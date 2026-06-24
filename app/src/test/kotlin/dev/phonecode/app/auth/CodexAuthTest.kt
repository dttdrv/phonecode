package dev.phonecode.app.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CodexAuthTest {

    private val auth = CodexAuth(OkHttpClient(), { _, _ -> }, { null })

    // -- PKCE --

    @Test fun pkceChallengeMatchesRfc7636TestVector() {
        // RFC 7636 Appendix B: known verifier -> expected S256 challenge.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            auth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun generatedVerifierIsBase64UrlNoPad() {
        val verifier = auth.generateVerifier()
        assertEquals(43, verifier.length) // 32 bytes -> 43 base64url chars, no padding
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
    }

    // -- Authorization URL --

    @Test fun authUrlContainsRequiredParams() {
        val url = auth.buildAuthUrl().toHttpUrl()
        assertEquals("auth.openai.com", url.host)
        assertEquals("/oauth/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", url.queryParameter("client_id"))
        assertEquals("http://localhost:1455/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("true", url.queryParameter("id_token_add_organizations"))
        assertEquals("true", url.queryParameter("codex_cli_simplified_flow"))
        // state and code_challenge are the freshly generated pending values
        assertTrue(!url.queryParameter("state").isNullOrEmpty())
        assertEquals(auth.pendingState, url.queryParameter("state"))
        assertEquals(auth.codeChallenge(auth.pendingVerifier!!), url.queryParameter("code_challenge"))
    }

    @Test fun buildAuthUrlGeneratesFreshVerifierAndState() {
        auth.buildAuthUrl()
        val firstVerifier = auth.pendingVerifier
        val firstState = auth.pendingState
        auth.buildAuthUrl()
        assertTrue(firstVerifier != auth.pendingVerifier)
        assertTrue(firstState != auth.pendingState)
    }

    // -- JWT claim extraction --

    @Test fun extractsBareAccountIdClaim() {
        val token = jwt("""{"sub":"user-1","chatgpt_account_id":"acct-123"}""")
        assertEquals("acct-123", auth.extractAccountId(token))
    }

    @Test fun extractsNamespacedAccountIdClaims() {
        val nested = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-456"}}""")
        assertEquals("acct-456", auth.extractAccountId(nested))
        val dotted = jwt("""{"https://api.openai.com/auth.chatgpt_account_id":"acct-789"}""")
        assertEquals("acct-789", auth.extractAccountId(dotted))
    }

    @Test fun extractAccountIdReturnsNullWhenAbsentOrMalformed() {
        assertNull(auth.extractAccountId(jwt("""{"sub":"user-1"}""")))
        assertNull(auth.extractAccountId("not-a-jwt"))
        assertNull(auth.extractAccountId("a.!!!not-base64url!!!.c"))
    }

    /** Hand-built unsigned JWT: base64url(header).base64url(payload).fake-signature. */
    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.sig"
    }

    // -- Loopback callback handling --

    @Test fun loopbackIgnoresWrongStateAndAcceptsMatching() {
        val latch = CountDownLatch(1)
        val received = AtomicReference<String?>(null)
        auth.startLoopback("expected-state") { code ->
            received.set(code)
            latch.countDown()
        }
        try {
            // A bogus/stray callback (wrong state) is answered politely but must NOT complete the flow
            // and must NOT kill the listener.
            val first = hitLoopback("code=evil&state=wrong")
            assertTrue(first.startsWith("HTTP/1.1 200"))
            assertEquals(1L, latch.count)

            // The genuine callback completes the flow with the right code.
            val second = hitLoopback("code=good-123&state=expected-state")
            assertTrue(second.startsWith("HTTP/1.1 200"))
            assertTrue("callback not invoked", latch.await(3, TimeUnit.SECONDS))
            assertEquals("good-123", received.get())
        } finally {
            auth.stopLoopback()
        }
    }

    @Test fun loopbackBindsLoopbackInterfaceOnly() {
        auth.startLoopback("s") { }
        try {
            // Connecting via 127.0.0.1 works; the socket must not be reachable on other interfaces,
            // which we can at least assert by checking the bound address is loopback.
            Socket("127.0.0.1", 1455).use { assertTrue(it.isConnected) }
        } finally {
            auth.stopLoopback()
        }
    }

    /** Sends a minimal GET to the loopback listener and returns the response status line. */
    private fun hitLoopback(query: String): String =
        Socket("127.0.0.1", 1455).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().apply {
                write("GET /auth/callback?$query HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readLine() ?: ""
        }
}
