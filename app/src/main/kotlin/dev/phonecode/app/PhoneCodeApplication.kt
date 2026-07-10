package dev.phonecode.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class PhoneCodeApplication : Application() {
    /**
     * Scope for in-flight agent TURNS. Deliberately application-level: a turn launched in
     * viewModelScope died with the activity (closing the app or locking the phone killed the
     * response mid-stream - device feedback). Here the turn keeps streaming as long as the
     * process lives (the dataSync TurnService holds that lease), persists its session on
     * completion, and the reopened UI restores the finished reply. ChatViewModel.cancel()
     * cancels the individual job; the scope itself lives as long as the process.
     */
    val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        File(filesDir, "crash.log").delete()
    }
}
