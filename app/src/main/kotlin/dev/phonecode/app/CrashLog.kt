package dev.phonecode.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal crash capture: uncaught exceptions append a timestamped stack trace to
 * filesDir/crash.log (then the system handler still runs, so the crash dialog/exit is unchanged).
 * Settings > About > Crash log shows and shares the file - turns "the app crashed" into a trace.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256 * 1024L

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { append(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, FILE)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val entry = "=== $stamp · thread ${thread.name} ===\n$trace\n"
        // Keep the newest entries; trim from the front if the log grows past the cap. The cap is
        // enforced in BYTES (review #6 - char-based trimming let multi-byte traces exceed it);
        // decoding a tail that starts mid-character yields a replacement char, which is fine for
        // a diagnostic log.
        val existing = if (file.exists()) file.readBytes() else ByteArray(0)
        val combined = existing + entry.toByteArray(Charsets.UTF_8)
        val trimmed = if (combined.size > MAX_BYTES) {
            combined.copyOfRange(combined.size - MAX_BYTES.toInt(), combined.size)
        } else combined
        file.writeBytes(trimmed)
    }
}
