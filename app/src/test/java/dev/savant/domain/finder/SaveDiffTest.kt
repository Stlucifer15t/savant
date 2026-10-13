package dev.savant.domain.finder

import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * The value finder, on two small sample binaries.
 *
 * The sample is shaped like a real save: a magic header, a little endian gold counter, a float hp,
 * a short lives counter, an ascii name, a big endian play time and a crc32 trailer that moves every
 * time anything else does. That trailer matters — a finder that cannot tell a checksum from a
 * counter is not a finder, and every assertion below is about which of the two gets ranked first.
 */
class SaveDiffTest {

    // ---- the sample saves ---------------------------------------------------------

    private fun sampleSave(
        gold: Int = 120,
        hp: Float = 42.5f,
        lives: Short = 3,
        name: String = "Aric",
        playTime: Int = 600,
        scratch: Int = 0,
    ): ByteArray {
        val bytes = ByteArray(64)
        "SVS1".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putU16Little(bytes, 4, 7)
        putI32Little(bytes, 8, gold)
        putFloatLittle(bytes, 16, hp)
        putI16Little(bytes, 20, lives)
        name.toByteArray(Charsets.US_ASCII).copyInto(bytes, 24)
        putI32Big(bytes, 40, playTime)
        putI32Little(bytes, 48, scratch)
        putU32Little(bytes, 60, crc(bytes, 0, 60))
        return bytes
    }

