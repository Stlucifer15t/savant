package dev.savant.ai

import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str
import java.util.UUID

/**
 * Provider profiles, on disk and in memory.
 *
 * A profile is *settings*: which provider, which model, how warm. The key is not here — it lives in
 * [dev.savant.data.settings.SecretStore], encrypted, and only its masked tail is ever shown. The
 * list is stored as one json document so a new field in a later version does not need a schema
 * migration, and every read is tolerant: a profile written by an older build still loads.
 */
object ProviderProfiles {

    const val VERSION = 1

    fun blank(kind: ProviderKind, id: String = newId()): ProviderProfile = ProviderProfile(
        id = id,
        name = kind.label,
        provider = kind,
        baseUrl = kind.defaultBaseUrl,
        model = defaultModel(kind),
    )

    /** A starting model per provider. It is a suggestion in a text field, not a contract. */
    fun defaultModel(kind: ProviderKind): String = when (kind) {
        ProviderKind.CLAUDE -> "claude-sonnet-4-5"
        ProviderKind.OPENAI -> "gpt-4o-mini"
        ProviderKind.OPENROUTER -> "openai/gpt-4o-mini"
        ProviderKind.GEMINI -> "gemini-2.0-flash"
        ProviderKind.CUSTOM -> ""
    }

    fun newId(): String = "p" + UUID.randomUUID().toString().replace("-", "").take(8)

    fun write(profiles: List<ProviderProfile>): String = JsonPayload.write(
        obj(
            "version" to num(VERSION),
            "profiles" to arr(profiles.map { encode(it) }),
        ),
    )

    /** Never throws: a corrupt list means no profiles, not a crash on the settings screen. */
    fun read(text: String?): List<ProviderProfile> {
        if (text.isNullOrBlank()) return emptyList()
        val root = JsonPayload.readOrEmpty(text)
        val items = root.list("profiles")
        return items.mapNotNull { item ->
            val itemObj = item as? Json.Obj ?: return@mapNotNull null
            val id = itemObj.string("id") ?: return@mapNotNull null
            ProviderProfile(
                id = id,
                name = itemObj.string("name") ?: "provider",
                provider = ProviderKind.fromId(itemObj.string("provider")),
                baseUrl = itemObj.string("baseUrl").orEmpty(),
                model = itemObj.string("model").orEmpty(),
                temperature = itemObj.double("temperature")?.toFloat() ?: 0.2f,
                maxOutputTokens = itemObj.int("maxOutputTokens") ?: 1024,
                streaming = itemObj.bool("streaming") ?: true,
                systemPromptOverride = itemObj.string("systemPromptOverride")?.takeIf { it.isNotBlank() },
                active = itemObj.bool("active") ?: false,
            )
        }
    }

    private fun encode(profile: ProviderProfile): Json = obj(
        "id" to str(profile.id),
        "name" to str(profile.name),
        "provider" to str(profile.provider.id),
        "baseUrl" to str(profile.baseUrl),
        "model" to str(profile.model),
        "temperature" to num(profile.temperature.toDouble()),
        "maxOutputTokens" to num(profile.maxOutputTokens),
        "streaming" to bool(profile.streaming),
        "systemPromptOverride" to str(profile.systemPromptOverride),
        "active" to bool(profile.active),
    )

    /** What the row shows and what the request log calls it. */
    fun label(profile: ProviderProfile): String =
        if (profile.model.isBlank()) profile.provider.label else "${profile.provider.label} · ${profile.model}"

    /** True when this profile cannot work without a key the user has not entered yet. */
    fun needsKey(profile: ProviderProfile): Boolean = profile.provider != ProviderKind.CUSTOM

    /**
     * A profile is usable when it has somewhere to go and something to ask for. OpenRouter and any
     * custom endpoint are allowed to have no base url if the provider supplies a default one.
     */
    fun problem(profile: ProviderProfile): String? = when {
        profile.baseUrl.isBlank() && profile.provider != ProviderKind.CUSTOM &&
            profile.provider.defaultBaseUrl.isBlank() -> "no base url"
        profile.provider == ProviderKind.CUSTOM && profile.baseUrl.isBlank() -> "a custom provider needs a base url"
        profile.model.isBlank() -> "no model name"
        else -> null
    }
}
