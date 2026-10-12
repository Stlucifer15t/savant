package dev.savant.domain

import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.UnsupportedSaveFormatException
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ParserRegistryTest {
    private val registry = ParserRegistry(setOf(JsonSaveParser(), XmlSaveParser()))

    @Test
    fun `structure wins when extension is generic`() {
        assertEquals("xml", registry.detect("slot1.sav", "<save/>".encodeToByteArray()).parser.id)
        assertEquals("json", registry.detect("slot2.save", "{\"hp\":1}".encodeToByteArray()).parser.id)
    }

    @Test
    fun `specific extension gives a cautious fallback`() {
        assertEquals("json", registry.detect("slot.json", "not actually json".encodeToByteArray()).parser.id)
    }

    @Test
    fun `unknown binary is rejected`() {
        assertFailsWith<UnsupportedSaveFormatException> {
            registry.detect("slot.bin", byteArrayOf(0, 1, 2, 3))
        }
    }
}
