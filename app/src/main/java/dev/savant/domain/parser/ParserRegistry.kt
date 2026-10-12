package dev.savant.domain.parser

class ParserRegistry(parsers: Set<SaveParser>) {
    private val installed = parsers.sortedBy { it.displayName }

    fun installedParsers(): List<SaveParser> = installed

    fun detect(fileName: String, bytes: ByteArray): DetectedParser {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val best = installed
            .map { parser ->
                val structural = parser.detect(bytes)
                val confidence = if (extension in parser.extensions) {
                    if (structural.score > 0f) structural.boosted(0.06f)
                    else Confidence.of(0.52f)
                } else {
                    structural
                }
                DetectedParser(parser, confidence)
            }
            .maxByOrNull { it.confidence.score }

        return best?.takeIf { it.confidence.score >= MINIMUM_CONFIDENCE }
            ?: throw UnsupportedSaveFormatException(
                "format not recognized. this build supports JSON and XML saves.",
            )
    }

    fun parser(id: String): SaveParser = installed.firstOrNull { it.id == id }
        ?: throw UnsupportedSaveFormatException("parser '$id' is not installed")

    private companion object {
        const val MINIMUM_CONFIDENCE = 0.45f
    }
}
