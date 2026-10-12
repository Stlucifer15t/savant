package dev.savant.domain.model

import kotlin.math.roundToInt

/**
 * How sure format detection is about a file. Surfaced verbatim in the FileChip
 * ("json · 97% certain") so the user can catch a wrong guess before editing.
 */
data class Confidence(val score: Float, val reason: String) {

    val level: Level
        get() = when {
            score >= 0.9f -> Level.CERTAIN
            score >= 0.6f -> Level.LIKELY
            score >= 0.3f -> Level.POSSIBLE
            else -> Level.UNLIKELY
        }

    val percent: Int get() = (score.coerceIn(0f, 1f) * 100).roundToInt()

    /** Chat copy: "97% certain — extension .json and a parseable object". */
    val label: String get() = "$percent% ${level.label}"

    val usable: Boolean get() = score >= MIN_USABLE

    enum class Level(val label: String) {
        CERTAIN("certain"),
        LIKELY("likely"),
        POSSIBLE("possible"),
        UNLIKELY("unlikely"),
    }

    companion object {
        const val MIN_USABLE = 0.3f

        fun none(reason: String) = Confidence(0f, reason)
        fun certain(reason: String) = Confidence(1f, reason)
        fun of(score: Float, reason: String) = Confidence(score.coerceIn(0f, 1f), reason)
    }
}
