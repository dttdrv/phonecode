package dev.phonecode.app.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MisulNativeHandshakeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun opensHandshakesListsModelsRejectsHostileInputAndReopens() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                assertEquals(1, MisulNative.abiVersion())
                repeat(2) { attempt ->
                    val root = File(context.cacheDir, "misul-native-handshake-$attempt").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val session = MisulNative.open(config(root).toString().encodeToByteArray())
                    try {
                        val handshake = session.request(
                            """{"jsonrpc":"2.0","id":1,"method":"rpc/handshake","params":{"protocol_major":1,"protocol_minor":0}}"""
                                .encodeToByteArray(),
                        ).decodeToString()
                        assertEquals(1, JSONObject(handshake).getJSONObject("result").getJSONObject("protocol").getInt("major"))

                        val modelList = session.request(
                            """{"jsonrpc":"2.0","id":2,"method":"model/list","params":{}}""".encodeToByteArray(),
                        ).decodeToString()
                        val models = JSONObject(modelList).getJSONObject("result").getJSONArray("models")
                        assertEquals("phonecode-test-model", models.getJSONObject(0).getString("id"))
                        assertEquals(null, session.nextEvent(0))

                        val promptSubmission = session.request(
                            """{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"prompt":"exercise JNI events"}}"""
                                .encodeToByteArray(),
                        )
                        assertEquals(0, promptSubmission.size)
                        val records = buildList {
                            repeat(16) {
                                session.nextEvent(2_000)?.decodeToString()?.let(::add)
                                if (any { "\"id\":3" in it }) return@buildList
                            }
                        }
                        assertEquals(true, records.any { "\"method\":\"agent_start\"" in it })
                        assertEquals(true, records.any { "\"id\":3" in it })
                        assertThrows(IllegalArgumentException::class.java) {
                            session.hostResponse(byteArrayOf('{'.code.toByte()))
                        }

                        assertThrows(IllegalArgumentException::class.java) {
                            session.request(ByteArray(1024 * 1024 + 1))
                        }
                        assertThrows(IllegalArgumentException::class.java) {
                            session.request(ByteArray(0))
                        }
                        Executors.newSingleThreadExecutor().let { otherThread ->
                            try {
                                otherThread.submit {
                                    assertThrows(IllegalStateException::class.java) {
                                        session.request(byteArrayOf('{'.code.toByte()))
                                    }
                                    assertThrows(IllegalStateException::class.java) {
                                        session.nextEvent(0)
                                    }
                                }.get(5, TimeUnit.SECONDS)
                            } finally {
                                otherThread.shutdownNow()
                            }
                        }
                    } finally {
                        session.close()
                        session.close()
                    }
                    assertThrows(IllegalStateException::class.java) {
                        session.request(byteArrayOf('{'.code.toByte()))
                    }
                }
            }.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun config(root: File) = JSONObject()
        .put("workspace_root", root.resolve("workspace").apply(File::mkdirs).absolutePath)
        .put("state_root", root.resolve("state").apply(File::mkdirs).absolutePath)
        .put("system_prompt", "PhoneCode native handshake test")
        .put(
            "default_selection",
            JSONObject()
                .put("provider", "phonecode-test-provider")
                .put("model", "phonecode-test-model")
                .put("context_window", 4096)
                .put("output_limit", 256),
        )
        .put(
            "model_profiles",
            JSONArray().put(
                JSONObject()
                    .put("id", "phonecode-test-model")
                    .put("name", "PhoneCode test model")
                    .put("provider", "phonecode-test-provider")
                    .put("context_window", 4096)
                    .put("output_limit", 256)
                    .put("max_output_tokens", 512),
            ),
        )
        .put(
            "provider_configs",
            JSONArray().put(
                JSONObject()
                    .put("id", "phonecode-test-provider")
                    .put("endpoint", "http://127.0.0.1:1/v1")
                    .put("credential_ref", "PHONECODE_TEST_KEY")
                    .put(
                        "models",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "phonecode-test-model")
                                .put("context_window", 4096)
                                .put("output_limit", 512),
                        ),
                    )
                    .put("retry", JSONObject().put("deadline_ms", 1000)),
            ),
        )
}
