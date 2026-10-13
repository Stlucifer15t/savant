package dev.savant.domain.finder

import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.asDouble
import kotlin.math.abs

/**
 * Result of comparing two saves.
 *
 * [lines] is the staggered monospace log the finder prints; it is part of the result because
 * "what did savant actually look at" is the thing a user has to be able to check.
 */
data class DiffReport(
    val sizeA: Int,
    val sizeB: Int,
    val changedBytes: Int,
    val runs: List<ByteRun>,
    val candidates: List<Candidate>,
    val lines: List<String>,
    val sameSize: Boolean,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = candidates.isEmpty()
    val best: Candidate? get() = candidates.firstOrNull()
}

/**
 * The value finder for files savant cannot parse.
 *
 * The workflow is the one every cheat engine converged on, because it is the one that works:
 *
 * 1. play, change one thing in the game (spend gold, take damage), save again;
 * 2. hand savant both files — [compare] looks only at the bytes that differ, interprets each
 *    changed stretch as every fixed width number in both byte orders, and ranks what it finds;
 * 3. if too many candidates survive, save a third time and [narrow] drops the ones that went back
 *    to their old value and promotes the ones that held or moved again;
 * 4. name the winner. It is stored per format, and step 7 turns those into a plugin mapping.
 *
 * Ranking is deliberately conservative and always explains itself: a candidate carries the reasons
 * for its score, and nothing here writes a byte. Scanning only the changed runs is what makes this
 * usable on a 50 MB file — reading every offset of every type would be 800 million reads for
 * nothing, when 99.9% of the file did not move.
 */
object SaveDiff {

    /**
     * Contiguous stretches where the two files differ.
     *
     * Compared up to the shorter length: if a save grew, the tail is new data and there is no
     * second file to compare it against. [maxRuns] stops a pathological case — two unrelated files
     * differ everywhere, and a run per byte would be a list of a million entries.
     */
    fun changedRuns(a: ByteArray, b: ByteArray, maxRuns: Int = 4_096): List<ByteRun> {
        val shared = minOf(a.size, b.size)
        val runs = mutableListOf<ByteRun>()
        var start = -1
        for (i in 0 until shared) {
            if (a[i] != b[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                runs += ByteRun(start, i - start)
                start = -1
                if (runs.size >= maxRuns) return runs
            }
        }
        if (start >= 0 && runs.size < maxRuns) runs += ByteRun(start, shared - start)
        return runs
    }

    /** Compares two saves and ranks every typed value that changed. */
    fun compare(a: ByteArray, b: ByteArray, spec: ScanSpec = ScanSpec()): DiffReport {
        val lines = mutableListOf<String>()
        val sameSize = a.size == b.size
        lines += "a · ${a.size} bytes"
        lines += "b · ${b.size} bytes"
        if (!sameSize) {
            lines += "sizes differ — only the first ${minOf(a.size, b.size)} bytes are comparable"
        }

        val runs = changedRuns(a, b)
        val changed = runs.sumOf { it.length }
        if (changed == 0) {
            lines += "no bytes differ"
            lines += "nothing to rank — change something in the game and save again"
            return DiffReport(a.size, b.size, 0, runs, emptyList(), lines, sameSize, false)
        }
        lines += "$changed byte(s) differ in ${runs.size} run(s)"
        runs.take(6).forEach { lines += "  run ${it.label}" }
        if (runs.size > 6) lines += "  … ${runs.size - 6} more"

        val found = mutableListOf<Candidate>()
        var stopped = false
        val window = minOf(a.size, b.size)
        // A hard ceiling on raw work: a file that differs everywhere is two different files, not two
        // saves of one game, and the honest answer is "too many candidates" rather than a hang.
        val hardCap = spec.limit * 8

        run loop@{
            for (run in runs) {
                for (type in spec.types) {
                    val size = BinaryValues.sizeOf(type)
                    if (size <= 0) continue
                    val from = (run.start - size + 1).coerceAtLeast(0)
                    val to = minOf(run.endInclusive, window - size)
                    if (from > to) continue
                    for (endian in spec.endianness) {
                        for (offset in from..to) {
                            if (spec.alignedOnly && size > 1 && offset % size != 0) continue
                            val before = BinaryValues.read(a, offset, type, endian) ?: continue
                            val after = BinaryValues.read(b, offset, type, endian) ?: continue
                            if (before == after) continue
                            found += rank(offset, type, endian, before, after, run, sameSize, a, b)
                            if (found.size >= hardCap) {
                                stopped = true
                                // `run loop@{}` is a lambda, so this is a return, not a break.
                                return@loop
                            }
                        }
                    }
                }
            }
        }

        val ranked = found
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.offset })
            .take(spec.limit)
        val truncated = stopped || found.size > spec.limit

