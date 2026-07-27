package dev.phonecode.app.runtime

import dev.phonecode.app.PhoneCodeApplication
import dev.phonecode.app.agent.EnvironmentBootstrap
import dev.phonecode.tools.shell.LocalShellBackend
import dev.phonecode.tools.shell.ShellBackend
import dev.phonecode.tools.shell.ShellBackendStatus
import dev.phonecode.tools.shell.UnavailableShellBackend
import java.io.File

internal object ShellBackendFactory {
    fun create(
        app: PhoneCodeApplication,
        debugRuntimeEnabled: Boolean,
    ): ShellBackend {
        if (!debugRuntimeEnabled) {
            val artifactStore = VmArtifactStore.from(app)
            return createRelease(artifactStore) { verifiedStore ->
                IsolatedVmShellBackend.create(
                    context = app,
                    artifactStore = verifiedStore,
                    acquireForegroundLease = app.foregroundLeases::acquire,
                    releaseForegroundLease = app.foregroundLeases::release,
                )
            }
        }

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

    internal fun createRelease(
        artifactStore: VmArtifactStore,
        isolatedBackendFactory: (VmArtifactStore) -> ShellBackend,
    ): ShellBackend = try {
        artifactStore.verify()
        isolatedBackendFactory(artifactStore)
    } catch (error: VmArtifactException) {
        UnavailableShellBackend(requireNotNull(error.message))
    } catch (error: Throwable) {
        UnavailableShellBackend(
            "Isolated VM runtime verification failed: " +
                (error.message?.take(300) ?: error.javaClass.simpleName) +
                ".",
        )
    }
}
