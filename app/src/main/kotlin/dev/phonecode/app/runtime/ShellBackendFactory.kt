package dev.phonecode.app.runtime

import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.EnvironmentBootstrap
import dev.phonecode.tools.shell.LocalShellBackend
import dev.phonecode.tools.shell.ShellBackend
import dev.phonecode.tools.shell.ShellBackendStatus
import dev.phonecode.tools.shell.UnavailableShellBackend
import java.io.File

internal object ShellBackendFactory {
    private const val RELEASE_UNAVAILABLE =
        "The isolated VM runtime is not available in this release build."

    fun create(
        app: PhoneCodeApplication,
        debugRuntimeEnabled: Boolean,
    ): ShellBackend {
        if (!debugRuntimeEnabled) return UnavailableShellBackend(RELEASE_UNAVAILABLE)

        val userland = EnvironmentBootstrap.ensure(app)
        return LocalShellBackend(
            shellProvider = { workspacePath ->
                userland.ensureLinux()
                userland.shell(workspacePath)
            },
            environmentProvider = userland::shellEnv,
            onStarted = { app.foregroundLeases.acquire("process:$it") },
            onStopped = { app.foregroundLeases.release("process:$it") },
            storageDirectory = File(app.filesDir, "processes"),
            statusProvider = {
                val ready = userland.ensureLinux()
                ShellBackendStatus(
                    available = ready,
                    detail = if (ready) {
                        "bundled Alpine Linux compatibility prototype; the workspace is /workspace; " +
                            "use only bundled commands and do not download executable packages"
                    } else {
                        "The bundled Alpine environment could not be prepared."
                    },
                )
            },
        )
    }
}
