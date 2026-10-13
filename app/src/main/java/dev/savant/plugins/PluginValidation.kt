package dev.savant.plugins

import dev.savant.codecs.Checksums

/**
 * Everything a document has to satisfy before savant will read a file with it.
 *
 * Most of this is already enforced while reading (unknown keys, missing paths); this pass is about
 * the things that are only wrong in context: a parser this build does not have, a codec whose key
 * is missing, a checksum that would be stored outside the data it covers, two mappings that write
 * into the same bytes.
 *
 * The same list is what the import screen shows. A plugin that fails is not installed — it is
 * shown, with reasons, and the accept button stays disabled.
 */
object PluginValidator {

    /** How many elements a `pointer` count may expand to before savant stops scanning. */
    const val MAX_ARRAY_ELEMENTS = 256

    fun validate(document: PluginDocument): List<PluginProblem> {
        val problems = mutableListOf<PluginProblem>()

        if (!document.parser.supported) {
            problems += PluginProblem.of(
                "parser",
                "the ${document.parser.label} reader lands in build step 9 — this definition is fine, it just has nothing to read with yet",
            )
        }
        if (!document.codec.supported) {
            problems += PluginProblem.of(
                "codec",
                "the ${document.codec.label} codec lands in build step 9 — until then savant cannot reopen what it closes",
            )
        }
        if (document.codec == PluginCodecKind.XOR) {
            val key = document.xorKey?.trim().orEmpty()
            when {
                key.isEmpty() -> problems += PluginProblem.of("xorKey", "an xor codec needs a hex key")
                key.length % 2 != 0 -> problems += PluginProblem.of("xorKey", "a hex key needs an even number of digits")
                !key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                    problems += PluginProblem.of("xorKey", "a hex key only uses 0-9 and a-f")
            }
        }
        document.checksum?.let { problems += validateChecksum(it, document) }

        val seen = mutableMapOf<String, String>()
        document.fields.forEachIndexed { index, field ->
            val where = "fields[$index]"
            val offset = field.offset
            when {
                offset == null -> Unit // already reported while reading
                offset < 0 -> problems += PluginProblem.of("$where.offset", "a byte offset cannot be negative")
            }
            if (field.isArray && field.stride != null && field.stride <= 0) {
                problems += PluginProblem.of("$where.stride", "a stride has to be positive")
            }
            field.pointer?.let { pointer ->
                if (pointer < 0) problems += PluginProblem.of("$where.pointer", "a byte offset cannot be negative")
            }
            if (field.type == PluginFieldType.STR && field.length != null && field.length <= 0) {
                problems += PluginProblem.of("$where.length", "a str length has to be positive")
            }
            // Two mappings writing the same bytes is the one mistake that corrupts a file quietly.
            val span = field.offset?.let { start -> field.type.sizeBytes?.let { start to it } }
            if (span != null && !field.isArray) {
                val previous = seen[field.path]
                if (previous != null) {
                    problems += PluginProblem.of(where, "the same path is mapped twice (also $previous)")
                }
                seen[field.path] = where
            }
        }

        // Overlapping scalars: the second one to be written would win, which is never what the
        // author meant. Only neighbouring mappings can collide, so one sorted pass is enough.
        val scalars = document.fields
            .filterNot { it.isArray }
            .mapNotNull { field -> field.offset?.let { start -> field.type.sizeBytes?.let { Triple(field, start, it) } } }
            .sortedBy { it.second }
        scalars.zipWithNext().forEach { (left, right) ->
            val (a, aStart, aSize) = left
            val (b, bStart, _) = right
            if (aStart + aSize > bStart) {
                problems += PluginProblem.of(
                    "fields",
                    "${b.path} at $bStart overlaps ${a.path} at $aStart — two mappings onto the same bytes",
                )
            }
        }

        return problems
    }

    private fun validateChecksum(checksum: PluginChecksum, document: PluginDocument): List<PluginProblem> {
        val problems = mutableListOf<PluginProblem>()
        val strategy = Checksums.byId(checksum.strategy)
        if (strategy == null) {
            val known = Checksums.all.joinToString(", ") { it.id }
            problems += PluginProblem.of("checksum.strategy", "unknown strategy \"${checksum.strategy}\" — one of: $known")
            return problems
        }
        val start = checksum.rangeStart
        if (start < 0) problems += PluginProblem.of("checksum.range", "the range cannot start before 0")
        checksum.rangeLength?.let { length ->
            if (length <= 0) problems += PluginProblem.of("checksum.range", "a range length is positive, or -1 for \"to the end\"")
        }
        val offset = checksum.storedAtOffset
        when {
            offset == null -> problems += PluginProblem.of("checksum.storedAt", "a checksum needs `[offset, width]`")
            offset < 0 -> problems += PluginProblem.of("checksum.storedAt", "the digest cannot be stored before 0")
        }
        if (checksum.storedWidth !in 1..strategy.widthBytes) {
            problems += PluginProblem.of(
                "checksum.storedAt",
                "a ${strategy.id} digest is ${strategy.widthBytes} bytes; storing ${checksum.storedWidth} is not one of them",
            )
        }
        if (checksum.storedAsHexText && document.parser == PluginParserKind.BINARY) {
            problems += PluginProblem.of(
                "checksum.storedAsHexText",
                "hex text digests are for text formats; a binary field would need a str mapping",
            )
        }
        return problems
    }
}
