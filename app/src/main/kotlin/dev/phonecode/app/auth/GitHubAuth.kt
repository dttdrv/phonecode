package dev.phonecode.app.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * "Sign in with GitHub" via the OAuth 2.0 Device Authorization Grant (RFC 8628) - see
 * design/specs/github-device-flow.md. No backend or redirect URI: the app shows a short code,
 * the user enters it at github.com/login/device, and the app polls for the token.
 *
 * On success the token + login are persisted through [store] under `git.token` / `git.username` -
 * the exact keys the agent's git push/pull tools already read - plus `github.login` for display.
 */
class GitHubAuth(
    private val http: OkHttpClient,
    private val store: (String, String) -> Unit,
    private val read: (String) -> String?,
    // Injectable for tests (MockWebServer); production uses the real GitHub endpoints.
    private val deviceCodeUrl: String = DEVICE_CODE_ENDPOINT,
    private val tokenUrl: String = TOKEN_ENDPOINT,
    private val userUrl: String = USER_ENDPOINT,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returned by [startDeviceFlow]; show [userCode], open [verificationUri], then [pollForToken]. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
    )

    /** Step 1: request device + user codes. Blocking; throws [IOException] on failure. */
    fun startDeviceFlow(): DeviceCode {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val request = Request.Builder()
            .url(deviceCodeUrl)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub device/code failed: HTTP ${response.code} ${text.take(300)}")
            }
            val r = json.decodeFromString(DeviceCodeResponse.serializer(), text)
            return DeviceCode(r.deviceCode, r.userCode, r.verificationUri, r.expiresIn, r.interval)
        }
    }

    /**
     * Step 3: poll until authorized, denied, or expired; returns the access token. Blocking - run on
     * Dispatchers.IO. Respects the interval and the cumulative +5s `slow_down` backoff (RFC 8628 §3.5).
     * The [active] probe lets the caller abandon the loop (e.g. user dismissed the sign-in sheet);
     * it is checked every ≤500ms during the wait so cancellation doesn't dangle for a full interval.
     */
    fun pollForToken(deviceCode: DeviceCode, active: () -> Boolean = { true }): String {
        val deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1_000L
        var intervalMs = deviceCode.interval * 1_000L

        while (System.currentTimeMillis() < deadline) {
            sleepWhileActive(intervalMs, active)
            if (!active()) throw SignInAbandonedException()

            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("device_code", deviceCode.deviceCode)
                .add("grant_type", GRANT_TYPE)
                .build()
            val request = Request.Builder()
                .url(tokenUrl)
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            val (token, error) = http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("GitHub token poll: HTTP ${response.code} ${text.take(300)}")
                }
                val obj = json.parseToJsonElement(text).jsonObject
                obj["access_token"]?.jsonPrimitive?.content to obj["error"]?.jsonPrimitive?.content
            }

            if (token != null) {
                store(KEY_TOKEN, token)
                return token
            }
            when (error) {
                "authorization_pending" -> Unit // normal; keep polling
                "slow_down" -> intervalMs += 5_000L
                "expired_token" -> throw DeviceCodeExpiredException()
                "access_denied" -> throw AccessDeniedException()
                else -> throw IOException("GitHub device flow error: $error")
            }
        }
        throw DeviceCodeExpiredException()
    }

    /** Sleeps in ≤500ms slices, returning early once [active] flips false. */
    private fun sleepWhileActive(totalMs: Long, active: () -> Boolean) {
        var remaining = totalMs
        while (remaining > 0 && active()) {
            val slice = minOf(remaining, 500L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    /** Fetches + persists the user's GitHub login. Blocking; throws [IOException] on failure. */
    fun fetchLogin(token: String): String {
        val request = Request.Builder()
            .url(userUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub /user failed: HTTP ${response.code} ${text.take(300)}")
            }
            val login = json.parseToJsonElement(text).jsonObject["login"]?.jsonPrimitive?.content
                ?: throw IOException("GitHub /user response missing login")
            store(KEY_LOGIN, login)
            // The git tools authenticate with these two keys; GitHub ignores the username when the
            // password is a valid token, so the login doubles as a readable Basic username.
            store(KEY_GIT_USERNAME, login)
            return login
        }
    }

    fun login(): String? = read(KEY_LOGIN)

    fun signOut() {
        listOf(KEY_TOKEN, KEY_LOGIN, KEY_GIT_USERNAME).forEach { store(it, "") }
    }

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Int = 900,
        @SerialName("interval") val interval: Int = 5,
    )

    class DeviceCodeExpiredException : IOException("GitHub code expired - start the sign-in again.")

    /** GitHub reported the user actively denied the authorization request. */
    class AccessDeniedException : IOException("GitHub sign-in was denied.")

    /** The caller abandoned the flow locally (cancel button, screen dismissed) - not an error. */
    class SignInAbandonedException : IOException("GitHub sign-in was cancelled.")

    companion object {
        /**
         * gh CLI's public device-flow client id - a working default for personal builds. A published
         * release MUST register its own OAuth app (Device Flow enabled) and replace this; tokens from
         * this id show as authorized by "GitHub CLI" and follow its org-level OAuth restrictions.
         */
        const val CLIENT_ID = "178c6fc778ccc68e1d6a"

        const val DEVICE_CODE_ENDPOINT = "https://github.com/login/device/code"
        const val TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
        const val USER_ENDPOINT = "https://api.github.com/user"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val SCOPE = "repo read:user"

        private const val KEY_TOKEN = "git.token"
        private const val KEY_GIT_USERNAME = "git.username"
        private const val KEY_LOGIN = "github.login"
    }
}
