package dev.phonecode.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.phonecode.app.ui.PhoneCodeApp

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
        // Ask BEFOREHAND (device feedback): the "PhoneCode is working" foreground notification -
        // the thing that keeps responses streaming with the screen off - needs this on 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { PhoneCodeApp() }
    }
}
