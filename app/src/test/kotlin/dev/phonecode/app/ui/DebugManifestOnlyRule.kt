package dev.phonecode.app.ui

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal fun debugManifestOnlyRule(): TestRule = TestRule { base, _: Description ->
    object : Statement() {
        override fun evaluate() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val hostActivityPresent = runCatching {
                context.packageManager.getActivityInfo(
                    ComponentName(context, "androidx.activity.ComponentActivity"),
                    0,
                )
            }.isSuccess
            Assume.assumeTrue(
                "ui-test-manifest activity absent (release variant)",
                hostActivityPresent,
            )
            base.evaluate()
        }
    }
}
