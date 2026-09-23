package dev.phonecode.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.phonecode.app.ui.PhoneCodeApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // True edge-to-edge: both bars fully transparent, content drawn behind them. Icon
        // appearance (light/dark) is driven by the app theme inside PhoneCodeApp, since the
        // in-app theme mode can differ from the system one.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Drop the system's automatic contrast scrims behind BOTH bars - the status-bar one
            // read as a "weird 50% dark footer" over the v2 blur chrome (device feedback).
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        setContent { PhoneCodeApp() }
        if (Build.VERSION.SDK_INT >= 33) {
            // Ask in context: the first time a turn runs, so its progress can show outside the app.
            // Android stops showing the dialog after repeated denials.
            lifecycleScope.launch {
                (application as PhoneCodeApplication).chatViewModel.state.first { it.isRunning }
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
            }
        }
    }
}
