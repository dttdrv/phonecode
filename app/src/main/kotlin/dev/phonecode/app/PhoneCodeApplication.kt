package dev.phonecode.app

import android.app.Application
import android.os.Process
import dev.phonecode.app.agent.ChatViewModel
import dev.phonecode.app.agent.TurnService
import dev.phonecode.app.data.TransferBundle
import dev.phonecode.app.runtime.MisulRuntimeController
import dev.phonecode.app.runtime.ShellBackendFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PhoneCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Recover before any lazy store/view-model reads can observe a half-committed import.
        TransferBundle.recoverInterruptedImport(filesDir)
    }

    val foregroundLeases by lazy {
        requireMainUid()
        ForegroundLeaseManager(
            start = { TurnService.start(this) },
            stop = { TurnService.stop(this) },
        )
    }
    val chatViewModel by lazy {
        requireMainUid()
        ChatViewModel(this)
    }
    internal val shellBackend by lazy {
        requireMainUid()
        ShellBackendFactory.create(this, BuildConfig.DEBUG)
    }
    internal val misulRuntimeController by lazy {
        requireMainUid()
        MisulRuntimeController()
    }

    /**
     * Scope for in-flight agent TURNS. Deliberately application-level: a turn launched in
     * viewModelScope died with the activity (closing the app or locking the phone killed the
     * response mid-stream - device feedback). Here the turn keeps streaming as long as the
     * process lives (the dataSync TurnService holds that lease), persists its session on
     * completion, and the reopened UI restores the finished reply. ChatViewModel.cancel()
     * cancels the individual job; the scope itself lives as long as the process.
     */
    val turnScope by lazy {
        requireMainUid()
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun requireMainUid() {
        check(Process.myUid() == applicationInfo.uid)
    }
}
