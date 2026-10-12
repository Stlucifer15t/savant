package dev.savant.domain.model

/**
 * Marks a display name or an explanation as produced by the AI rather than read from the file.
 * Anything with a guess is rendered with the "(likely)" suffix and a dotted underline so the
 * user never mistakes an invention for a fact from the save.
 */
data class AiGuess(
    val displayName: String,
    val confidence: Float,
    val explanation: String? = null,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be 0..1" }
    }

    val confidenceLabel: String
        get() = when {
            confidence >= 0.75f -> "likely"
            confidence >= 0.45f -> "maybe"
            else -> "guess"
        }
}
