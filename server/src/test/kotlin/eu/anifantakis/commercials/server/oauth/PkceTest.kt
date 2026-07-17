package eu.anifantakis.commercials.server.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkceTest {

    // ───────────────────────────────────────────────── S256 challenge ──

    /** RFC 7636 Appendix B: the official verifier→challenge test vector. */
    @Test
    fun s256MatchesTheRfcVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun challengeMatchesVerifiesAndRejects() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertTrue(challengeMatches("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", verifier))
        assertFalse(challengeMatches("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", verifier + "x"))
        assertFalse(challengeMatches("bogus", verifier))
    }

    // ─────────────────────────────────────────────── verifier format ──

    @Test
    fun verifierLengthBounds() {
        assertFalse(isValidCodeVerifier("a".repeat(42)))   // one short
        assertTrue(isValidCodeVerifier("a".repeat(43)))
        assertTrue(isValidCodeVerifier("a".repeat(128)))
        assertFalse(isValidCodeVerifier("a".repeat(129)))  // one long
    }

    @Test
    fun verifierCharset() {
        assertTrue(isValidCodeVerifier("ABCxyz0189-._~".repeat(4)))          // 56 chars, all legal
        assertFalse(isValidCodeVerifier("a".repeat(42) + "+"))               // base64 (not base64url) char
        assertFalse(isValidCodeVerifier("a".repeat(42) + "="))               // padding
        assertFalse(isValidCodeVerifier("a".repeat(42) + " "))
    }

    // ─────────────────────────────────── redirect URI acceptability ──

    @Test
    fun httpsAnywhereIsAcceptable() {
        assertTrue(isAcceptableRedirectUri("https://claude.ai/api/mcp/auth_callback"))
        assertTrue(isAcceptableRedirectUri("https://chatgpt.com/connector/oauth/abc123"))
    }

    @Test
    fun httpOnlyOnLoopback() {
        assertTrue(isAcceptableRedirectUri("http://localhost/callback"))
        assertTrue(isAcceptableRedirectUri("http://localhost:8787/callback"))
        assertTrue(isAcceptableRedirectUri("http://127.0.0.1:33418/cb"))
        assertFalse(isAcceptableRedirectUri("http://evil.com/cb"))
        assertFalse(isAcceptableRedirectUri("http://192.168.1.7/cb"))   // private, but NOT loopback
    }

    @Test
    fun dangerousSchemesAndFragmentsAreRejected() {
        assertFalse(isAcceptableRedirectUri("javascript:alert(1)"))
        assertFalse(isAcceptableRedirectUri("data:text/html,x"))
        assertFalse(isAcceptableRedirectUri("myapp://callback"))
        assertFalse(isAcceptableRedirectUri("https://ok.example/cb#fragment"))
        assertFalse(isAcceptableRedirectUri("not a uri"))
    }

    // ──────────────────────────────────────── redirect URI matching ──

    @Test
    fun exactMatchAlwaysWins() {
        assertTrue(redirectUriMatches("https://claude.ai/api/mcp/auth_callback", "https://claude.ai/api/mcp/auth_callback"))
    }

    @Test
    fun nonLoopbackRequiresExactMatch() {
        assertFalse(redirectUriMatches("https://claude.ai/api/mcp/auth_callback", "https://claude.ai/api/mcp/auth_callback2"))
        assertFalse(redirectUriMatches("https://claude.ai/cb", "https://evil.claude.ai.attacker.com/cb"))
        assertFalse(redirectUriMatches("https://claude.ai/cb", "https://claude.ai/cb/extra"))
        // The loopback port exception must NOT leak to public hosts.
        assertFalse(redirectUriMatches("https://claude.ai/cb", "https://claude.ai:8443/cb"))
    }

    /** RFC 8252 §7.3: loopback redirects match ignoring the port (ephemeral per run). */
    @Test
    fun loopbackIgnoresPort() {
        assertTrue(redirectUriMatches("http://localhost/callback", "http://localhost:52301/callback"))
        assertTrue(redirectUriMatches("http://localhost:7777/callback", "http://localhost:9999/callback"))
        assertTrue(redirectUriMatches("http://127.0.0.1/cb", "http://127.0.0.1:33418/cb"))
        // ...but never ignoring path, scheme, or host.
        assertFalse(redirectUriMatches("http://localhost/callback", "http://localhost:52301/other"))
        assertFalse(redirectUriMatches("http://localhost/callback", "https://localhost/callback"))
        assertFalse(redirectUriMatches("http://localhost/callback", "http://127.0.0.1/callback"))
    }

    // ─────────────────────────────────────────── canonical resource ──

    @Test
    fun canonicalFormLowercasesAndStripsDefaults() {
        assertEquals("https://mcp.example.gr/mcp/http", canonicalResource("HTTPS://MCP.Example.GR:443/mcp/http/"))
        assertEquals("http://localhost:8080/mcp/http", canonicalResource("http://localhost:8080/mcp/http"))
        assertEquals("https://mcp.example.gr", canonicalResource("https://mcp.example.gr/"))
    }

    @Test
    fun canonicalRejectsJunk() {
        assertNull(canonicalResource("not a uri at all //"))
        assertNull(canonicalResource("mcp.example.gr/mcp"))   // no scheme
    }
}