    private fun putI32Little(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun putI32Big(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = ((value ushr 24) and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 3] = (value and 0xFF).toByte()
    }

    private fun putU32Little(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putI16Little(bytes: ByteArray, at: Int, value: Short) {
        bytes[at] = (value.toInt() and 0xFF).toByte()
        bytes[at + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
    }

    private fun putU16Little(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putFloatLittle(bytes: ByteArray, at: Int, value: Float) =
        putI32Little(bytes, at, java.lang.Float.floatToIntBits(value))

    private fun crc(bytes: ByteArray, from: Int, to: Int): Long {
        val digest = CRC32()
        digest.update(bytes, from, to - from)
        return digest.value
    }

    // ---- changed runs -------------------------------------------------------------

    @Test
    fun onlyTheBytesThatMovedComeBackAsRuns() {
        val before = sampleSave(gold = 120)
        val after = sampleSave(gold = 1_000)

        val runs = SaveDiff.changedRuns(before, after)

        // gold at 8..11 and the crc32 trailer at 60..63 — nothing else moved.
        assertTrue("expected a run over the gold counter: $runs", runs.any { it.start == 8 })
        assertTrue("expected a run over the checksum: $runs", runs.any { it.start == 60 })
        assertEquals(2, runs.size)
        assertTrue(runs.sumOf { it.length } <= 8)
    }

    @Test
    fun identicalFilesHaveNoRunsAndNoCandidates() {
        val report = SaveDiff.compare(sampleSave(), sampleSave())

        assertEquals(0, report.changedBytes)
        assertTrue(report.candidates.isEmpty())
        assertTrue(report.lines.any { it.contains("no bytes differ") })
    }

    @Test
    fun theTailOfALongerFileIsNotCompared() {
        val before = sampleSave()
        val after = sampleSave() + ByteArray(16) { 0x41 }

        val runs = SaveDiff.changedRuns(before, after)
        assertTrue(runs.isEmpty())
    }

    // ---- ranking ------------------------------------------------------------------

    @Test
    fun theGoldCounterOutranksTheChecksumItDisturbed() {
        val before = sampleSave(gold = 120)
        val after = sampleSave(gold = 999_999)

        val report = SaveDiff.compare(before, after)

        val best = report.best
        assertNotNull(best)
        assertEquals(8, best!!.offset)
        assertEquals(FieldType.INT32, best.type)
        assertEquals(Endianness.LITTLE, best.endianness)
        assertEquals(FieldValue.Int64(120), best.before)
        assertEquals(FieldValue.Int64(999_999), best.after)
        assertTrue("a top candidate has to explain itself: ${best.reasons}", best.reasons.size >= 3)
        assertTrue(best.confidence > 0.7f)

        // The checksum changed too, and savant says so rather than hiding it — but it ranks it
        // below the counter, because every one of its bytes moved and none of them is zero.
        val checksum = report.candidates.firstOrNull { it.offset == 60 && it.type == FieldType.INT32 }
        assertNotNull("the crc32 trailer should also be a candidate", checksum)
        assertTrue(checksum!!.score < best.score)
        assertTrue(checksum.reasons.any { it.contains("digest shaped") })
        assertTrue(best.reasons.none { it.contains("digest shaped") })
    }

    @Test
    fun aFloatFieldIsFoundAsAFloat() {
        val report = SaveDiff.compare(sampleSave(hp = 42.5f), sampleSave(hp = 100f))

        val float = report.candidates.firstOrNull { it.type == FieldType.FLOAT && it.offset == 16 }
        assertNotNull("expected an f32 candidate at 16: ${report.candidates.take(5)}", float)
        assertEquals(42.5, (float!!.before as FieldValue.Real).value, 1e-6)
        assertEquals(100.0, (float.after as FieldValue.Real).value, 1e-6)
        // Only the third byte of the float moved, which is what a hp value going up looks like.
        assertTrue(float.reasons.any { it.contains("of 4 byte(s) changed") })
    }

    @Test
    fun aFloatReadingOfAnIntegerFieldIsRankedDown() {
        // 120 as a little endian float32 is a denormal, and no game stores one on purpose. That is
        // what stops four bytes of gold counter from winning the ranking as a hit points value.
        val report = SaveDiff.compare(sampleSave(gold = 120), sampleSave(gold = 999_999))

        val asFloat = report.candidates.firstOrNull { it.offset == 8 && it.type == FieldType.FLOAT }
        assertNotNull("the float reading of the same bytes is still a candidate", asFloat)
        assertTrue(asFloat!!.reasons.any { it.contains("denormal") })
        assertTrue(asFloat.score < report.best!!.score)
    }

    @Test
    fun aBigEndianFieldKeepsItsByteOrder() {
        val report = SaveDiff.compare(sampleSave(playTime = 600), sampleSave(playTime = 660))

        val candidate = report.candidates.firstOrNull { it.offset == 40 && it.endianness == Endianness.BIG }
        assertNotNull("expected a big endian candidate at 40", candidate)
        assertEquals(FieldValue.Int64(600), candidate!!.before)
        assertEquals(FieldValue.Int64(660), candidate.after)
    }

    @Test
    fun aTwoByteCounterIsFoundAsI16() {
        // 3 -> 300 moves both bytes of the counter, which is the clean case for a two byte field.
        val report = SaveDiff.compare(sampleSave(lives = 3), sampleSave(lives = 300))

        val candidate = report.candidates.firstOrNull { it.offset == 20 && it.type == FieldType.INT16 }
        assertNotNull("expected an i16 candidate at 20", candidate)
        assertEquals(FieldValue.Int64(3), candidate!!.before)
        assertEquals(FieldValue.Int64(300), candidate.after)
        assertTrue(candidate.reasons.any { it.contains("exactly 2 byte") })
    }

    @Test
    fun textThatReadsAsANumberIsFlaggedAsText() {
        val report = SaveDiff.compare(sampleSave(name = "Aric"), sampleSave(name = "Bric"))

        val name = report.candidates.firstOrNull { it.offset == 24 }
        assertNotNull("a changed name still produces candidates", name)
        assertTrue(
            "the name bytes are printable and savant has to say so: ${name!!.reasons}",
            report.candidates.filter { it.offset in 21..27 }
                .any { it.reasons.any { reason -> reason.contains("printable text") } },
        )
        assertTrue("a name is not a confident number", report.candidates.all { it.score < 0.9f })
    }

    @Test
    fun filesOfDifferentSizesArePenalisedAndSaidSo() {
        val before = sampleSave(gold = 120)
        val after = sampleSave(gold = 5_000) + ByteArray(4)

        val report = SaveDiff.compare(before, after)

        assertEquals(false, report.sameSize)
        assertTrue(report.lines.any { it.contains("sizes differ") })
        assertTrue(report.candidates.isNotEmpty())
        assertTrue(report.candidates.all { it.reasons.any { reason -> reason.contains("sizes differ") } })
    }

    @Test
    fun alignedOnlyDropsOddOffsets() {
        // A value that starts on an odd byte: real saves do this, and the flag has to honour it.
        val before = ByteArray(32)
        val after = ByteArray(32)
        putI32Little(before, 5, 10)
        putI32Little(after, 5, 20)

        val unaligned = SaveDiff.compare(before, after, ScanSpec(types = setOf(FieldType.INT32), endianness = setOf(Endianness.LITTLE)))
        assertTrue(unaligned.candidates.any { it.offset == 5 })

        val aligned = SaveDiff.compare(
            before,
            after,
            ScanSpec(types = setOf(FieldType.INT32), endianness = setOf(Endianness.LITTLE), alignedOnly = true),
        )
        assertTrue(aligned.candidates.none { it.offset == 5 })
    }

    // ---- narrowing with a third save ----------------------------------------------

    @Test
    fun aThirdSaveKeepsWhatHeldAndDropsWhatWentBack() {
        val first = sampleSave(gold = 120, scratch = 0)
        val second = sampleSave(gold = 999, scratch = 4242)
        // The player spent gold again; the scratch field went back to what it was.
        val third = sampleSave(gold = 1_500, scratch = 0)

        val report = SaveDiff.compare(first, second)
        val narrowed = SaveDiff.narrow(report.candidates, third)

        val gold = narrowed.firstOrNull { it.offset == 8 && it.type == FieldType.INT32 }
        assertNotNull("gold should survive narrowing", gold)
        assertTrue(gold!!.reasons.any { it.contains("third save") })
        assertEquals(FieldValue.Int64(1_500), gold.after)

        val scratch = narrowed.firstOrNull { it.offset == 48 && it.type == FieldType.INT32 }
        assertEquals("a field that went back to its old value is not the one", null, scratch)

        assertTrue(SaveDiff.narrowSummary(report.candidates.size, narrowed.size).contains("survived"))
    }

    @Test
    fun narrowingAFieldThatHeldItsValuePromotesIt() {
        val first = sampleSave(gold = 120)
        val second = sampleSave(gold = 999)
        val third = sampleSave(gold = 999)

        val report = SaveDiff.compare(first, second)
        val before = report.candidates.first { it.offset == 8 && it.type == FieldType.INT32 }
        val after = SaveDiff.narrow(listOf(before), third).first()

        assertTrue(after.score > before.score)
        assertTrue(after.reasons.any { it.contains("held") })
    }

    // ---- exact value scan ---------------------------------------------------------

    @Test
    fun anExactValueScanFindsTheOffsetThatHoldsIt() {
        val bytes = sampleSave(gold = 1_234)

        val hits = SaveDiff.findValue(bytes, FieldValue.Int64(1_234), ScanSpec(types = setOf(FieldType.INT32)))

        assertTrue(hits.any { it.offset == 8 && it.endianness == Endianness.LITTLE })
        assertTrue(hits.all { it.reasons.contains("exact match") })
    }

    @Test
    fun anExactFloatScanToleratesWhatTheUserTyped() {
        val bytes = sampleSave(hp = 42.5f)

        // The user types 42.5, which arrives as a double; the file holds a float32.
        val hits = SaveDiff.findValue(bytes, FieldValue.parse("42.5"), ScanSpec(types = setOf(FieldType.FLOAT)))

        assertTrue("expected the f32 at 16: $hits", hits.any { it.offset == 16 })
    }

    @Test
    fun anExactScanFindsNothingWhenTheValueIsNotThere() {
        val bytes = sampleSave(gold = 1_234)
        val hits = SaveDiff.findValue(bytes, FieldValue.Int64(77_777), ScanSpec(types = setOf(FieldType.INT32)))
        assertTrue(hits.isEmpty())
    }

    // ---- scale --------------------------------------------------------------------

    @Test
    fun twoUnrelatedFilesAreCappedInsteadOfHanging() {
        val random = Random(20261013)
        val a = random.nextBytes(200_000)
        val b = random.nextBytes(200_000)

        val started = System.nanoTime()
        val report = SaveDiff.compare(a, b, ScanSpec(limit = 200))
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue("expected the list to be capped, got ${report.candidates.size}", report.truncated)
        assertTrue(report.candidates.size <= 200)
        assertTrue("two unrelated 200 kb files took ${millis}ms", millis < 20_000)
    }

    @Test
    fun aLargeSaveWithOneChangeIsStillFast() {
        val big = ByteArray(4 * 1024 * 1024)
        Random(7).nextBytes(big)
        val edited = big.copyOf()
        putI32Little(edited, 1_000_000, 12_345)
        putI32Little(big, 1_000_000, 120)

        val started = System.nanoTime()
        val report = SaveDiff.compare(big, edited, ScanSpec(types = setOf(FieldType.INT32), endianness = setOf(Endianness.LITTLE)))
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue(report.candidates.any { it.offset == 1_000_000 })
        assertTrue("a 4 mb one-field diff took ${millis}ms", millis < 5_000)
    }

    @Test
    fun theReportCarriesTheLogTheUserSees() {
        val report = SaveDiff.compare(sampleSave(gold = 1), sampleSave(gold = 2))

        assertTrue(report.lines.any { it.contains("differ") })
        assertTrue(report.lines.any { it.contains("candidate") })
        assertTrue(report.lines.any { it.contains("best:") })
    }
}
