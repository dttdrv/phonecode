package dev.phonecode.app.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test

class ConfigHotReloadObserverTest {

    @Test
    fun disabledObserverNeverTouchesPlatformFileWatching() {
        val observer = ConfigHotReloadObserver(
            scope = CoroutineScope(Job()),
            directories = { error("Disabled observers must not inspect directories") },
            onChange = { error("Disabled observers must not dispatch changes") },
            enabled = false,
        )

        observer.start()
        observer.restart()
        observer.close()
    }
}
