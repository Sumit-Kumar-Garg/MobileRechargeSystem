package com.app.recharge.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.recharge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.NetworkState

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorState(error: ApiError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(error.messageRes()), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(Res.string.action_retry))
        }
    }
}

/** Offline and slow-connection notice shown above the content. */
@Composable
fun NetworkBanner(state: NetworkState, modifier: Modifier = Modifier) {
    val message: StringResource = when {
        !state.connected -> Res.string.banner_offline
        state.isSlow -> Res.string.banner_slow
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(message),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

fun ApiError.messageRes(): StringResource = when (kind) {
    ApiErrorKind.OFFLINE -> Res.string.error_offline
    ApiErrorKind.TIMEOUT -> Res.string.error_timeout
    else -> Res.string.error_generic
}

/**
 * Screen frame with a title bar. Window insets are applied once at the app root (see RechargeApp), so they
 * are switched off here; otherwise iOS would pad the status bar area twice.
 */
@Composable
fun AppScaffold(title: String, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, windowInsets = WindowInsets(0, 0, 0, 0)) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}
