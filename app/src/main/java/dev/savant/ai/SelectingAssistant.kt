package dev.savant.ai

import dev.savant.data.ai.NetworkLog
import dev.savant.data.settings.ProfileStore
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import dev.savant.ai.provider.HttpTransport
import dev.savant.ai.provider.ProfileSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The engine the rest of the app talks to: on device by default, a provider when one is configured.
 *
 * Nothing else in savant knows which it is. The chat asks [AssistantEngine] and gets either the
 * deterministic command parser or a tool loop against a remote model, and both answer with the same
 * three shapes — text, a proposal, or a failure. That is what keeps "no LLM writes bytes" a property
 * of the architecture rather than a promise about one code path: both engines reach the file through
 * the same [ToolExecutor], and both can only propose edits.
 *
 * A profile that is missing its model or its key does not silently fall back to the local engine. It
 * says what is missing, because a user who configured a provider and then gets on-device answers has
 * no way to tell the difference.
 */
@Singleton
class SelectingAssistant @Inject constructor(
    private val local: LocalAssistant,
    private val executor: ToolExecutor,
    private val profiles: ProfileStore,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val transport: HttpTransport,
    private val networkLog: NetworkLog,
) : AssistantEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var active: ProviderProfile? = null

    @Volatile
    private var prefs: SavantSettings = SavantSettings()

    init {
        scope.launch {
            combine(profiles.profiles, settings.settings) { set: ProfileSet, current: SavantSettings -> set to current }
                .collect { (set, current) ->
                    active = set.active
                    prefs = current
                }
        }
    }

    override val id: String get() = active?.let { "remote:${it.provider.id}" } ?: local.id

    override val label: String
        get() = active?.let { profile -> "${profile.name} · ${profile.model}" } ?: local.label

    override val isRemote: Boolean get() = active != null

    override fun tools(): List<ToolSpec> = ToolCatalog.ALL

    override suspend fun respond(input: AssistantInput): AssistantReply {
        val set = profiles.current()
        val profile = set.active ?: return local.respond(input)

        val key = secrets.get(SecretStore.profileKey(profile.id)) ?: secrets.get(SecretStore.PROVIDER_KEY)
        val missing = profile.missingKey(!key.isNullOrBlank())
        if (missing != null) {
            return AssistantReply.Failure(
                listOf(
                    "the ${profile.name.ifBlank { profile.provider.label }} profile is not ready",
                    missing,
                    "fix it in settings → providers, or switch back to on device",
                ),
            )
        }

        val current = settings.settings.first()
        val client = RemoteLlmClient(
            profile = profile,
            apiKey = { key },
            transport = transport,
            onEvent = { event -> if (current.logNetworkRequests) networkLog.record(event) },
        )
        val policy = PromptPolicy(sendFieldValues = current.sendFieldValues)
        return RemoteAssistant(client, executor, policy).respond(input)
    }
}
