package com.app.recharge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.ui.ErrorState
import com.app.recharge.core.ui.LoadingState
import com.app.recharge.core.ui.NetworkBanner
import com.app.recharge.core.ui.RechargeTheme
import com.app.recharge.feature.recharge.ui.AppNavHost
import com.app.recharge.resources.*
import org.jetbrains.compose.resources.stringResource

/** Gives screens access to the app's objects (repository, recorder, platform) without passing them down by hand. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph was not provided") }

/**
 * The whole app UI. Android calls this from MainActivity.setContent, iOS from MainViewController:
 * everything below this function is shared.
 */
@Composable
fun RechargeApp(graph: AppGraph) {
    CompositionLocalProvider(LocalAppGraph provides graph) {
        RechargeTheme {
            val viewModel = viewModel { MainViewModel(graph.auth, graph.consent, graph.repository, graph.networkMonitor) }
            val state by viewModel.state.collectAsStateWithLifecycle()

            LifecycleEventEffect(Lifecycle.Event.ON_START) {
                graph.recorder.lifecycle("app_foreground")
                graph.flushTelemetry()                 // also sends events left over from the last run
            }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                graph.recorder.lifecycle("app_background")
            }

            // Surface gives the theme's background and text colour (also in dark mode).
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // Status bar, notch and home indicator are respected once, here, for every screen.
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    NetworkBanner(state.network)
                    when (state.session) {
                        SessionState.SIGNING_IN -> LoadingState()
                        SessionState.FAILED -> ErrorState(ApiError(ApiErrorKind.NETWORK), onRetry = viewModel::signIn)
                        SessionState.READY -> AppNavHost(rememberNavController())
                    }
                }
            }
            if (state.showConsent) {
                ConsentDialog(onSave = viewModel::saveConsent)
            }
        }
    }
}

@Composable
private fun ConsentDialog(onSave: (personalization: Boolean, analytics: Boolean) -> Unit) {
    var personalization by rememberSaveable { mutableStateOf(false) }
    var analytics by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(Res.string.consent_title)) },
        text = {
            Column {
                ConsentRow(stringResource(Res.string.consent_personalization), personalization) { personalization = it }
                ConsentRow(stringResource(Res.string.consent_analytics), analytics) { analytics = it }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(personalization, analytics) }) { Text(stringResource(Res.string.consent_save)) }
        },
    )
}

@Composable
private fun ConsentRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
