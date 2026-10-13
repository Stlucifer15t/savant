package org.junit

/**
 * `org.junit.Assert`, with the overload set the tests actually call.
 *
 * Keeping both the two-argument and message-first three-argument forms matters: Kotlin resolves
 * `assertEquals(message, expected, actual)` against exactly these shapes, so a stub with a
 * different surface would test something other than what CI runs.
 */
object Assert {

    fun fail(message: String?): Nothing = throw AssertionError(message ?: "failed")

    fun fail(): Nothing = throw AssertionError("failed")

    // ---- equality ------------------------------------------------------------------

    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected == actual) return
        // Arrays need structural comparison, exactly like JUnit does.
        if (expected is ByteArray && actual is ByteArray) return assertArrayEquals(message, expected, actual)
        if (expected is IntArray && actual is IntArray) return assertArrayEquals(message, expected, actual)
        if (expected is LongArray && actual is LongArray) return assertArrayEquals(message, expected, actual)
        if (expected is DoubleArray && actual is DoubleArray) return assertArrayEquals(message, expected, actual)
        if (expected is Array<*> && actual is Array<*>) return assertArrayEquals(message, expected, actual)
        throw ComparisonFailure(message, display(expected), display(actual))
    }

    fun assertEquals(expected: Long, actual: Long) = assertEquals(null as String?, expected, actual)

    fun assertEquals(message: String?, expected: Long, actual: Long) {
        if (expected != actual) throw ComparisonFailure(message, expected.toString(), actual.toString())
    }

    fun assertEquals(expected: Boolean, actual: Boolean) = assertEquals(null as String?, expected, actual)

    fun assertEquals(message: String?, expected: Boolean, actual: Boolean) {
        if (expected != actual) throw ComparisonFailure(message, expected.toString(), actual.toString())
    }

    fun assertEquals(expected: Double, actual: Double) = assertEquals(expected, actual, 0.0)

    fun assertEquals(expected: Double, actual: Double, delta: Double) =
        assertEquals(null as String?, expected, actual, delta)

    /**
     * Floats and doubles compare bit patterns when delta is zero, like JUnit: `NaN == NaN` passes,
     * `0.0 != -0.0`, and no silent rounding.
     */
    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (expected.isNaN() && actual.isNaN()) return
        val ok = if (delta == 0.0) {
            expected.toRawBits() == actual.toRawBits()
        } else {
            kotlin.math.abs(expected - actual) <= delta
        }
        if (!ok) throw ComparisonFailure(message, expected.toString(), actual.toString())
    }

    fun assertEquals(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0f)

    fun assertEquals(expected: Float, actual: Float, delta: Float) =
        assertEquals(null as String?, expected, actual, delta)

    fun assertEquals(message: String?, expected: Float, actual: Float) =
        assertEquals(message, expected, actual, 0.0f)

    fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (expected.isNaN() && actual.isNaN()) return
        val ok = if (delta == 0.0f) {
            expected.toRawBits() == actual.toRawBits()
        } else {
            kotlin.math.abs(expected - actual) <= delta
        }
        if (!ok) throw ComparisonFailure(message, expected.toString(), actual.toString())
    }

    fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionError("expected values to differ, both were $actual")
    }

    // ---- arrays --------------------------------------------------------------------

    fun assertArrayEquals(expected: ByteArray?, actual: ByteArray?) =
        assertArrayEquals(null as String?, expected, actual)

    fun assertArrayEquals(message: String?, expected: ByteArray?, actual: ByteArray?) {
        if (expected == null || actual == null) {
            if (expected !== actual) throw ComparisonFailure(message, display(expected), display(actual))
            return
        }
        if (!expected.contentEquals(actual)) {
            throw ComparisonFailure(
                message,
                "byte[${expected.size}] ${expected.take(32).joinToString(" ") { "%02x".format(it) }}",
                "byte[${actual.size}] ${actual.take(32).joinToString(" ") { "%02x".format(it) }}",
            )
        }
    }

    fun assertArrayEquals(expected: IntArray?, actual: IntArray?) =
        assertArrayEquals(null as String?, expected, actual)

    fun assertArrayEquals(message: String?, expected: IntArray?, actual: IntArray?) {
        if (expected == null || actual == null) {
            if (expected !== actual) throw ComparisonFailure(message, display(expected), display(actual))
            return
        }
        if (!expected.contentEquals(actual)) {
            throw ComparisonFailure(message, expected.joinToString(" "), actual.joinToString(" "))
        }
    }

    fun assertArrayEquals(expected: LongArray?, actual: LongArray?) =
        assertArrayEquals(null as String?, expected, actual)

    fun assertArrayEquals(message: String?, expected: LongArray?, actual: LongArray?) {
        if (expected == null || actual == null) {
            if (expected !== actual) throw ComparisonFailure(message, display(expected), display(actual))
            return
        }
        if (!expected.contentEquals(actual)) {
            throw ComparisonFailure(message, expected.joinToString(" "), actual.joinToString(" "))
        }
    }

    fun assertArrayEquals(expected: DoubleArray?, actual: DoubleArray?) =
        assertArrayEquals(null as String?, expected, actual)

    fun assertArrayEquals(message: String?, expected: DoubleArray?, actual: DoubleArray?) {
        if (expected == null || actual == null) {
            if (expected !== actual) throw ComparisonFailure(message, display(expected), display(actual))
            return
        }
        if (!expected.contentEquals(actual)) {
            throw ComparisonFailure(message, expected.joinToString(" "), actual.joinToString(" "))
        }
    }

    fun assertArrayEquals(expected: Array<*>?, actual: Array<*>?) =
        assertArrayEquals(null as String?, expected, actual)

    fun assertArrayEquals(message: String?, expected: Array<*>?, actual: Array<*>?) {
        if (expected == null || actual == null) {
            if (expected !== actual) throw ComparisonFailure(message, display(expected), display(actual))
            return
        }
        if (!expected.contentEquals(actual)) {
            throw ComparisonFailure(message, expected.contentToString(), actual.contentToString())
        }
    }

    // ---- truth ---------------------------------------------------------------------

    fun assertTrue(condition: Boolean) = assertTrue(null as String?, condition)

    fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionError(message ?: "expected true")
    }

    fun assertFalse(condition: Boolean) = assertFalse(null as String?, condition)

    fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionError(message ?: "expected false")
    }

    // ---- null ----------------------------------------------------------------------

    fun assertNull(actual: Any?) = assertNull(null as String?, actual)

    fun assertNull(message: String?, actual: Any?) {
        if (actual != null) throw AssertionError((message ?: "") + " expected null but was $actual")
    }

    fun assertNotNull(actual: Any?) = assertNotNull(null as String?, actual)

    fun assertNotNull(message: String?, actual: Any?) {
        if (actual == null) throw AssertionError(message ?: "expected not null")
    }

    // ---- identity ------------------------------------------------------------------

    fun assertSame(expected: Any?, actual: Any?) = assertSame(null as String?, expected, actual)

    fun assertSame(message: String?, expected: Any?, actual: Any?) {
        if (expected !== actual) throw ComparisonFailure(message, display(expected), display(actual))
    }

    fun assertNotSame(unexpected: Any?, actual: Any?) {
        if (unexpected === actual) throw AssertionError("expected different instances")
    }

    /** JUnit 4.13 shape: `assertThrows(IOException::class.java) { ... }`. */
    fun <T : Throwable> assertThrows(expected: Class<T>, runnable: () -> Unit): T {
        try {
            runnable()
        } catch (thrown: Throwable) {
            if (expected.isInstance(thrown)) return expected.cast(thrown)
            throw AssertionError("expected ${expected.name} but was ${thrown.javaClass.name}: ${thrown.message}")
        }
        throw AssertionError("expected ${expected.name} to be thrown, nothing was")
    }

    private fun display(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        else -> value.toString()
    }
}
