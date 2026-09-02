package dev.phonecode.app.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MisulNativePerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun recordsOneColdAndFiveWarmSamples() {
        val root = File(context.cacheDir, "misul-native-performance").apply {
            deleteRecursively()
            mkdirs()
        }
        val config = config(root).toString().encodeToByteArray()
        val handshake = """{"jsonrpc":"2.0","id":1,"method":"rpc/handshake","params":{"protocol_major":1,"protocol_minor":0}}"""
            .encodeToByteArray()
        val modelList = """{"jsonrpc":"2.0","id":2,"method":"model/list","params":{}}""".encodeToByteArray()

        val loadStart = SystemClock.elapsedRealtimeNanos()
        assertEquals(1, MisulNative.abiVersion())
        val loadEnd = SystemClock.elapsedRealtimeNanos()
        val session = MisulNative.open(config)
        val openEnd = SystemClock.elapsedRealtimeNanos()
        try {
            val response = JSONObject(session.request(handshake).decodeToString())
            assertEquals(1, response.getJSONObject("result").getJSONObject("protocol").getInt("major"))
            val handshakeEnd = SystemClock.elapsedRealtimeNanos()

            val warm = JSONArray()
            repeat(5) {
                val start = SystemClock.elapsedRealtimeNanos()
                val models = JSONObject(session.request(modelList).decodeToString())
                warm.put(SystemClock.elapsedRealtimeNanos() - start)
                assertEquals("phonecode-test-model", models.getJSONObject("result").getJSONArray("models").getJSONObject(0).getString("id"))
            }

            val cpuStart = Process.getElapsedCpuTime()
            SystemClock.sleep(1000)
            val idleCpuMs = Process.getElapsedCpuTime() - cpuStart
            val appProcesses = context.getSystemService(ActivityManager::class.java)
                .runningAppProcesses
                .count { it.processName.startsWith(context.packageName) }
            val thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
                context.getSystemService(PowerManager::class.java).currentThermalStatus
            } else {
                -1
            }

            val sample = JSONObject()
                .put("load_ns", loadEnd - loadStart)
                .put("open_ns", openEnd - loadEnd)
                .put("handshake_ns", handshakeEnd - openEnd)
                .put("cold_total_ns", handshakeEnd - loadStart)
                .put("warm_model_list_ns", warm)
                .put("idle_cpu_ms_per_second", idleCpuMs)
                .put("idle_pss_kb", Debug.getPss())
                .put("app_process_count", appProcesses)
                .put("thermal_status", thermalStatus)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first())
                .put("device", Build.MODEL)
            File(context.filesDir, "misul-performance-samples.jsonl").appendText(sample.toString() + "\n")
            assertTrue(idleCpuMs >= 0)
        } finally {
            session.close()
        }
    }

    private fun config(root: File) = JSONObject()
        .put("workspace_root", root.resolve("workspace").apply(File::mkdirs).absolutePath)
        .put("state_root", root.resolve("state").apply(File::mkdirs).absolutePath)
        .put("system_prompt", "PhoneCode native performance test")
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
