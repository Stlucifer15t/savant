package dev.savant.ai

import dev.savant.ai.provider.HttpTransport
import dev.savant.ai.provider.NetworkEvent
import dev.savant.ai.provider.ProfileSet
import dev.savant.ai.web.WebConfig
import dev.savant.ai.web.WebPolicy
import dev.savant.ai.web.WebToolExecutor
import dev.savant.ai.web.WebTools
import dev.savant.ai.web.SearchProvider
import dev.savant.data.ai.NetworkLog
import dev.savant.data.mcp.McpServerStore
import dev.savant.data.settings.ProfileStore
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import dev.savant.mcp.McpProtocol
import dev.savant.mcp.McpSet
import dev.savant.mcp.McpToolRouter
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
 * shapes — text, a proposal, a failure, or a request for permission. That is what keeps "no LLM writes
 * bytes" a property of the architecture rather than a promise about one code path: both engines reach
 * the file through the same [ToolExecutor], and both can only propose edits.
 *
 * This is also where the extra tools are decided. The eight save tools are always there; `web_search`
 * and `fetch_url` are added only when the user switched them on, and a connector's tools only when the
 * connector is enabled — from a cache, so answering "what can the model call" never touches the
 * network.
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
    private val connectors: McpServerStore,
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

    @Volatile
    private var connectorSet: McpSet = McpSet()

    init {
        scope.launch {
            combine(profiles.profiles, settings.settings, connectors.set) { set, current, mcp ->
                Triple(set, current, mcp)
            }.collect { (set, current, mcp) ->
                active = set.active
                prefs = current
                connectorSet = mcp
            }
        }
    }

    override val id: String get() = active?.let { "remote:${it.provider.id}" } ?: local.id

    override val label: String
        get() = active?.let { profile -> "${profile.name} · ${profile.model}" } ?: local.label

    override val isRemote: Boolean get() = active != null

    override fun tools(): List<ToolSpec> = ToolCatalog.ALL + webSpecs() + connectorSpecs()

    private fun webSpecs(): List<ToolSpec> = if (prefs.webToolsEnabled) WebTools.ALL else emptyList()

    /** From the cache the last handshake left behind: no round trip to build a prompt. */
    private fun connectorSpecs(): List<ToolSpec> = connectorSet.enabled.flatMap { server ->
        (connectorSet.tools[server.id] ?: emptyList()).map { tool -> McpProtocol.toolSpec(tool, server.name) }
    }

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
        val record: (NetworkEvent) -> Unit = { event ->
            if (current.logNetworkRequests) networkLog.record(event)
        }

        val client = RemoteLlmClient(profile = profile, apiKey = { key }, transport = transport, onEvent = record)

        val web = if (current.webToolsEnabled) {
            WebToolExecutor(transport = transport, config = { webConfig(current) }, onEvent = record)
        } else {
            null
        }

        val mcp = connectors.current()
        val router = if (mcp.enabled.isEmpty()) {
            null
        } else {
            McpToolRouter(
                servers = mcp.enabled,
                toolsByServer = mcp.tools,
                apiKey = { server -> secrets.get(SecretStore.MCP_PREFIX + server.id) },
                transport = transport,
                onEvent = record,
            )
        }

        return RemoteAssistant(
            client = client,
            executor = executor,
            policy = PromptPolicy(sendFieldValues = current.sendFieldValues),
            web = web,
            connectors = router,
        ).respond(input)
    }

    private fun webConfig(current: SavantSettings) = WebConfig(
        searchEnabled = current.searchApiEnabled,
        searchProvider = SearchProvider.fromId(current.searchProvider),
        searchKey = secrets.get(SecretStore.SEARCH_KEY),
        allowList = WebPolicy.parseAllowList(current.allowListedDomains),
        confirmEveryCall = current.confirmWebCalls,
    )
}
