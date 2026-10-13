package dev.savant.ai

import dev.savant.domain.model.FieldEdit

/**
 * The provider backed engine: a tool loop over an [LlmClient].
 *
 * The loop is the whole trick, and it is deliberately short. The model gets the system prompt, the
 * conversation so far and the eight tools; when it answers with tool calls, each one runs through the
 * same [ToolExecutor] the on device parser uses, and the result goes back as a tool message. Value
 * changing tools return *proposed* edits, which are collected and handed to the chat as a
 * [AssistantReply.Proposal] — the diff preview stays the only gate between a model and a file.
 *
 * [maxRounds] bounds the loop. A model that keeps calling tools without answering is stopped and
 * told so, rather than being allowed to spend the user's tokens in a circle.
 */
class RemoteAssistant(
    private val client: LlmClient,
    private val executor: ToolExecutor,
    private val policy: PromptPolicy = PromptPolicy(),
    private val maxRounds: Int = MAX_ROUNDS,
) : AssistantEngine {

    override val id: String get() = "remote:${client.providerId}"

    override val label: String get() = client.label

    override val isRemote: Boolean get() = true

    override suspend fun respond(input: AssistantInput): AssistantReply {
        val messages = mutableListOf<LlmMessage>()
        messages += input.history
        messages += LlmMessage(role = LlmMessage.Role.USER, text = input.text)

        val system = SystemPrompt.forSession(input.session, policy)
        val edits = mutableListOf<FieldEdit>()
        val toolLines = mutableListOf<String>()
        var requestExport = false
        var answer: String? = null
        var rounds = 0

        while (rounds < maxRounds) {
            val response = runCatching { client.complete(LlmRequest(messages = messages, tools = tools(), systemPrompt = system)) }
                .getOrElse { error ->
                    return AssistantReply.Failure(
                        listOf("the provider call failed", (error.message ?: error.toString()).take(300)),
                    )
                }

            when (response.stopReason) {
                StopReason.ERROR -> return AssistantReply.Failure(failureLines(response.text))
                StopReason.REFUSAL -> return AssistantReply.Failure(
                    listOf("the provider declined this request", response.text?.take(300) ?: "no reason given").filter { it.isNotBlank() },
                )

                else -> Unit
            }

            if (!response.text.isNullOrBlank()) answer = response.text
            if (!response.wantsToolCall) break

            messages += LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = response.text ?: "",
                toolCalls = response.toolCalls,
            )
            for (call in response.toolCalls) {
                val outcome = executor.execute(call, input.session)
                edits += outcome.edits
                requestExport = requestExport || outcome.requestExport
                if (outcome.lines.isNotEmpty()) toolLines += outcome.lines
                messages += LlmMessage(
                    role = LlmMessage.Role.TOOL,
                    text = outcome.text,
                    toolCallId = call.id,
                    toolName = call.name,
                )
            }
            rounds++
        }

        if (rounds >= maxRounds) toolLines += "stopped after $maxRounds tool rounds"

        val lines = buildList {
            answer?.split('\n')?.filter { it.isNotBlank() }?.let { addAll(it) }
            addAll(toolLines)
        }

        return when {
            edits.isNotEmpty() -> AssistantReply.Proposal(
                lines = lines.ifEmpty { listOf("proposed ${edits.size} edit(s)") },
                edits = edits,
                requestExport = requestExport,
            )

            lines.isEmpty() -> AssistantReply.Failure(listOf("the provider returned nothing usable"))
            else -> AssistantReply.Text(lines)
        }
    }

    private fun failureLines(text: String?): List<String> =
        listOf("the provider returned an error", (text ?: "no detail").take(300)).filter { it.isNotBlank() }

    companion object {
        const val MAX_ROUNDS = 4
    }
}
