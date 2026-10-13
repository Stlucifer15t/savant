package dev.savant.ai.provider

import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/** The stored profile list plus which one is active. `activeId == null` means the on device engine. */
data class ProfileSet(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: ProviderProfile? get() = profiles.firstOrNull { it.id == activeId }

    val isRemote: Boolean get() = active != null

    fun withProfile(profile: ProviderProfile): ProfileSet = ProfileSet(
        profiles = profiles.filterNot { it.id == profile.id } + profile,
        activeId = activeId ?: profile.id,
    )

    fun withoutProfile(id: String): ProfileSet = ProfileSet(
        profiles = profiles.filterNot { it.id == id },
        activeId = if (activeId == id) null else activeId,
    )

    fun withActive(id: String?): ProfileSet = copy(activeId = id)

    /** A new profile with the provider's own defaults filled in, ready to edit. */
    companion object {
        fun draft(kind: ProviderKind, id: String = "${kind.id}-${System.currentTimeMillis()}"): ProviderProfile =
            ProviderProfile(
                id = id,
                name = kind.label,
                provider = kind,
                baseUrl = kind.defaultBaseUrl,
                model = "",
                temperature = 0.2f,
                maxOutputTokens = 1024,
            )

        /**
         * Placeholder text for the model field, per provider.
         *
         * Deliberately a hint and not a default: model ids change every few months and savant has no
         * way to know which ones a key is entitled to, so an empty field that says "no model name" is
         * more honest than a filled in one that 404s.
         */
        val MODEL_HINT: Map<ProviderKind, String> = mapOf(
            ProviderKind.CLAUDE to "a claude model id",
            ProviderKind.OPENAI to "a gpt model id",
            ProviderKind.OPENROUTER to "author/model, as openrouter lists it",
            ProviderKind.GEMINI to "a gemini model id",
            ProviderKind.CUSTOM to "whatever the endpoint calls it",
        )
    }
}

/**
 * Profiles ⇄ json, for the preferences file.
 *
 * Keys are not part of this: a profile is a shape (which provider, which model, which endpoint, how
 * hot), and the key lives in encrypted storage under the profile's id. Decoding never throws — a
 * profile written by a future build is skipped, not fatal.
 */
object ProfileCodec {

    fun encode(set: ProfileSet): String = JsonPayload.write(
        obj(
            "activeId" to str(set.activeId),
            "profiles" to arr(set.profiles.map { encodeProfile(it) }),
        ),
    )

    fun decode(text: String?): ProfileSet {
        val json = JsonPayload.readOrEmpty(text) as? Json.Obj ?: return ProfileSet()
        val profiles = json.list("profiles").mapNotNull { decodeProfile(it) }
        val activeId = json.string("activeId")?.takeIf { id -> profiles.any { it.id == id } }
        return ProfileSet(profiles = profiles, activeId = activeId)
    }

    fun encodeProfile(profile: ProviderProfile): Json.Obj = obj(
        "id" to str(profile.id),
        "name" to str(profile.name),
        "provider" to str(profile.provider.id),
        "baseUrl" to str(profile.baseUrl),
        "model" to str(profile.model),
        "temperature" to num(profile.temperature.toDouble()),
        "maxOutputTokens" to num(profile.maxOutputTokens),
        "streaming" to bool(profile.streaming),
        "systemPromptOverride" to str(profile.systemPromptOverride),
    )

    fun decodeProfile(json: Json): ProviderProfile? {
        val obj = json as? Json.Obj ?: return null
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val kind = ProviderKind.fromId(obj.string("provider"))
        return ProviderProfile(
            id = id,
            name = obj.string("name") ?: kind.label,
            provider = kind,
            baseUrl = obj.string("baseUrl") ?: kind.defaultBaseUrl,
            model = obj.string("model") ?: "",
            temperature = (obj.double("temperature") ?: 0.2).toFloat(),
            maxOutputTokens = obj.int("maxOutputTokens") ?: 1024,
            streaming = obj.bool("streaming") ?: false,
            systemPromptOverride = obj.string("systemPromptOverride"),
        )
    }
}
