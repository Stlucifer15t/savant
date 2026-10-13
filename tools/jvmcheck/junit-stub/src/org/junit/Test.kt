package org.junit

import kotlin.reflect.KClass

/**
 * The slice of JUnit 4 the unit tests use, reimplemented for the sandbox harness.
 *
 * The dev sandbox has no Android SDK and cannot reach maven central or google maven, so the real
 * `junit:junit:4.13.2` jar is not obtainable there. Signatures and semantics follow JUnit 4 —
 * including the message-first argument order — and CI still runs the real thing through gradle.
 * This exists so `tools/jvmcheck/check.sh` can compile and run the pure-JVM half of the suite
 * before anything is pushed.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test(val expected: KClass<out Throwable> = Throwable::class)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Before

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class After

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ignore(val value: String = "")

/** JUnit's richer assertion error: shows both sides of a failed equality. */
class ComparisonFailure(
    message: String?,
    private val expected: String?,
    private val actual: String?,
) : AssertionError(message) {
    override val message: String
        get() = buildString {
            append(super.message ?: "values differ")
            append("\n  expected: ")
            append(expected)
            append("\n  actual:   ")
            append(actual)
        }
}
