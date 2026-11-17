package com.app.recharge.android

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.app.recharge.AppGraph
import com.app.recharge.RechargeApp
import com.app.recharge.core.telemetry.ConfigDiff
import com.app.recharge.core.telemetry.ConfigSnapshot
import com.app.recharge.core.telemetry.WindowBuckets
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/** Hosts the shared UI. Everything Android-only that the screens do not need to know about lives here. */
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as RechargeApplication).graph

        reportStartAndConfigChanges(graph, restored = savedInstanceState != null)
        if (savedInstanceState == null) {
            askForNotificationsOnce()
            registerForPush(graph)
        }
        setContent { RechargeApp(graph) }
    }

    /** Cold start time once per process, and what changed (rotation, dark mode, ...) when the activity is recreated. */
    private fun reportStartAndConfigChanges(graph: AppGraph, restored: Boolean) {
        val config = snapshot(resources.configuration)
        val previous = lastConfig
        if (previous == null) {
            graph.recorder.lifecycle(
                "app_start",
                mapOf("coldStartMs" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
            )
        } else {
            val changed = ConfigDiff.diff(previous, config)
            if (changed.isNotEmpty()) {
                graph.recorder.configChange(
                    "config_changed",
                    mapOf("changed" to changed.joinToString(","), "stateRestored" to restored),
                )
            }
        }
        lastConfig = config
    }

    private fun snapshot(c: Configuration) = ConfigSnapshot(
        orientation = if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
        locale = c.locales.get(0)?.toLanguageTag() ?: "und",
        darkMode = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        fontScale = c.fontScale,
        windowSize = WindowBuckets.of(c.screenWidthDp),
    )

    /** If the user says no, SMS and email receipts still arrive. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Only when Firebase is configured (google-services.json present); otherwise push is simply skipped. */
    private fun registerForPush(graph: AppGraph) {
        if (FirebaseApp.getApps(this).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> graph.registerPushToken(token) }
    }

    private companion object {
        /** Survives activity recreation (not process death), which is exactly what a config change is. */
        var lastConfig: ConfigSnapshot? = null
    }
}
