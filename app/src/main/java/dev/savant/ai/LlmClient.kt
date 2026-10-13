package dev.savant.ai

/**
 * Provider agnostic LLM contract.
 *
 * One implementation per provider (Claude, OpenAI, OpenRouter, Gemini, any OpenAI compatible
 * endpoint). Everything above this interface — tools, trimming, the diff rule — is shared, so
 * switching provider never changes how an edit reaches a file.
 *
 * Wired up in build step 6; the local command parser implements [AssistantEngine] until then.
 */
interface LlmClient {

    val providerId: String

    val label: String

    /** One round trip, no partial answers. */
    suspend fun complete(request: LlmRequest): LlmResponse

    /** Text deltas only: what a caller that just wants to show progress subscribes to. */
    suspend fun stream(request: LlmRequest): kotlinx.coroutines.flow.Flow<LlmChunk> =
        kotlinx.coroutines.flow.flow { emit(LlmChunk(complete(request).text ?: "", emptyList(), true)) }

    /**
     * The same round trip with its progress: deltas first, then one [LlmEvent.Completed] carrying
     * the full answer. The default keeps a non streaming provider a two line implementation.
     */
    fun events(request: LlmRequest): kotlinx.coroutines.flow.Flow<LlmEvent> =
        kotlinx.coroutines.flow.flow {
            val response = complete(request)
            response.text?.takeIf { it.isNotEmpty() }?.let { emit(LlmEvent.Delta(LlmChunk(it, done = true))) }
            emit(LlmEvent.Completed(response))
        }

    /**
     * Tiny connectivity check used by the "test connection" button.
     * Never carries save data, field names or values — one token in, one token out.
     */
    suspend fun ping(): ProviderPing
}

data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val systemPrompt: String? = null,
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 1024,
    /**
     * What the caller put in this request, for the request log and for the privacy copy.
     * It is a note *about* the request, never sent to a provider.
     */
    val payloadNote: PayloadNote = PayloadNote(),
)

/**
 * The one line that answers "what did savant just send". Set by whoever built the payload, read by
 * the client when it writes the request log, and printed in the chat under a remote answer.
 */
data class PayloadNote(
    val fields: Int = 0,
    val values: Boolean = false,
    /** Set when the file was over the trim limit or the user turned values off. */
    val withheldReason: String? = null,
) {
    val withheld: Boolean get() = withheldReason != null
}

data class LlmMessage(
    val role: Role,
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on [Role.TOOL] messages: the id of the call this answers. */
    val toolCallId: String? = null,
    val toolName: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

data class LlmResponse(
    val text: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val stopReason: StopReason = StopReason.STOP,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val model: String? = null,
) {
    val wantsToolCall: Boolean get() = toolCalls.isNotEmpty()
}

data class LlmChunk(val textDelta: String, val toolCalls: List<ToolCall> = emptyList(), val done: Boolean = false)

/**
 * One round trip, as it happens.
 *
 * An engine wants both halves of a streamed answer: the deltas, so the chat can show the reply
 * arriving, and the finished [LlmResponse], so tool calls can run. Providers that cannot stream
 * emit a single [Completed].
 */
sealed interface LlmEvent {
    data class Delta(val chunk: LlmChunk) : LlmEvent
    data class Completed(val response: LlmResponse) : LlmEvent
}

enum class StopReason { STOP, TOOL_USE, LENGTH, REFUSAL, ERROR }

/**
 * Result of "test connection". [detail] is the exact error text from the provider — shown
 * verbatim, with any key material redacted to its last four characters.
 */
data class ProviderPing(
    val ok: Boolean,
    val detail: String,
    val latencyMillis: Long,
    val model: String? = null,
    val endpoint: String? = null,
)

/** What a provider is configured with. Keys live in EncryptedSharedPreferences, never here. */
data class ProviderProfile(
    val id: String,
    val name: String,
    val provider: ProviderKind,
    val baseUrl: String,
    val model: String,
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 1024,
    val streaming: Boolean = true,
    val systemPromptOverride: String? = null,
    val active: Boolean = false,
)

enum class ProviderKind(val id: String, val label: String, val defaultBaseUrl: String, val keyHint: String) {
    CLAUDE("claude", "claude", "https://api.anthropic.com/v1", "sk-ant-..."),
    OPENAI("openai", "openai", "https://api.openai.com/v1", "sk-..."),
    OPENROUTER("openrouter", "openrouter", "https://openrouter.ai/api/v1", "sk-or-..."),
    GEMINI("gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "AIza..."),
    CUSTOM("custom", "custom (openai compatible)", "", "any"),
    ;

    companion object {
        fun fromId(id: String?): ProviderKind = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}
