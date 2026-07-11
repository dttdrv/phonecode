package dev.phonecode.app.agent

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Bootstraps the agent's userland in two tiers.
 *
 * TIER 1 - busybox (always present, zero setup). busybox ships inside the APK as `libbusybox.so`
 * per ABI - the ONLY place Android lets an app exec a binary from (W^X: execve of anything written
 * under app data is denied by SELinux for targetSdk >= 29, which is also why no downloaded binary or
 * package manager can ever run). Each applet is a SYMLINK in $PREFIX/bin resolving to the
 * APK-installed busybox - the standard `busybox --install -s` layout.
 *
 * TIER 2 - a real Linux userland via proot (only when the proot binaries are bundled). proot is a
 * userspace chroot+bind via ptrace: it runs a whole Alpine rootfs reached through mmap (PROT_EXEC
 * mmap survives W^X; only execve is blocked), using a trusted loader that ALSO lives in the APK
 * (jniLibs) so it is exec-permitted. The Alpine rootfs is BUNDLED in assets and extracted once (no
 * network, no failure mode where a download dies mid-flight), so the base Linux is reliable and
 * `apk add python3 py3-pip nodejs ...` works. Until the proot binaries exist or the rootfs finishes
 * extracting, the shell transparently stays on busybox.
 */
object EnvironmentBootstrap {

    /** Bundled Alpine version. Bumping this re-extracts on the next launch (the marker is version-keyed). */
    private const val ALPINE_VERSION = "3.21.7"
    // gzip-compressed tar, named with an opaque extension: AGP gunzips a `.gz` asset (and drops the
    // extension), which would break assets.open(); `.rootfs` is stored verbatim (see noCompress in build.gradle).
    private const val ALPINE_ASSET = "alpine-aarch64.rootfs"

    class Userland internal constructor(
        private val busyboxShell: List<String>,
        /** busybox/host environment: HOME, TMPDIR, PREFIX, PATH, TERM. */
        val env: Map<String, String>,
        /** Installed busybox applet names; empty when busybox is unavailable (fallback toybox). */
        val applets: List<String>,
        private val linux: Linux?,
    ) {
        /** True when the proot binaries are bundled, so a Linux userland CAN be set up on this build/ABI. */
        val linuxAvailable: Boolean get() = linux != null

        /** True once the Alpine rootfs is extracted (the shell runs inside Linux). */
        fun linuxReady(): Boolean = linux?.ready() == true

        /** Extract the bundled rootfs now (idempotent, blocking; call off the main thread to prewarm). */
        fun ensureLinux(): Boolean = linux?.ensure() ?: false

        /**
         * Current shell argv. Returns the proot-wrapped Linux shell once its rootfs is ready, else the
         * busybox shell. Calling it also kicks off the one-time rootfs extract in the background, so the
         * first command runs on busybox and later commands transparently upgrade to Linux.
         */
        fun shell(workspacePath: String): List<String> {
            linux?.kickoffSetup()
            return linux?.takeIf { it.ready() }?.shellArgv(workspacePath) ?: busyboxShell
        }

        /** Environment matching [shell]: proot needs PROOT_* + the guest PATH/HOME; busybox keeps the host env. */
        fun shellEnv(): Map<String, String> = linux?.takeIf { it.ready() }?.env() ?: env
    }

