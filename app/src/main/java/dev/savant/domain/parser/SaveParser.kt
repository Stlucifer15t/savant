package dev.savant.domain.parser

import dev.savant.domain.model.FieldTree

@JvmInline
value class Confidence private constructor(val score: Float) : Comparable<Confidence> {
    override fun compareTo(other: Confidence): Int = score.compareTo(other.score)

    val label: String
        get() = when {
            score >= 0.85f -> "high"
            score >= 0.60f -> "medium"
            score > 0f -> "low"
            else -> "none"
        }

    val percent: Int get() = (score * 100).toInt().coerceIn(0, 100)

    fun boosted(amount: Float): Confidence = of(score + amount)

    companion object {
        val None = Confidence(0f)
        fun of(score: Float) = Confidence(score.coerceIn(0f, 1f))
    }
}

interface ChecksumStrategy {
    val displayName: String
    fun verify(bytes: ByteArray): Boolean
    fun apply(bytes: ByteArray, tree: FieldTree): ByteArray
}

object NoChecksumStrategy : ChecksumStrategy {
    override val displayName: String = "not required"
    override fun verify(bytes: ByteArray): Boolean = true
    override fun apply(bytes: ByteArray, tree: FieldTree): ByteArray = bytes
}

interface SaveParser {
    val id: String
    val displayName: String
    val extensions: Set<String>
    val checksumStrategy: ChecksumStrategy

    fun detect(bytes: ByteArray): Confidence
    fun parse(bytes: ByteArray): FieldTree
    fun write(tree: FieldTree): ByteArray
}

data class DetectedParser(
    val parser: SaveParser,
    val confidence: Confidence,
)

class UnsupportedSaveFormatException(message: String) : IllegalArgumentException(message)
class CorruptSaveException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
