package dev.savant.ai.providers

import dev.savant.ai.AiPayload
import dev.savant.ai.AssistantEngine
import dev.savant.ai.AssistantInput
import dev.savant.ai.AssistantReply
import dev.savant.ai.LlmClient
import dev.savant.ai.LlmEvent
import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmResponse
import dev.savant.ai.LlmRequest
import dev.savant.ai.PayloadNote
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCatalog
import dev.savant.ai.ToolExecutor
import dev.savant.ai.net.TransportException
import dev.savant.data.settings.SavantSettings
import dev.savant.domain.model.FieldEdit

/**
 * A provider backed assistant.
 *
 * The model is given eight tools and no file. It reads through `list_fields` / `search_fields` /
 * `get_field`, it *proposes* through `set_field` / `set_many` / `undo` — and every proposal is
 * returned as an [FieldEdit] for the diff preview, exactly like a tap on a value. There is no code
 * path from a model to a byte of the save file, and this class does not add one.
 *
 * The loop is capped at [maxRounds]: a model that keeps calling tools without answering gets one
 * more round than it needs and then a sentence explaining why savant stopped.
 */
class ProviderAssistant(
    private val client: LlmClient,
    private val executor: ToolExecutor,
    private val profile: ProviderProfile,
    private val settings: () -> SavantSettings = { SavantSettings() },
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) : AssistantEngine {

    override val id: String = "provider:${profile.id}"

    override val label: String = HttpLlmClient.displayLabel(profile)

    override val isRemote: Boolean = true

    /** Set once per engine instance: the privacy line is worth reading once, not every turn. */
    private var announced = false

    override suspend fun respond(input: AssistantInput): AssistantReply {
        val session = input.session
            ?: return AssistantReply.Failure(listOf("no save file is open — attach one first"))
        if (session.readOnly) {
            return AssistantReply.Failure(
                listOf(
                    session.tree.readOnlyReason ?: "this file is read only",
                    "values are visible, but nothing can be proposed until a parser can rebuild this format",
                ),
            )
        }

        val summary = AiPayload.summaryFor(
            tree = session.tree,
            fileSizeBytes = session.file.sizeBytes,
            sendValues = settings().sendFieldValues,
        )
        val note = PayloadNote(
            fields = summary.fieldsIncluded,
            values = summary.valuesIncluded,
            withheldReason = summary.withheldReason,
        )

        val conversation = buildList {
            addAll(trimHistory(input.history))
            add(LlmMessage(role = LlmMessage.Role.USER, text = input.text))
        }.toMutableList()

        val edits = mutableListOf<FieldEdit>()
        val said = mutableListOf<String>()
        var wantsExport = false
        var rounds = 0

        while (true) {
            val request = LlmRequest(
                messages = conversation,
                tools = ToolCatalog.ALL,
                systemPrompt = systemPrompt(summary),
                temperature = profile.temperature,
                maxOutputTokens = profile.maxOutputTokens,
                payloadNote = note,
            )
            val response = try {
                roundTrip(request, input)
            } catch (failure: TransportException) {
                val lines = listOf(failure.message ?: "the provider request did not complete") + hintFor(failure)
                // A later round can fail after the model already proposed something: keep the
                // proposals and say so, rather than throwing away work the user can still confirm.
                return if (edits.isEmpty()) {
                    AssistantReply.Failure(lines)
                } else {
                    AssistantReply.Proposal(
                        listOf("the conversation stopped, but ${edits.size} proposed change(s) are below") + lines,
                        edits,
                        wantsExport,
                    )
                }
            }

            response.text?.trim()?.takeIf { it.isNotEmpty() }?.let { said += it }

            if (response.toolCalls.isEmpty()) {
                val lines = buildList {
                    addAll(said)
                    if (response.stopReason == StopReason.REFUSAL) {
                        add("the model declined to answer this one")
                    }
                    if (response.stopReason == StopReason.LENGTH) {
                        add("the answer hit the output token limit (${profile.maxOutputTokens})")
                    }
                    addAll(privacyLines(summary))
                }
                return when {
                    edits.isNotEmpty() -> AssistantReply.Proposal(lines, edits, wantsExport)
                    wantsExport -> AssistantReply.Proposal(lines, emptyList(), true)
                    lines.isEmpty() -> AssistantReply.Text(listOf("the provider answered with nothing"))
                    else -> AssistantReply.Text(lines)
                }
            }

            if (rounds >= maxRounds) {
                said += "stopped after $rounds tool rounds — the model kept asking for tools"
                return AssistantReply.Proposal(
                    said + privacyLines(summary),
                    edits,
                    wantsExport,
                )
            }
            rounds++

            conversation += LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = response.text.orEmpty(),
                toolCalls = response.toolCalls,
            )
            for (call in response.toolCalls) {
                val outcome = executor.execute(call, session)
                conversation += LlmMessage(
                    role = LlmMessage.Role.TOOL,
                    text = outcome.text,
                    toolCallId = call.id,
                    toolName = call.name,
                )
                edits += outcome.edits
                wantsExport = wantsExport || outcome.requestExport
            }
        }
    }

    /**
     * One round trip: reports the reply as it arrives and returns the finished answer.
     *
     * Providers that cannot stream still satisfy this — they emit the whole answer as one delta —
     * so the loop above has a single shape whatever is answering.
     */
    private suspend fun roundTrip(request: LlmRequest, input: AssistantInput): LlmResponse {
        var completed: LlmResponse? = null
        val streamed = StringBuilder()
        client.events(request).collect { event ->
            when (event) {
                is LlmEvent.Delta -> {
                    streamed.append(event.chunk.textDelta)
                    input.onPartial(streamed.toString())
                }

                is LlmEvent.Completed -> completed = event.response
            }
        }
        return completed ?: throw TransportException("the stream ended without an answer", retryable = true)
    }

    private fun hintFor(failure: TransportException): List<String> = when {
        failure.retryable -> listOf("that looks transient — sending the same message again usually works")
        failure.message?.contains("api key") == true -> listOf("add the key in settings → ai provider")
        else -> emptyList()
    }

    private fun privacyLines(summary: AiPayload.Summary): List<String> = buildList {
        if (!announced) {
            announced = true
            add("answering with $label — a field listing goes to ${host()}, the save file itself never leaves the device")
        }
        if (summary.withheldReason != null) {
            add("sent field names and types only: ${summary.withheldReason}")
        }
    }

    private fun host(): String = profile.baseUrl
        .ifBlank { profile.provider.defaultBaseUrl }
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .ifBlank { "the provider" }

    /** The last few turns of the transcript, so a follow-up lands in context. */
    private fun trimHistory(history: List<LlmMessage>): List<LlmMessage> {
        if (history.isEmpty()) return emptyList()
        return history
            .filter { it.role == LlmMessage.Role.USER || it.role == LlmMessage.Role.ASSISTANT }
            .takeLast(MAX_HISTORY)
            .map { message -> message.copy(text = message.text.take(MAX_HISTORY_CHARS)) }
    }

    private fun systemPrompt(summary: AiPayload.Summary): String = """
        you are savant, a workbench that edits game save files on a phone.

        what you can do
        - read an open file with list_fields, search_fields and get_field
        - propose values with set_field, set_many and undo
        - ask for a rebuild with export

        rules
        - you cannot write a file. every change is a proposal the user confirms in a diff, then a
          deterministic editor validates and applies it. never say a value "has been" changed.
        - only use paths that appear in the listing below. when you are not sure which field the user
          means, search first, then ask in one line.
        - answers are one or two short lowercase lines. no markdown, no emoji, no preamble.
        - if the request cannot be done with these tools, say so in one line and stop.
        - these are offline single player saves. never suggest anything that touches an online account.

        the open file
        ${summary.text}
    """.trimIndent()

    companion object {
        const val DEFAULT_MAX_ROUNDS = 6
        private const val MAX_HISTORY = 12
        private const val MAX_HISTORY_CHARS = 2000
    }
}
