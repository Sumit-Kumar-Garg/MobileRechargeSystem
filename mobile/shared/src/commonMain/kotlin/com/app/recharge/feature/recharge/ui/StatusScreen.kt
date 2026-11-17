package com.app.recharge.feature.recharge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.app.recharge.core.ui.AppScaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.recharge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.app.recharge.core.ui.ErrorState
import com.app.recharge.core.ui.LoadingState
import com.app.recharge.feature.recharge.domain.Order
import com.app.recharge.feature.recharge.domain.OrderStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun StatusRoute(onNewRecharge: () -> Unit, viewModel: StatusViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatusScreen(state = state, onRetry = viewModel::retry, onNewRecharge = onNewRecharge)
}

@Composable
fun StatusScreen(state: StatusUiState, onRetry: () -> Unit, onNewRecharge: () -> Unit) {
    AppScaffold(stringResource(Res.string.status_title)) { padding ->
        val order = state.order
        when {
            order == null && state.error != null -> ErrorState(state.error, onRetry, Modifier.padding(padding))
            order == null -> LoadingState(Modifier.padding(padding))
            else -> StatusContent(order, onNewRecharge, Modifier.padding(padding))
        }
    }
}

@Composable
private fun StatusContent(order: Order, onNewRecharge: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val settled = order.status.settled
        if (!settled) CircularProgressIndicator()
        Text(
            stringResource(headline(order.status)),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("status_headline"),
        )
        when (order.status) {
            OrderStatus.COMPLETED -> Text(
                stringResource(Res.string.status_completed_detail, order.planName, formatDate(order.validUntil)),
                style = MaterialTheme.typography.bodyLarge,
            )
            OrderStatus.PAYMENT_PENDING -> Text(stringResource(Res.string.status_pending_payment))
            OrderStatus.PROVISIONING_FAILED, OrderStatus.REFUND_INITIATED -> Text(stringResource(Res.string.status_refund))
            else -> Unit
        }
        if (settled) {
            Button(onClick = onNewRecharge, modifier = Modifier.fillMaxWidth().testTag("status_done")) {
                Text(stringResource(Res.string.status_new_recharge))
            }
        }
    }
}

private fun headline(status: OrderStatus): StringResource = when (status) {
    OrderStatus.COMPLETED -> Res.string.status_completed
    OrderStatus.PAYMENT_FAILED -> Res.string.status_failed
    OrderStatus.REFUNDED -> Res.string.status_refunded
    OrderStatus.EXPIRED -> Res.string.status_expired
    OrderStatus.PAYMENT_PENDING -> Res.string.status_pending_payment
    OrderStatus.PROVISIONING_FAILED, OrderStatus.REFUND_INITIATED -> Res.string.status_refund
    else -> Res.string.status_in_progress
}

/** "2026-12-11T10:00:00Z" -> "11.12.2026" in the phone's time zone. Empty when the date cannot be read. */
internal fun formatDate(iso: String?, zone: TimeZone = TimeZone.currentSystemDefault()): String = runCatching {
    val date = Instant.parse(iso!!).toLocalDateTime(zone).date
    "${date.dayOfMonth.toString().padStart(2, '0')}.${date.monthNumber.toString().padStart(2, '0')}.${date.year}"
}.getOrDefault("")
