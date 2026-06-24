package dev.phonecode.app.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAuthTest {

    private val server = MockWebServer().apply { start() }
    private val stored = mutableMapOf<String, String>()
    private val auth = GitHubAuth(
        http = OkHttpClient(),
        store = { k, v -> stored[k] = v },
        read = { stored[it] },
        deviceCodeUrl = server.url("/login/device/code").toString(),
        tokenUrl = server.url("/login/oauth/access_token").toString(),
        userUrl = server.url("/user").toString(),
    )

    @After fun tearDown() = server.shutdown()

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** A code that polls instantly (interval=0) and won't expire mid-test. */
    private fun code(expiresIn: Int = 900) =
        GitHubAuth.DeviceCode("dev-1", "ABCD-1234", "https://github.com/login/device", expiresIn, 0)

    // -- startDeviceFlow --

    @Test fun startDeviceFlowParsesResponseAndSendsClientId() {
        server.enqueue(
            json(
                """{"device_code":"dc-9","user_code":"WXYZ-7890",
                    "verification_uri":"https://github.com/login/device","expires_in":899,"interval":5}""",
            ),
        )
        val device = auth.startDeviceFlow()
        assertEquals("dc-9", device.deviceCode)
        assertEquals("WXYZ-7890", device.userCode)
        assertEquals("https://github.com/login/device", device.verificationUri)
        assertEquals(899, device.expiresIn)
        assertEquals(5, device.interval)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("client_id=${GitHubAuth.CLIENT_ID}"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    // -- pollForToken --

    @Test fun pollSucceedsAfterAuthorizationPendingAndStoresToken() {
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"access_token":"gho_tok","token_type":"bearer"}"""))

        assertEquals("gho_tok", auth.pollForToken(code()))
        assertEquals("gho_tok", stored["git.token"])
        assertEquals(2, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("device_code=dev-1"))
        assertTrue(body.contains("grant_type=urn")) // RFC 8628 device_code grant
    }

    @Test fun pollThrowsOnGitHubDenial() {
        server.enqueue(json("""{"error":"access_denied"}"""))
        assertThrows(GitHubAuth.AccessDeniedException::class.java) { auth.pollForToken(code()) }
    }

    @Test fun pollThrowsWhenCodeAlreadyExpired() {
        // expiresIn=0 -> the deadline has passed before the first request; no HTTP traffic at all.
        assertThrows(GitHubAuth.DeviceCodeExpiredException::class.java) {
            auth.pollForToken(code(expiresIn = 0))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun pollThrowsWhenGitHubReportsExpiredToken() {
        server.enqueue(json("""{"error":"expired_token"}"""))
        assertThrows(GitHubAuth.DeviceCodeExpiredException::class.java) { auth.pollForToken(code()) }
    }

    @Test fun pollAbandonsWithoutRequestWhenInactive() {
        assertThrows(GitHubAuth.SignInAbandonedException::class.java) {
            auth.pollForToken(code()) { false }
        }
        assertEquals(0, server.requestCount)
    }

    // -- fetchLogin --

    @Test fun fetchLoginStoresLoginAndGitUsername() {
        server.enqueue(json("""{"login":"octocat","id":1}"""))
        assertEquals("octocat", auth.fetchLogin("gho_tok"))
        assertEquals("octocat", stored["github.login"])
        assertEquals("octocat", stored["git.username"])
        assertEquals("Bearer gho_tok", server.takeRequest().getHeader("Authorization"))
        assertEquals("octocat", auth.login())
    }

    // -- signOut --

    @Test fun signOutClearsAllKeys() {
        stored["git.token"] = "t"; stored["git.username"] = "u"; stored["github.login"] = "l"
        auth.signOut()
        assertEquals("", stored["git.token"])
        assertEquals("", stored["git.username"])
        assertEquals("", stored["github.login"])
    }
}
