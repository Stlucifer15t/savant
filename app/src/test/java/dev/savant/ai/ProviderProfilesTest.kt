package dev.savant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profiles are settings, and settings have to survive a bad write.
 *
 * A corrupt list must mean "no profiles", never a crash on the settings screen — and a profile
 * written by an older build must still load.
 */
class ProviderProfilesTest {

    @Test
    fun aListRoundTrips() {
        val profiles = listOf(
            ProviderProfiles.blank(ProviderKind.CLAUDE, id = "p1"),
            ProviderProfiles.blank(ProviderKind.GEMINI, id = "p2").copy(
                name = "research",
                baseUrl = "http://10.0.0.2:8080/v1",
                model = "gemini-2.0-flash",
                temperature = 0.7f,
                maxOutputTokens = 2048,
                streaming = false,
                systemPromptOverride = "be terse",
                active = true,
            ),
        )
        val restored = ProviderProfiles.read(ProviderProfiles.write(profiles))
        assertEquals(2, restored.size)
        assertEquals(profiles[0], restored[0])
        assertEquals(profiles[1], restored[1])
        assertTrue(restored[1].active)
        assertFalse(restored[1].streaming)
        assertEquals(0.7f, restored[1].temperature, 0.0001f)
    }

    @Test
    fun aCorruptListMeansNoProfilesRatherThanACrash() {
        assertTrue(ProviderProfiles.read(null).isEmpty())
        assertTrue(ProviderProfiles.read("").isEmpty())
        assertTrue(ProviderProfiles.read("not json at all").isEmpty())
        assertTrue(ProviderProfiles.read("""{"version":1}""").isEmpty())
        // An entry with no id cannot be activated or keyed, so it is dropped, not guessed at.
        assertTrue(ProviderProfiles.read("""{"profiles":[{"name":"x"}]}""").isEmpty())
    }

    @Test
    fun anOlderEntryLoadsWithDefaults() {
        val restored = ProviderProfiles.read(
            """{"version":1,"profiles":[{"id":"p9","name":"old","provider":"claude","baseUrl":"","model":"claude-sonnet-4-5"}]}""",
        )
        assertEquals(1, restored.size)
        assertEquals(ProviderKind.CLAUDE, restored.first().provider)
        assertEquals(0.2f, restored.first().temperature, 0.0001f)
        assertEquals(1024, restored.first().maxOutputTokens)
        assertTrue(restored.first().streaming)
    }

    @Test
    fun anUnknownProviderFallsBackToCustom() {
        val restored = ProviderProfiles.read("""{"profiles":[{"id":"p9","provider":"wat","model":"m"}]}""")
        assertEquals(ProviderKind.CUSTOM, restored.first().provider)
    }

    @Test
    fun aBlankProfileHasTheDefaultsOfItsProvider() {
        val claude = ProviderProfiles.blank(ProviderKind.CLAUDE)
        assertEquals("https://api.anthropic.com/v1", claude.baseUrl)
        assertEquals("claude-sonnet-4-5", claude.model)
        assertTrue(claude.id.startsWith("p"))
        assertTrue(ProviderProfiles.needsKey(claude))
        assertNull(ProviderProfiles.problem(claude))

        val selfHosted = ProviderProfiles.blank(ProviderKind.CUSTOM).copy(baseUrl = "http://localhost:11434/v1", model = "llama3")
        assertFalse(ProviderProfiles.needsKey(selfHosted))
        assertNull(ProviderProfiles.problem(selfHosted))
    }

    @Test
    fun anUnusableProfileSaysWhatIsMissing() {
        assertEquals("no model name", ProviderProfiles.problem(ProviderProfiles.blank(ProviderKind.CLAUDE).copy(model = "")))
        assertEquals(
            "a custom provider needs a base url",
            ProviderProfiles.problem(ProviderProfiles.blank(ProviderKind.CUSTOM).copy(baseUrl = "", model = "llama3")),
        )
    }
}