    fun ensure(context: Context): Userland {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "tmp").apply { mkdirs() }
        val prefix = File(context.filesDir, "usr").apply { mkdirs() }
        val bin = File(prefix, "bin").apply { mkdirs() }
        val busyboxLib = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        // busybox is multi-call by argv[0]: this build only behaves as busybox when invoked AS "busybox"
        // (running the binary as "libbusybox.so" errors "applet not found"). So route the direct --list/tar
        // calls through this "busybox"-named symlink. (Applet symlinks like "sh"/"tar" already work because
        // their own argv[0] IS the applet name.)
        val busybox = File(bin, "busybox")
        runCatching { busybox.delete(); android.system.Os.symlink(busyboxLib.absolutePath, busybox.absolutePath) }

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
        val applets = if (busyboxLib.canExecute()) installApplets(busybox, busyboxLib, bin, version) else emptyList()
        val shell = if ("sh" in applets) {
            listOf(File(bin, "sh").absolutePath, "-c")
        } else {
            listOf("/system/bin/sh", "-c")
        }
        return Userland(shell, env, applets, buildLinux(context))
    }

    /** Symlinks every applet into [bin] once per app version; later calls read the marker. [busybox] is the
     *  'busybox'-named symlink (for --list); [busyboxLib] is the real binary (the applet-symlink target). */
    private fun installApplets(busybox: File, busyboxLib: File, bin: File, version: Int): List<String> = runCatching {
        val marker = File(bin.parentFile, ".applets-v$version")
        if (marker.isFile) return@runCatching marker.readLines().filter { it.isNotBlank() }

        val process = ProcessBuilder(busybox.absolutePath, "--list").redirectErrorStream(true).start()
        val listed = process.inputStream.bufferedReader().readText().lines()
            .map { it.trim() }.filter { it.matches(Regex("[a-z0-9._\\[\\]-]+")) }
        process.waitFor()
        if (listed.isEmpty()) return@runCatching emptyList()

        listed.forEach { name ->
            val link = File(bin, name)
            // The per-version marker already gates this loop, so always re-point the link: after an app
            // update the old symlinks target the previous nativeLibraryDir and must be recreated against
            // the current one. Skipping existing links (the old guard) left the shell pointing at a stale path.
            runCatching {
                link.delete()
                android.system.Os.symlink(busyboxLib.absolutePath, link.absolutePath)
            }
        }
        marker.writeText(listed.joinToString("\n"))
        listed
    }.getOrDefault(emptyList())

    /**
     * A [Linux] capability iff the proot binaries are bundled for this ABI (arm64-v8a only, which is the
     * only ABI with bundled proot + the bundled aarch64 rootfs); null = busybox-only on every other build.
     */
    private fun buildLinux(context: Context): Linux? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot-loader.so")
        if (!proot.canExecute() || !loader.exists()) return null
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return null
        return Linux(
            proot = proot,
            loader = loader,
            rootfs = File(context.filesDir, "linux/aarch64"),
            tmpDir = File(context.cacheDir, "proot-tmp"),
            assets = context.assets,
        )
    }

    /**
     * The proot + Alpine tier. [ready] is a cheap marker check; [ensure]/[kickoffSetup] extract the BUNDLED
     * rootfs once (local, no network), so the base Linux is reliable - the old runtime download could die
     * mid-flight (detached thread, flaky network) and leave the agent with no package manager.
     */
    class Linux internal constructor(
        private val proot: File,
        private val loader: File,
        private val rootfs: File,
        private val tmpDir: File,
        private val assets: AssetManager,
    ) {
        // Marker is version-keyed so a newer bundled rootfs (after an app update) re-extracts instead of
        // running against the stale tree.
        private val marker = File(rootfs.parentFile, "${rootfs.name}-$ALPINE_VERSION.ready")
        private val started = AtomicBoolean(false)

        fun ready(): Boolean = marker.isFile

        /** Extract the bundled rootfs once, on a background thread (no-op if ready or already running). */
        fun kickoffSetup() {
            if (ready() || !started.compareAndSet(false, true)) return
            Thread({ runCatching { ensure() } }, "alpine-setup").apply { isDaemon = true }.start()
        }

        /** Idempotent, blocking: extract the bundled Alpine rootfs if not already done. Returns ready state. */
        @Synchronized
        fun ensure(): Boolean {
            if (ready()) return true
            return runCatching { extract() }.getOrDefault(false)
        }

        /**
         * Extract the bundled gzipped tar in PURE KOTLIN. We do NOT shell out to busybox/toybox tar: that
         * process runs in the app's untrusted_app domain whose seccomp filter SIGSYS-kills the metadata
         * syscalls tar uses (timestamps/ownership), so it died with exit 159. This extractor uses only the
         * calls the app already makes (write, mkdir, Os.symlink, chmod), all seccomp-allowed.
         */
        private fun extract(): Boolean {
            rootfs.deleteRecursively()
            rootfs.mkdirs()
            tmpDir.mkdirs()
            GZIPInputStream(assets.open(ALPINE_ASSET).buffered()).use { untar(it, rootfs) }
            if (!File(rootfs, "bin").exists()) {
                rootfs.deleteRecursively()
                return false
            }
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            marker.writeText("ok")
            return true
        }

        /**
         * Minimal ustar extractor: directories, regular files, symlinks. Skips hardlinks and device/fifo
         * nodes (the Alpine minirootfs has none, and proot binds the host /dev anyway). Only regular files
         * carry data blocks; every entry is padded to a 512-byte boundary.
         */
        private fun untar(input: InputStream, dest: File) {
            val header = ByteArray(512)
            while (readFully(input, header)) {
                if (header.all { it.toInt() == 0 }) break // end-of-archive marker
                val name = cString(header, 0, 100)
                if (name.isEmpty()) continue
                val mode = octal(header, 100, 8)
                val size = octal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val target = File(dest, name)
                when (type) {
                    '5' -> target.mkdirs()
                    '2' -> { // symlink: target path is in the linkname field, not data
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching { android.system.Os.symlink(cString(header, 157, 100), target.absolutePath) }
                    }
                    '0', ' ' -> { // regular file
                        target.parentFile?.mkdirs()
                        target.outputStream().use { copyN(input, it, size) }
                        skipN(input, padding(size))
                        if (mode and 0b001_000_000 != 0L) target.setExecutable(true, false)
                    }
                    // '1' hardlink, '3'/'4' device, '6' fifo: no data blocks, skip.
                    else -> Unit
                }
            }
        }

        private fun padding(size: Long): Long = (512 - (size % 512)) % 512

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }

        private fun copyN(input: InputStream, out: java.io.OutputStream, n: Long) {
            val buf = ByteArray(64 * 1024)
            var left = n
            while (left > 0) {
                val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                left -= r
            }
        }

        private fun skipN(input: InputStream, n: Long) {
            var left = n
            val buf = ByteArray(8 * 1024)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
        }

        private fun cString(b: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && b[end].toInt() != 0) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }

        private fun octal(b: ByteArray, off: Int, len: Int): Long =
            cString(b, off, len).trim().takeIf { it.isNotEmpty() }?.toLongOrNull(8) ?: 0L

        /** proot argv ending in `/bin/sh -c`; ShellTool appends the command string. */
        fun shellArgv(workspacePath: String): List<String> {
            val workspace = File(workspacePath).absolutePath
            return listOf(
                proot.absolutePath,
                "-r", rootfs.absolutePath,
                "-0",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/data",
                "-b", "${tmpDir.absolutePath}:/tmp",
                "-w", workspace,
                "/bin/sh", "-c",
            )
        }

        fun env(): Map<String, String> = mapOf(
            "PROOT_LOADER" to loader.absolutePath, // the trusted, exec-permitted loader in jniLibs
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "HOME" to "/root",
            "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
        )
    }
}
