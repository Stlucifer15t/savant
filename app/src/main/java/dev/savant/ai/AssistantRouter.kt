package dev.savant.ai

import dev.savant.ai.net.HttpTransport
import dev.savant.ai.providers.HttpLlmClient
import dev.savant.ai.providers.ProviderAssistant
import dev.savant.data.settings.SavantSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Decides what answers: the on-device parser, or the profile the user activated.
 *
 * The chat depends on one [AssistantEngine] and never learns which one it is talking to, which is
 * what makes swapping in a provider a settings change rather than a second code path. A profile that
 * cannot work — no model, no key — produces a [AssistantReply.Failure] naming the fix, not a
 * half-sent request.
 *
 * Pure: profiles, keys and settings arrive as functions and flows, so the whole decision table is
 * unit tested without a device, a key or a network.
 */
class AssistantRouter(
    private val executor: ToolExecutor,
    private val transport: HttpTransport,
    private val profiles: StateFlow<List<ProviderProfile>>,
    private val keyOf: suspend (ProviderProfile) -> String?,
    private val settings: () -> SavantSettings,
    private val log: NetworkLog,
) : AssistantEngine {

    override val id: String get() = activeProfile()?.let { "provider:${it.id}" } ?: "local"

    override val label: String
        get() = activeProfile()?.let { ProviderProfiles.label(it) } ?: "on device"

    override val isRemote: Boolean get() = activeProfile() != null

    private var localEngine: AssistantEngine? = null

    /** One engine per profile, so the "answering with …" line is said once and not every turn. */
    private class Candidate(val profile: ProviderProfile, val key: String?, val engine: AssistantEngine)

    private var candidate: Candidate? = null

    override suspend fun respond(input: AssistantInput): AssistantReply {
        val profile = activeProfile()
            ?: return localEngine().respond(input)

        ProviderProfiles.problem(profile)?.let { problem ->
            return AssistantReply.Failure(
                listOf("the active provider profile is incomplete: $problem", "fix it in settings → ai provider, or switch back to on device"),
            )
        }
        val key = keyOf(profile)
        if (key.isNullOrBlank() && ProviderProfiles.needsKey(profile)) {
            return AssistantReply.Failure(
                listOf(
                    "no api key stored for ${profile.name}",
                    "add one in settings → ai provider → ${profile.name}",
                ),
            )
        }
        return engineFor(profile, key).respond(input)
    }

    private fun engineFor(profile: ProviderProfile, key: String?): AssistantEngine {
        val cached = candidate
        if (cached != null && cached.profile == profile && cached.key == key) return cached.engine
        val client = HttpLlmClient(
            profile = profile,
            transport = transport,
            apiKey = { key },
            log = log,
        )
        val engine = ProviderAssistant(client, executor, profile, settings = settings)
        candidate = Candidate(profile, key, engine)
        return engine
    }

    private fun localEngine(): AssistantEngine =
        localEngine ?: LocalAssistant(executor).also { localEngine = it }

    private fun activeProfile(): ProviderProfile? = profiles.value.firstOrNull { it.active }

    /** The engine label the chat header shows; the local one when nothing is activated. */
    fun labelFor(profile: ProviderProfile?): String = profile?.let { ProviderProfiles.label(it) } ?: "on device"
}
