package dev.savant.data.history

import dev.savant.domain.chat.ChatEntry
import dev.savant.domain.chat.ChatRole
import dev.savant.domain.chat.DiffEntry
import dev.savant.domain.chat.DiffStatus
import dev.savant.domain.chat.ExportEntry
import dev.savant.domain.chat.FieldsEntry
import dev.savant.domain.chat.FileEntry
import dev.savant.domain.chat.NoticeEntry
import dev.savant.domain.chat.ParseEntry
import dev.savant.domain.chat.TextEntry
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.EditSource
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.VerificationMismatch
import dev.savant.domain.parser.VerificationReport
import dev.savant.domain.session.ExportArtifact
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.jsonOf
import dev.savant.parsers.json.jsonToValue
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/** A chat entry as it is stored: plain text to search, json for the rest. Pure jvm, no room. */
data class StoredEntry(
    val id: String,
    val sessionId: String,
    val kind: String,
    val role: String?,
    val body: String,
    val payload: String,
    val isError: Boolean,
    val createdAt: Long,
    val position: Int,
)

object EntryKinds {
    const val TEXT = "text"
    const val FILE = "file"
    const val PARSE = "parse"
    const val FIELDS = "fields"
    const val DIFF = "diff"
    const val EXPORT = "export"
    const val NOTICE = "notice"
}

/**
 * Chat entry ⇄ stored row.
 *
 * Two rules shape the encoding. Everything a person would search for goes into `body` as plain
 * text — including the paths and both sides of every diff. Everything else goes into a compact
 * json payload, and nothing huge is stored at all: field lists and rebuilt bytes are not history,
 * they belong to the file, which is why a restored field card says so instead of pretending.
 *
 * Decoding never throws. A payload from an older build, or a corrupt one, yields null and the
 * entry is skipped rather than taking the transcript down.
 */
object EntryCodec {

    fun encode(entry: ChatEntry, sessionId: String, position: Int): StoredEntry {
        val (kind, role, body, payload, isError) = when (entry) {
            is TextEntry -> Quintet(
                EntryKinds.TEXT,
                entry.role.name,
                entry.lines.joinToString("\n"),
                JsonPayload.write(obj("lines" to arr(entry.lines.map { str(it) }))),
                entry.isError,
            )

            is FileEntry -> Quintet(
                EntryKinds.FILE,
                null,
                "${entry.file.name} ${entry.formatBadge}",
                JsonPayload.write(
                    obj(
                        "uri" to str(entry.file.uri),
                        "name" to str(entry.file.name),
                        "size" to num(entry.file.sizeBytes),
                        "mime" to str(entry.file.mimeType),
                        "importedAt" to num(entry.file.importedAt),
                        "hex" to str(entry.file.hexPreview),
                        "origin" to str(entry.file.origin.name),
                        "badge" to str(entry.formatBadge),
                        "score" to num(entry.confidence?.score?.toDouble()),
                        "reason" to str(entry.confidence?.reason),
                    ),
                ),
                false,
            )

            is ParseEntry -> Quintet(
                EntryKinds.PARSE,
                null,
                entry.log.joinToString("\n") { it.text },
                JsonPayload.write(
                    obj(
                        "done" to bool(entry.done),
                        "lines" to arr(entry.log.map { str(it.text) }),
                    ),
                ),
                false,
            )

            is FieldsEntry -> Quintet(
                EntryKinds.FIELDS,
                null,
                entry.headline,
                JsonPayload.write(
                    obj(
                        "headline" to str(entry.headline),
                        "total" to num(entry.totalCount),
                        "editable" to num(entry.editableCount),
                        "collapsed" to arr(entry.collapsed.map { str(it) }),
                        "summary" to arr(
                            entry.groups.map { group -> str("${group.category.label} ${group.count}") },
                        ),
                        "groups" to arr(
                            entry.groups.map { group ->
                                obj(
                                    "category" to str(group.category.name),
                                    "count" to num(group.count),
                                    "editable" to num(group.nodes.count { it.editable }),
                                )
                            },
                        ),
                    ),
                ),
                false,
            )

            is DiffEntry -> Quintet(
                EntryKinds.DIFF,
                null,
                entry.edits.joinToString("\n") { "${it.path}: ${it.oldText} -> ${it.newText}" },
                JsonPayload.write(
                    obj(
                        "status" to str(entry.status.name),
                        "note" to str(entry.note),
                        "appliedCount" to num(entry.appliedCount),
                        "dropped" to arr(entry.dropped.map { str(it) }),
                        "edits" to arr(entry.edits.map { edit -> encodeEdit(edit) }),
                    ),
                ),
                false,
            )

            is ExportEntry -> Quintet(
                EntryKinds.EXPORT,
                null,
                "${entry.artifact.fileName} ${entry.artifact.report.summary}",
                JsonPayload.write(
                    obj(
                        "fileName" to str(entry.artifact.fileName),
                        "size" to num(entry.artifact.sizeBytes),
                        "builtAt" to num(entry.artifact.builtAt),
                        "ok" to bool(entry.artifact.report.ok),
                        "format" to str(entry.artifact.report.format.name),
                        "checked" to num(entry.artifact.report.checkedEdits),
                        "warnings" to arr(entry.artifact.report.warnings.map { str(it) }),
                        "reparsedReadOnly" to bool(entry.artifact.report.reparsedReadOnly),
                        "shared" to bool(entry.shared),
                        "savedTo" to str(entry.savedTo),
                    ),
                ),
                false,
            )

            is NoticeEntry -> Quintet(
                EntryKinds.NOTICE,
                null,
                (listOf(entry.title) + entry.lines).joinToString("\n"),
                JsonPayload.write(
                    obj(
                        "title" to str(entry.title),
                        "lines" to arr(entry.lines.map { str(it) }),
                    ),
                ),
                true,
            )
        }

        return StoredEntry(
            id = entry.id,
            sessionId = sessionId,
            kind = kind,
            role = role,
            body = body,
            payload = payload,
            isError = isError,
            createdAt = entry.atMillis,
            position = position,
        )
    }