        lines += "read ${spec.typeCount} type(s) × ${spec.endianness.size} byte order(s) across the changed runs"
        lines += if (found.isEmpty()) {
            "no typed value changed — the bytes that moved are not a number savant recognises"
        } else {
            "${ranked.size} candidate(s) ranked" + if (truncated) ", list capped at ${spec.limit}" else ""
        }
        ranked.firstOrNull()?.let { lines += "best: ${it.offsetLabel} ${it.typeTag} ${it.changeText}" }

        return DiffReport(
            sizeA = a.size,
            sizeB = b.size,
            changedBytes = changed,
            runs = runs,
            candidates = ranked,
            lines = lines,
            sameSize = sameSize,
            truncated = truncated,
        )
    }

    /**
     * Finds every offset that currently holds [target].
     *
     * The single file case: the user knows the number ("gold is 1234") and wants to know where it
     * lives. One pass per type and byte order, no ranking of neighbours, because there is nothing
     * to compare against — the score says how plausible the *reading* is, not how sure savant is
     * that this is the gold counter.
     */
    fun findValue(bytes: ByteArray, target: FieldValue, spec: ScanSpec = ScanSpec()): List<Candidate> {
        val found = mutableListOf<Candidate>()
        for (type in spec.types) {
            val size = BinaryValues.sizeOf(type)
            if (size <= 0) continue
            for (endian in spec.endianness) {
                var offset = 0
                while (offset + size <= bytes.size) {
                    if (!spec.alignedOnly || size <= 1 || offset % size == 0) {
                        val value = BinaryValues.read(bytes, offset, type, endian)
                        if (value != null && matches(value, target, type)) {
                            found += Candidate(
                                offset = offset,
                                type = type,
                                endianness = endian,
                                before = target,
                                after = value,
                                score = exactScore(bytes, offset, type, endian),
                                reasons = exactReasons(bytes, offset, type),
                                runLength = size,
                            )
                            if (found.size >= spec.limit) return found.sortedBy { it.offset }
                        }
                    }
                    offset++
                }
            }
        }
        return found.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.offset })
    }

    /**
     * Re-reads every candidate in a third save and re-ranks.
     *
     * Three outcomes, and they are not equally good:
     * - the value held → this offset really does store it;
     * - it moved again → still a live counter, promoted a little less;
     * - it went back to the first value → it was a transient (a timer, a checksum, a cache), dropped.
     */
    fun narrow(candidates: List<Candidate>, third: ByteArray): List<Candidate> {
        val kept = mutableListOf<Candidate>()
        var dropped = 0
        for (candidate in candidates) {
            val value = BinaryValues.read(third, candidate.offset, candidate.type, candidate.endianness)
            if (value == null) {
                dropped++
                continue
            }
            when {
                matches(value, candidate.after ?: value, candidate.type) -> kept += promote(
                    candidate,
                    0.22f,
                    "held in the third save",
                )

                matches(value, candidate.before ?: value, candidate.type) -> dropped++

                else -> kept += promote(candidate, 0.12f, "moved again in the third save")
                    .copy(before = candidate.after, after = value)
            }
        }
        return kept.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.offset })
    }

    /** How many candidates a third save removed, for the log line. */
    fun narrowSummary(before: Int, after: Int): String = when {
        before == 0 -> "nothing to narrow"
        after == 0 -> "all $before candidates went back to their old value — none of them is the field"
        else -> "$after of $before candidates survived the third save"
    }

    private fun promote(candidate: Candidate, by: Float, reason: String): Candidate =
        candidate.copy(score = (candidate.score + by).coerceAtMost(0.99f), reasons = candidate.reasons + reason)

    /**
     * The ranking.
     *
     * Every branch adds a reason, because a percentage on its own is not an explanation and the
     * user is about to make an edit based on it.
     */
    private fun rank(
        offset: Int,
        type: FieldType,
        endian: Endianness,
        before: FieldValue,
        after: FieldValue,
        run: ByteRun,
        sameSize: Boolean,
        a: ByteArray,
        b: ByteArray,
    ): Candidate {
        val size = BinaryValues.sizeOf(type)
        var score = 0.30f
        val reasons = mutableListOf<String>()

        when {
            run.length == size -> {
                // The strongest signal there is: exactly one value of this width moved.
                score += 0.30f
                reasons += "exactly $size byte(s) changed"
            }

            run.length < size -> {
                score += 0.12f
                reasons += "${run.length} of $size byte(s) changed"
            }

            run.length <= size * 2 -> {
                score += 0.04f
                reasons += "run of ${run.length} byte(s)"
            }

            else -> {
                score -= 0.18f
                reasons += "run of ${run.length} byte(s)"
            }
        }

        // A counter grows from its low end, so the changed bytes sitting at the low edge of the
        // window is what a real i32 gold counter looks like — and it depends on the byte order.
        val lowEdgeMoved = if (endian == Endianness.LITTLE) offset == run.start else run.endInclusive == offset + size - 1
        if (lowEdgeMoved && size > 1) {
            score += 0.08f
            reasons += "changed at the low byte"
        }

        if (size > 1 && offset % size == 0) {
            score += 0.14f
            reasons += "aligned"
        }
        if (!sameSize) {
            score -= 0.22f
            reasons += "file sizes differ"
        }
        if (BinaryValues.looksLikeText(a, offset, size) && BinaryValues.looksLikeText(b, offset, size)) {
            score -= 0.28f
            reasons += "bytes are printable text"
        }

        val previous = (before as? FieldValue.Int64)?.value
        when (after) {
            is FieldValue.Int64 -> {
                // `.value`, not `asLong()`: inside this branch the type is known, and the extension
                // would hand back a nullable and lose the comparison.
                val value = after.value
                if (value in 0..10_000_000) {
                    score += 0.10f
                    reasons += "plausible magnitude"
                } else if (value < 0) {
                    score -= 0.10f
                    reasons += "negative"
                }
                if (previous != null && value > previous && value - previous <= 100_000) {
                    score += 0.06f
                    reasons += "small increase"
                }
                if (size == 8) {
                    score -= 0.08f
                    reasons += "64 bit integers are rare in saves"
                }
                if (size >= 2 && BinaryValues.allBytesSet(b, offset, size)) {
                    score -= 0.10f
                    reasons += "no zero bytes — digest shaped"
                }
            }

            is FieldValue.Real -> {
                val value = after.value
                if (!value.isFinite()) {
                    score -= 0.30f
                    reasons += "not a finite number"
                } else if (abs(value) <= 1_000_000.0) {
                    score += 0.08f
                    reasons += "plausible magnitude"
                    if (type == FieldType.FLOAT && value != 0.0 && abs(value) < 1.0) {
                        score += 0.04f
                        reasons += "ratio sized float"
                    }
                } else {
                    score -= 0.20f
                    reasons += "implausible magnitude"
                }
            }

            else -> Unit
        }

        if (endian == Endianness.BIG) {
            // Not wrong, just less common: phone and pc saves are overwhelmingly little endian.
            score -= 0.04f
        }

        return Candidate(
            offset = offset,
            type = type,
            endianness = endian,
            before = before,
            after = after,
            score = score.coerceIn(0.02f, 0.99f),
            reasons = reasons,
            runLength = run.length,
        )
    }

    private fun exactScore(bytes: ByteArray, offset: Int, type: FieldType, endian: Endianness): Float {
        val size = BinaryValues.sizeOf(type)
        var score = 0.40f
        if (size > 1 && offset % size == 0) score += 0.18f
        if (BinaryValues.looksLikeText(bytes, offset, size)) score -= 0.22f
        if (endian == Endianness.BIG) score -= 0.04f
        return score.coerceIn(0.02f, 0.99f)
    }

    private fun exactReasons(bytes: ByteArray, offset: Int, type: FieldType): List<String> {
        val size = BinaryValues.sizeOf(type)
        val reasons = mutableListOf("exact match")
        if (size > 1 && offset % size == 0) reasons += "aligned"
        if (BinaryValues.looksLikeText(bytes, offset, size)) reasons += "bytes are printable text"
        return reasons
    }

    /**
     * Numeric comparison with a tolerance for reals.
     *
     * A float stored as 42.5 reads back as exactly 42.5, but a value the user typed as `0.1` does
     * not survive a float32 round trip bit for bit, and refusing to find it would be worse than
     * accepting a hair of slop.
     */
    private fun matches(found: FieldValue, target: FieldValue, type: FieldType): Boolean {
        val foundNumber = found.asDouble()
        val targetNumber = target.asDouble()
        if (foundNumber != null && targetNumber != null) {
            return when (type) {
                FieldType.FLOAT -> abs(foundNumber - targetNumber) <= 1e-3 * maxOf(1.0, abs(targetNumber))
                FieldType.DOUBLE -> abs(foundNumber - targetNumber) <= 1e-9 * maxOf(1.0, abs(targetNumber))
                else -> foundNumber == targetNumber
            }
        }
        return found == target
    }
}
