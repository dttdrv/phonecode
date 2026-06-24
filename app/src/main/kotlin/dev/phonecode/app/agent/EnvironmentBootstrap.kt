package dev.phonecode.app.agent

import android.content.Context
import java.io.File

/**
 * Bootstraps the agent's userland (round-4: "fix the environment, fully").
 *
 * busybox ships inside the APK as `libbusybox.so` per ABI - the ONLY place Android lets an app
 * exec a binary from (W^X: execve of anything written under app data is denied by SELinux for
 * targetSdk >= 29, which is also why no downloaded binary or package manager can ever run).
 * Each busybox applet is exposed as a SYMLINK in $PREFIX/bin: execve resolves the link to the
 * APK-installed target, which is permitted - the standard `busybox --install -s` layout. Shim
 * scripts would NOT work (a script in app data cannot be exec'd directly, only `sh script`).
 *
 * The result: a real POSIX toolkit (ash, awk, sed, grep, find, tar, gzip, bzip2, diff, patch,
 * wget, vi, ...) layered over Android's toybox, with HOME/TMPDIR/PREFIX set so tools that
 * expect a home directory stop failing.
 */
object EnvironmentBootstrap {
    data class Userland(
        /** argv prefix for the shell tool, e.g. [".../bin/sh", "-c"]. */
        val shell: List<String>,
        /** Environment for every shell invocation: HOME, TMPDIR, PREFIX, PATH, TERM. */
        val env: Map<String, String>,
        /** Installed busybox applet names; empty when busybox is unavailable (fallback toybox). */
        val applets: List<String>,
    )

    fun ensure(context: Context): Userland {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "tmp").apply { mkdirs() }
        val prefix = File(context.filesDir, "usr").apply { mkdirs() }
        val bin = File(prefix, "bin").apply { mkdirs() }
        val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")

        val env = mapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "PREFIX" to prefix.absolutePath,
            "PATH" to bin.absolutePath + ":/system/bin",
            "TERM" to "dumb",
        )

        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)
        val applets = if (busybox.canExecute()) installApplets(busybox, bin, version) else emptyList()
        val shell = if ("sh" in applets) {
            listOf(File(bin, "sh").absolutePath, "-c")
        } else {
            listOf("/system/bin/sh", "-c")
        }
        return Userland(shell, env, applets)
    }

    /** Symlinks every applet into [bin] once per app version; later calls read the marker. */
    private fun installApplets(busybox: File, bin: File, version: Int): List<String> = runCatching {
        val marker = File(bin.parentFile, ".applets-v$version")
        if (marker.isFile) return@runCatching marker.readLines().filter { it.isNotBlank() }

        val process = ProcessBuilder(busybox.absolutePath, "--list").redirectErrorStream(true).start()
        val listed = process.inputStream.bufferedReader().readText().lines()
            .map { it.trim() }.filter { it.matches(Regex("[a-z0-9._\\[\\]-]+")) }
        process.waitFor()
        if (listed.isEmpty()) return@runCatching emptyList()

        listed.forEach { name ->
            val link = File(bin, name)
            if (!link.exists()) {
                runCatching { android.system.Os.symlink(busybox.absolutePath, link.absolutePath) }
            }
        }
        marker.writeText(listed.joinToString("\n"))
        listed
    }.getOrDefault(emptyList())
}