    fun decode(row: StoredEntry): ChatEntry? = runCatching {
        val payload = JsonPayload.readOrEmpty(row.payload) as? Json.Obj ?: Json.Obj()
        when (row.kind) {
            EntryKinds.TEXT -> TextEntry(
                id = row.id,
                role = ChatRole.entries.firstOrNull { it.name == row.role } ?: ChatRole.ASSISTANT,
                lines = payload.strings("lines").ifEmpty { row.body.split("\n") },
                atMillis = row.createdAt,
                isError = row.isError,
            )

            EntryKinds.FILE -> FileEntry(
                id = row.id,
                file = ImportedFile(
                    uri = payload.string("uri") ?: "",
                    name = payload.string("name") ?: "save file",
                    sizeBytes = payload.long("size") ?: 0L,
                    mimeType = payload.string("mime"),
                    importedAt = payload.long("importedAt") ?: row.createdAt,
                    hexPreview = payload.string("hex") ?: "-- -- -- --",
                    origin = ImportedFile.Origin.entries.firstOrNull { it.name == payload.string("origin") }
                        ?: ImportedFile.Origin.PICKER,
                ),
                formatBadge = payload.string("badge") ?: "?",
                confidence = payload.double("score")?.let { score ->
                    Confidence(score.toFloat(), payload.string("reason") ?: "")
                },
                atMillis = row.createdAt,
            )

            EntryKinds.PARSE -> ParseEntry(
                id = row.id,
                log = payload.strings("lines").map { ParseLogLine(it) },
                done = payload.bool("done") ?: true,
                atMillis = row.createdAt,
            )

            // The field list itself is not stored: values belong to the file, not to the transcript.
            EntryKinds.FIELDS -> FieldsEntry(
                id = row.id,
                headline = payload.string("headline") ?: row.body,
                groups = emptyList(),
                totalCount = payload.int("total") ?: 0,
                editableCount = payload.int("editable") ?: 0,
                collapsed = payload.strings("collapsed").toSet(),
                summaryLines = payload.strings("summary"),
                restored = true,
                atMillis = row.createdAt,
            )

            EntryKinds.DIFF -> DiffEntry(
                id = row.id,
                edits = payload.list("edits").mapNotNull { decodeEdit(it) },
                status = DiffStatus.entries.firstOrNull { it.name == payload.string("status") } ?: DiffStatus.APPLIED,
                note = payload.string("note"),
                dropped = payload.strings("dropped").toSet(),
                appliedCount = payload.int("appliedCount") ?: 0,
                atMillis = row.createdAt,
            )

            EntryKinds.EXPORT -> ExportEntry(
                id = row.id,
                artifact = ExportArtifact(
                    fileName = payload.string("fileName") ?: "save",
                    bytes = ByteArray(0),
                    report = VerificationReport(
                        ok = payload.bool("ok") ?: false,
                        format = SaveFormat.entries.firstOrNull { it.name == payload.string("format") } ?: SaveFormat.JSON,
                        sizeBytes = (payload.long("size") ?: 0L).toInt(),
                        checkedEdits = payload.int("checked") ?: 0,
                        mismatches = emptyList<VerificationMismatch>(),
                        warnings = payload.strings("warnings"),
                        reparsedReadOnly = payload.bool("reparsedReadOnly") ?: false,
                    ),
                    builtAt = payload.long("builtAt") ?: row.createdAt,
                ),
                shared = payload.bool("shared") ?: false,
                savedTo = payload.string("savedTo"),
                restored = true,
                atMillis = row.createdAt,
            )

            EntryKinds.NOTICE -> NoticeEntry(
                id = row.id,
                title = payload.string("title") ?: "notice",
                lines = payload.strings("lines"),
                atMillis = row.createdAt,
            )

            else -> null
        }
    }.getOrNull()

    private fun encodeEdit(edit: FieldEdit): Json = obj(
        "path" to str(edit.path),
        "name" to str(edit.displayName),
        "type" to str(edit.type.tag),
        "source" to str(edit.source.name),
        "old" to jsonOf(edit.oldValue),
        "new" to jsonOf(edit.newValue),
        "min" to num(edit.min),
        "max" to num(edit.max),
    )

    private fun decodeEdit(json: Json): FieldEdit? {
        val payload = json as? Json.Obj ?: return null
        val type = FieldType.fromTag(payload.string("type")) ?: FieldType.STRING
        return FieldEdit(
            path = payload.string("path") ?: return null,
            displayName = payload.string("name") ?: "",
            type = type,
            oldValue = jsonToValue(payload["old"], type),
            newValue = jsonToValue(payload["new"], type),
            source = EditSource.entries.firstOrNull { it.name == payload.string("source") } ?: EditSource.USER_INLINE,
            min = payload.double("min"),
            max = payload.double("max"),
        )
    }

    /** Five values out of one `when`, without a data class per entry kind. */
    private data class Quintet(
        val kind: String,
        val role: String?,
        val body: String,
        val payload: String,
        val isError: Boolean,
    )
}
