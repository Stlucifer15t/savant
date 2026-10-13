package dev.savant.data.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encrypted secrets file, tested without a keystore.
 *
 * The cipher is a parameter, so everything that can silently destroy a user's keys — version bytes,
 * truncation, a decrypt that throws, a body that is not json — is asserted here with a fake. The
 * android keystore only has to do two things: encrypt bytes and decrypt them.
 */
class SecretVaultTest {

    /** Stands in for aes-gcm: reversible, and it fails loudly on the wrong key. */
    private val cipher: (ByteArray) -> ByteArray = { bytes -> ByteArray(bytes.size) { (bytes[it] + 7).toByte() } }
    private val reverse: (ByteArray) -> ByteArray = { bytes -> ByteArray(bytes.size) { (bytes[it] - 7).toByte() } }

    @Test
    fun aSealedVaultOpensAgain() {
        val plain = "these are secrets".toByteArray()
        val vault = SecretVault.seal(plain, cipher)
        assertTrue(vault.size > plain.size)
        assertArrayEquals(plain, SecretVault.open(vault, reverse))
    }

    @Test
    fun aVaultCarriesItsMagicAndVersion() {
        val vault = SecretVault.seal(byteArrayOf(1, 2, 3), cipher)
        assertEquals('S'.code.toByte(), vault[0])
        assertEquals('V'.code.toByte(), vault[1])
        assertEquals('L'.code.toByte(), vault[2])
        assertEquals('T'.code.toByte(), vault[3])
        assertEquals(SecretVault.VERSION, vault[4])
    }

    @Test
    fun somethingThatIsNotAVaultIsRefusedRatherThanDecrypted() {
        assertNull(SecretVault.open("not a vault at all".toByteArray(), reverse))
        // A vault from a future build is not guessed at: savant would rather ask for the keys again.
        val future = SecretVault.seal(byteArrayOf(9), cipher).copyOf()
        future[4] = 99
        assertNull(SecretVault.open(future, reverse))
        assertNull(SecretVault.open(byteArrayOf('S'.code.toByte()), reverse))
        assertNull(SecretVault.open(ByteArray(0), reverse))
    }

    @Test
    fun aBodyTheCipherRejectsIsNotAnAnswer() {
        val corrupt = SecretVault.seal(byteArrayOf(1, 2, 3), cipher).copyOf()
        corrupt[6] = 0
        val thrown = { throw IllegalStateException("bad tag") }
        assertNull(SecretVault.open(corrupt, { thrown() }))
    }

    @Test
    fun secretsRoundTripAsOneJsonObject() {
        val secrets = mapOf(
            SecretStore.providerKey("p1") to "sk-ant-1234abcd",
            SecretStore.SEARCH_KEY to "brave-key-9",
        )
        val vault = SecretVault.encode(secrets, cipher)
        assertEquals(secrets, SecretVault.decode(vault, reverse))
    }

    @Test
    fun aCorruptVaultYieldsNoSecretsInsteadOfCrashing() {
        val vault = SecretVault.encode(mapOf("a" to "1"), cipher)
        assertTrue(SecretVault.decode(vault.copyOf(vault.size - 3), reverse).isEmpty())
        assertTrue(SecretVault.decode("garbage".toByteArray(), reverse).isEmpty())
        // Decrypts fine, but the plaintext is not the json this version writes.
        val junk = SecretVault.seal("[]".toByteArray(), cipher)
        assertTrue(SecretVault.decode(junk, reverse).isEmpty())
    }

    @Test
    fun theMemoryStoreKeepsTheSameContract() {
        val store = MemorySecretStore()
        assertTrue(store.isVolatile)
        assertEquals("memory only", store.storageLabel)
        store.put("a", "secret")
        assertTrue(store.has("a"))
        assertEquals("secret", store.get("a"))
        store.put("a", "")
        assertFalse(store.has("a"))
        store.put("b", "x")
        store.clearAll()
        assertNull(store.get("b"))
    }
}
