package dev.savant.jvmcheck

import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.None
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * A tiny JUnit 4 runner for the sandbox harness: finds `*Test` classes in a compiled output
 * directory, runs every `@Test` method with its `@Before` / `@After` hooks, and prints one line per
 * failure. Exit code is 0 only when everything passed.
 *
 * Deliberately boring: reflection, no ordering guarantees beyond "declaration order", no listeners.
 * CI runs the real junit task; this is what makes the same tests runnable before pushing.
 *
 * Arguments: the class directory, then an optional case-insensitive substring that a test class's
 * simple name must contain (`Json` runs JsonSaveParserTest and JsonPayloadTest).
 */
object TestRunner {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "build/jvmcheck/test-classes")
        val filter = args.getOrNull(1)?.trim().orEmpty()
        if (!root.isDirectory) {
            System.err.println("no compiled test classes at ${root.path}")
            kotlin.system.exitProcess(2)
        }

        val classes = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .filter { it.endsWith("Test") && !it.contains('$') }
            .filter { filter.isEmpty() || it.substringAfterLast('.').contains(filter, ignoreCase = true) }
            .sorted()
            .toList()

        if (classes.isEmpty()) {
            System.err.println(if (filter.isEmpty()) "no test classes found" else "no test class matches '$filter'")
            kotlin.system.exitProcess(2)
        }

        var passed = 0
        var failed = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        for (name in classes) {
            val type = try {
                Class.forName(name)
            } catch (missing: Throwable) {
                failed++
                failures += "$name — could not load: ${missing.message}"
                continue
            }
            if (type.isInterface || java.lang.reflect.Modifier.isAbstract(type.modifiers)) continue
            val ignoredClass = type.getAnnotation(Ignore::class.java) != null

            val methods = type.declaredMethods
                .filter { it.getAnnotation(Test::class.java) != null }
                .sortedBy { it.name }

            for (method in methods) {
                val label = "$name.${method.name}"
                if (ignoredClass || method.getAnnotation(Ignore::class.java) != null) {
                    skipped++
                    continue
                }
                val outcome = runTest(type, method)
                when (outcome) {
                    null -> passed++
                    else -> {
                        failed++
                        failures += "$label — $outcome"
                        println("FAIL $label")
                        println("     $outcome")
                    }
                }
            }
        }

        println()
        println("$passed passed, $failed failed" + if (skipped > 0) ", $skipped skipped" else "")
        if (failures.isNotEmpty()) {
            println()
            failures.forEach { println("  $it") }
            kotlin.system.exitProcess(1)
        }
    }

    /** Returns null when the test passed, otherwise a one-line description of what went wrong. */
    private fun runTest(
        type: Class<*>,
        method: java.lang.reflect.Method,
    ): String? {
        val instance = try {
            type.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        } catch (thrown: Throwable) {
            return "could not construct: ${thrown.message}"
        }

        val expected = method.getAnnotation(Test::class.java).expected.java
        val expectsThrow = expected != None::class.java
        val hooks = type.declaredMethods.filter {
            it.getAnnotation(Before::class.java) != null || it.getAnnotation(After::class.java) != null
        }.sortedBy { it.name }

        try {
            hooks.filter { it.getAnnotation(Before::class.java) != null }
                .forEach { it.isAccessible = true; it.invoke(instance) }
            method.isAccessible = true
            method.invoke(instance)
        } catch (wrapped: InvocationTargetException) {
            val cause = wrapped.targetException
            // Only a declared `expected =` swallows a throw. Anything else is a failure.
            if (expectsThrow && expected.isInstance(cause)) return null
            return describe(cause)
        } catch (thrown: Throwable) {
            return describe(thrown)
        } finally {
            hooks.filter { it.getAnnotation(After::class.java) != null }
                .forEach { runCatching { it.isAccessible = true; it.invoke(instance) } }
        }

        if (expectsThrow) return "expected ${expected.name} to be thrown, nothing was"
        return null
    }

    private fun describe(thrown: Throwable): String {
        val head = "${thrown.javaClass.simpleName}: ${thrown.message.orEmpty().lineSequence().first()}"
        val frame = thrown.stackTrace.firstOrNull { it.className.startsWith("dev.savant") }
        return if (frame == null) head else "$head (${frame.fileName}:${frame.lineNumber})"
    }
}
