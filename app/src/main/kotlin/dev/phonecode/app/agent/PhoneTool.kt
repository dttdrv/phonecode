package dev.phonecode.app.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import dev.phonecode.app.R
import dev.phonecode.tools.Tool
import dev.phonecode.tools.ToolContext
import dev.phonecode.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Phone control (device feedback: "extremely sophisticated, well made"). One multiplexed tool -
 * `phone` - whose `action` selects a capability. Design rules:
 *  - mutating = true: EVERY invocation passes the user permission gate first; the summary the
 *    user approves names the exact action and value.
 *  - No dangerous primitives: dialing opens the dialer PREFILLED (never places the call),
 *    app launching uses launcher intents only, there is no accessibility-service puppetry.
 *  - Every action degrades with a precise, model-actionable error ("app 'Spotify' not found;
 *    did you mean: Spotify Lite (com.spotify.lite)").
 */
class PhoneTool(private val app: Application) : Tool {
    override val name = "phone"
    override val description =
        "Control this phone. Actions: open_app (label or package), app_list, open_url, share_text, " +
            "dial (prefills the dialer, never calls), clipboard_set, clipboard_get, notify (title|body), " +
            "device_info (battery/network/storage/RAM), settings_panel (wifi|bluetooth|display|sound|battery|app), " +
            "torch (on|off), vibrate (ms), set_volume (0-100, media stream)."
    override val mutating = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("enum", buildJsonArray {
                    listOf(
                        "open_app", "app_list", "open_url", "share_text", "dial", "clipboard_set",
                        "clipboard_get", "notify", "device_info", "settings_panel", "torch",
                        "vibrate", "set_volume",
                    ).forEach { add(it) }
                })
                put("description", "Which phone capability to invoke")
            }
            putJsonObject("value") {
                put("type", "string")
                put("description", "Action argument: app name/package, URL, text, number, 'title|body' for notify, panel name, on/off for torch")
            }
            putJsonObject("level") {
                put("type", "integer")
                put("description", "Numeric argument: vibration ms (vibrate) or volume percent 0-100 (set_volume)")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override val promptSnippet = "phone - device control: launch apps, share, clipboard, notifications, torch, volume, device info"
    override val promptGuidelines = listOf(
        "phone actions always ask the user first; describe intent in the chat before invoking.",
        "dial only PREFILLS the dialer; the user always places the call themselves.",
    )

    // Main.immediate: system-service calls want the main thread, but when the caller is ALREADY
    // on main (Robolectric runs tests there inside runBlocking) it must run inline - a plain
    // Main dispatch can never land on a blocked main looper (deadlock found by the test suite).
    override suspend fun execute(args: JsonObject, context: ToolContext): ToolResult = withContext(Dispatchers.Main.immediate) {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
            ?: return@withContext ToolResult("phone: missing 'action'", isError = true)
        val value = (args["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val level = (args["level"] as? JsonPrimitive)?.intOrNull
        runCatching {
            when (action) {
                "open_app" -> openApp(value)
                "app_list" -> appList()
                "open_url" -> openUrl(value)
                "share_text" -> shareText(value)
                "dial" -> dial(value)
                "clipboard_set" -> clipboardSet(value)
                "clipboard_get" -> clipboardGet()
                "notify" -> notify(value)
                "device_info" -> deviceInfo()
                "settings_panel" -> settingsPanel(value)
                "torch" -> torch(value)
                "vibrate" -> vibrate(level ?: 200)
                "set_volume" -> setVolume(level ?: return@withContext ToolResult("set_volume: pass 'level' 0-100", isError = true))
                else -> ToolResult("phone: unknown action '$action'", isError = true)
            }
        }.getOrElse { e -> ToolResult("phone $action failed: ${e.message}", isError = true) }
    }

    // ----- launching -----

    private fun launchables(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return app.packageManager.queryIntentActivities(intent, 0).map {
            it.loadLabel(app.packageManager).toString() to it.activityInfo.packageName
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun openApp(query: String): ToolResult {
        if (query.isBlank()) return ToolResult("open_app: pass the app name or package in 'value'", isError = true)
        val direct = app.packageManager.getLaunchIntentForPackage(query)
        val apps = if (direct == null) launchables() else emptyList()
        val match = direct ?: apps.firstOrNull { it.first.equals(query, ignoreCase = true) }
            ?.let { app.packageManager.getLaunchIntentForPackage(it.second) }
        ?: apps.firstOrNull { it.first.contains(query, ignoreCase = true) }
            ?.let { app.packageManager.getLaunchIntentForPackage(it.second) }
        return if (match != null) {
            app.startActivity(match.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult("opened")
        } else {
            val near = apps.filter { it.first.contains(query.take(3), ignoreCase = true) }.take(5)
            val hint = if (near.isEmpty()) "" else " Did you mean: " + near.joinToString { "${it.first} (${it.second})" }
            ToolResult("app '$query' not found.$hint", isError = true)
        }
    }

    private fun appList(): ToolResult {
        val rows = launchables().take(150).joinToString("\n") { "${it.first} (${it.second})" }
        return ToolResult(rows.ifBlank { "(no launchable apps visible)" })
    }

    private fun openUrl(url: String): ToolResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult("open_url: pass an http(s) URL", isError = true)
        }
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("opened $url")
    }

    private fun shareText(text: String): ToolResult {
        if (text.isBlank()) return ToolResult("share_text: pass the text in 'value'", isError = true)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        app.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("share sheet opened")
    }

    private fun dial(number: String): ToolResult {
        if (number.isBlank()) return ToolResult("dial: pass the number in 'value'", isError = true)
        app.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("dialer opened with $number (user places the call)")
    }

    // ----- clipboard / notify -----

    private fun clipboardSet(text: String): ToolResult {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PhoneCode", text))
        return ToolResult("copied ${text.length} chars to the clipboard")
    }

    private fun clipboardGet(): ToolResult {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        return if (text.isNullOrEmpty()) {
            ToolResult("clipboard is empty or unreadable (Android only exposes it to the focused app)", isError = true)
        } else ToolResult(text.take(8_000))
    }

    private fun notify(value: String): ToolResult {
        val title = value.substringBefore('|').ifBlank { "PhoneCode" }
        val body = value.substringAfter('|', "").ifBlank { return ToolResult("notify: pass 'title|body'", isError = true) }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent", "Agent notifications", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            (System.currentTimeMillis() % 10_000).toInt(),
            Notification.Builder(app, "agent")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title).setContentText(body).build(),
        )
        return ToolResult("notification posted")
    }

    // ----- device -----

    private fun deviceInfo(): ToolResult {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val totalGb = stat.totalBytes / 1_000_000_000.0
        val rt = Runtime.getRuntime()
        return ToolResult(
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "battery: $battery%${if (charging) " (charging)" else ""}\n" +
                "network: $net\n" +
                "storage: %.1f GB free of %.1f GB\n".format(freeGb, totalGb) +
                "app memory: ${rt.totalMemory() / 1_048_576} MB used of ${rt.maxMemory() / 1_048_576} MB max",
        )
    }

    private fun settingsPanel(panel: String): ToolResult {
        val action = when (panel.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> return ToolResult("settings_panel: one of wifi|bluetooth|display|sound|battery|app", isError = true)
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (panel.lowercase() == "app") intent.data = Uri.parse("package:${app.packageName}")
        app.startActivity(intent)
        return ToolResult("opened $panel settings")
    }

    private fun torch(state: String): ToolResult {
        val on = when (state.lowercase()) { "on" -> true; "off" -> false; else -> return ToolResult("torch: 'on' or 'off'", isError = true) }
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult("no flashlight on this device", isError = true)
        cm.setTorchMode(id, on)
        return ToolResult("torch ${if (on) "on" else "off"}")
    }

    private fun vibrate(ms: Int): ToolResult {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return ToolResult("no vibrator on this device", isError = true)
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(20, 2_000).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        return ToolResult("vibrated ${ms.coerceIn(20, 2_000)}ms")
    }

    private fun setVolume(percent: Int): ToolResult {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max / 100)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return ToolResult("media volume set to ${percent.coerceIn(0, 100)}% ($target/$max)")
    }
}
