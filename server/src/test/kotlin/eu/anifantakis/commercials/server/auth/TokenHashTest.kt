package eu.anifantakis.commercials.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TokenHashTest {

    /** NIST FIPS 180-2 test vector: SHA-256("abc"). Guards the hex encoding too. */
    @Test
    fun matchesKnownSha256Vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            tokenHash("abc")
        )
    }

    @Test
    fun isDeterministicAndCollisionFreeForDistinctTokens() {
        val a = tokenHash("token-a")
        assertEquals(a, tokenHash("token-a"))
        assertNotEquals(a, tokenHash("token-b"))
    }

    /** 64 lowercase hex chars - must fit the token_hash VARCHAR(64) column. */
    @Test
    fun producesSixtyFourLowercaseHexChars() {
        val h = tokenHash("0f".repeat(32))
        assertEquals(64, h.length)
        assertEquals(h, h.lowercase())
        assertEquals(true, h.all { it in "0123456789abcdef" })
    }
}
