package dev.savant.domain.parser

import dev.savant.domain.model.Confidence
import dev.savant.domain.model.SaveFormat

/** One parser's opinion about a file. */
data class DetectionResult(
    val parser: SaveParser,
    val format: SaveFormat,
    val confidence: Confidence,
)

/**
 * Asks every registered parser to score the file and keeps the ranking.
 *
 * Plain class on purpose: it is constructed by Hilt in the app and directly in unit tests.
 */
class ParserRegistry(private val parsers: List<SaveParser>) {

    val formats: List<SaveFormat> get() = parsers.map { it.format }

    fun parserFor(format: SaveFormat): SaveParser? = parsers.firstOrNull { it.format == format }

    /** All scores above zero, best first. Shown in chat when detection is ambiguous. */
    fun candidates(bytes: ByteArray, fileName: String?): List<DetectionResult> =
        parsers.map { parser ->
            DetectionResult(parser, parser.format, runCatching { parser.detect(bytes, fileName) }
                .getOrElse { Confidence.none("detection failed: ${it.message}") })
        }
            .filter { it.confidence.score > 0f }
            .sortedByDescending { it.confidence.score }

    fun best(bytes: ByteArray, fileName: String?): DetectionResult? =
        candidates(bytes, fileName).firstOrNull { it.confidence.usable }

    /** Parses with the best matching parser, or with [preferred] when the user/plugin chose one. */
    fun parse(
        bytes: ByteArray,
        fileName: String?,
        preferred: SaveFormat? = null,
        hints: ParseHints = ParseHints(),
    ): ParseResult {
        val detection = preferred?.let { format ->
            val parser = parserFor(format)
                ?: throw SavantParserException("no parser installed for ${format.badge}", format)
            DetectionResult(parser, format, Confidence.certain("format chosen by ${hints.pluginId ?: "user"}"))
        } ?: best(bytes, fileName)

        if (detection == null) {
            throw SavantParserException(
                "no parser recognised this file. it does not look like json, xml, ini, rpgsave, sqlite or a known binary layout",
            )
        }
        val result = detection.parser.parse(ParseRequest(bytes, fileName, hints))
        return result.copy(detection = detection.confidence)
    }

    companion object {
        val EMPTY = ParserRegistry(emptyList())
    }
}
