package dev.phonecode.app.auth

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitHubAuthTest {

    private val clientId = "phonecode-test-client"
    private val server = MockWebServer().apply { start() }
    private val stored = mutableMapOf<String, String>()
    private val writes = mutableListOf<Map<String, String>>()
    private val auth = GitHubAuth(
        http = OkHttpClient(),
        store = { values ->
            writes += values
            values.forEach { (key, value) ->
                if (value.isBlank()) stored.remove(key) else stored[key] = value
            }
        },
        read = { stored[it] },
        clientId = clientId,
        deviceCodeUrl = server.url("/login/device/code").toString(),
        tokenUrl = server.url("/login/oauth/access_token").toString(),
        userUrl = server.url("/user").toString(),
    )

    @Test fun releaseRequiresAnAppOwnedClientId() {
        assertEquals("phonecode-owned-client", githubOAuthClientId(" phonecode-owned-client ", debug = false))
        assertThrows(IllegalStateException::class.java) { githubOAuthClientId("", debug = false) }
        assertThrows(IllegalStateException::class.java) { githubOAuthClientId(GitHubAuth.CLIENT_ID, debug = false) }
    }

    @Test fun debugMayUseThePublicClientFallback() {
        assertEquals(GitHubAuth.CLIENT_ID, githubOAuthClientId("", debug = true))
    }

    @Test fun authClientDisablesBothRedirectModes() {
        val field = GitHubAuth::class.java.getDeclaredField("http").apply { isAccessible = true }
        val client = field.get(auth) as OkHttpClient

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

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
        assertTrue(body.contains("client_id=$clientId"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    // -- pollForToken --

    @Test fun pollSucceedsAfterAuthorizationPendingWithoutPersistingToken() {
        server.enqueue(json("""{"error":"authorization_pending"}"""))
        server.enqueue(json("""{"access_token":"gho_tok","token_type":"bearer"}"""))

        assertEquals("gho_tok", auth.pollForToken(code()))
        assertFalse(stored.containsKey("git.token"))
        assertTrue(writes.isEmpty())
        assertEquals(2, server.requestCount)
        repeat(2) {
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("client_id=$clientId"))
            assertTrue(body.contains("device_code=dev-1"))
            assertTrue(body.contains("grant_type=urn")) // RFC 8628 device_code grant
        }
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

    @Test fun pollDoesNotFollowRedirectWithDeviceCode() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/replayed")),
        )
        server.enqueue(json("""{"access_token":"replayed"}"""))

        assertThrows(IOException::class.java) { auth.pollForToken(code()) }

        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("device_code=dev-1"))
    }

    // -- finishSignIn --

    @Test fun finishSignInPersistsAllCredentialsOnlyAfterIdentitySucceeds() {
        server.enqueue(json("""{"login":"octocat","id":1}"""))

        assertEquals("octocat", auth.finishSignIn("gho_tok"))

        assertEquals(1, writes.size)
        assertEquals("gho_tok", stored["git.token"])
        assertEquals("octocat", stored["github.login"])
        assertEquals("octocat", stored["git.username"])
        assertEquals("Bearer gho_tok", server.takeRequest().getHeader("Authorization"))
        assertEquals("octocat", auth.login())
    }

    @Test fun identityFailureDoesNotPersistAnyCredential() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/replayed")),
        )
        server.enqueue(json("""{"login":"attacker"}"""))

        assertThrows(IOException::class.java) { auth.finishSignIn("gho_secret") }

        assertEquals(1, server.requestCount)
        assertEquals("Bearer gho_secret", server.takeRequest().getHeader("Authorization"))
        assertTrue(stored.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun cancellationAfterIdentityDoesNotPersistCredentials() {
        server.enqueue(json("""{"login":"octocat","id":1}"""))
        var activeChecks = 0

        assertThrows(GitHubAuth.SignInAbandonedException::class.java) {
            auth.finishSignIn("gho_tok", active = { ++activeChecks == 1 })
        }

        assertTrue(stored.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun cancellationWhilePublishingRollsBackToPreviousCredentials() {
        stored["git.token"] = "manual-token"
        stored["git.username"] = "manual-user"
        server.enqueue(json("""{"login":"octocat","id":1}"""))
        var active = true

        assertThrows(GitHubAuth.SignInAbandonedException::class.java) {
            auth.finishSignIn("gho_tok", active = { active }) {
                active = false
            }
        }

        assertEquals("manual-token", stored["git.token"])
        assertEquals("manual-user", stored["git.username"])
        assertFalse(stored.containsKey("github.login"))
    }

    @Test fun credentialTransactionExcludesConcurrentManualWrites() {
        server.enqueue(json("""{"login":"octocat","id":1}"""))
        val lock = Any()
        val published = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val manualWriteEntered = CountDownLatch(1)
        val transactionalAuth = GitHubAuth(
            http = OkHttpClient(),
            store = { values ->
                values.forEach { (key, value) ->
                    if (value.isBlank()) stored.remove(key) else stored[key] = value
                }
            },
            read = { stored[it] },
            credentialLock = lock,
            clientId = clientId,
            deviceCodeUrl = server.url("/login/device/code").toString(),
            tokenUrl = server.url("/login/oauth/access_token").toString(),
            userUrl = server.url("/user").toString(),
        )
        val authThread = Thread {
            transactionalAuth.finishSignIn("device-token") {
                published.countDown()
                assertTrue(allowCompletion.await(2, TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(published.await(2, TimeUnit.SECONDS))
        val manualThread = Thread {
            synchronized(lock) {
                manualWriteEntered.countDown()
                stored["git.token"] = "manual-token"
                stored["git.username"] = "manual-user"
                stored["github.login"] = "manual-user"
            }
        }.apply { start() }

        assertFalse(manualWriteEntered.await(100, TimeUnit.MILLISECONDS))
        allowCompletion.countDown()
        authThread.join(2_000)
        manualThread.join(2_000)

        assertEquals("manual-token", stored["git.token"])
        assertEquals("manual-user", stored["git.username"])
        assertEquals("manual-user", stored["github.login"])
    }

    // -- signOut --

    @Test fun signOutClearsAllKeys() {
        stored["git.token"] = "t"; stored["git.username"] = "u"; stored["github.login"] = "l"
        auth.signOut()
        assertFalse(stored.containsKey("git.token"))
        assertFalse(stored.containsKey("git.username"))
        assertFalse(stored.containsKey("github.login"))
        assertEquals(1, writes.size)
    }
}
