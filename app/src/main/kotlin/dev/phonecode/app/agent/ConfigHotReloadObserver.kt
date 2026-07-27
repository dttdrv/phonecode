package dev.phonecode.app.agent

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal class ConfigHotReloadObserver(
    private val scope: CoroutineScope,
    private val directories: () -> List<File>,
    private val onChange: suspend () -> Unit,
    private val debounceMillis: Long = 300,
    private val enabled: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private var observers = emptyList<FileObserver>()
    private var debounceJob: Job? = null
    private var started = false

    fun start() {
        if (!enabled) return
        synchronized(lock) {
            if (started) return@synchronized
            started = true
            rebuild()
        }
    }

    fun restart() {
        if (!enabled) return
        synchronized(lock) {
            if (!started) return@synchronized
            rebuild()
            schedule()
        }
    }

    private fun schedule() = synchronized(lock) {
        if (!started) return@synchronized
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMillis)
            synchronized(lock) { if (started) rebuild() }
            onChange()
        }
    }

    @Suppress("DEPRECATION")
    private fun rebuild() {
        observers.forEach(FileObserver::stopWatching)
        observers = directories().distinctBy { it.absolutePath }.filter { it.isDirectory }.map { directory ->
            object : FileObserver(directory.absolutePath, EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    schedule()
                }
            }.also(FileObserver::startWatching)
        }
    }

    override fun close() = synchronized(lock) {
        started = false
        debounceJob?.cancel()
        debounceJob = null
        observers.forEach(FileObserver::stopWatching)
        observers = emptyList()
    }

    private companion object {
        const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
