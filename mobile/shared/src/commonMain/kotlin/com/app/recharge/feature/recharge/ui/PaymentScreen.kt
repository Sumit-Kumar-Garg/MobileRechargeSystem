package com.app.recharge.feature.recharge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.app.recharge.core.ui.AppScaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.recharge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.ui.messageRes
import com.app.recharge.LocalAppGraph
import kotlin.uuid.Uuid

/** Mock payment methods. Each one makes the mock payment provider behave differently. */
private enum class DemoMethod(val token: String, val label: StringResource) {
    APPROVED("tok_success", Res.string.pay_method_success),
    DECLINED("tok_decline", Res.string.pay_method_decline),
    TIMEOUT("tok_timeout", Res.string.pay_method_timeout),
}

@Composable
fun PaymentRoute(
    planName: String,
    amountMinor: Int,
    currency: String,
    masked: String,
    onDone: (orderId: String) -> Unit,
    viewModel: PaymentViewModel,
) {
    // Saved state: the key survives rotation and process death, so a retry can never create a second order.
    val idempotencyKey = rememberSaveable { Uuid.random().toString().replace("-", "") }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        (state as? PaymentUiState.Done)?.let {
            onDone(it.orderId)
            viewModel.consumeDone()
        }
    }
    PaymentScreen(
        summary = stringResource(Res.string.pay_summary, planName, masked),
        amount = LocalAppGraph.current.platform.formatMoney(amountMinor, currency),
        state = state,
        onPay = { token -> viewModel.onPayClicked(token, idempotencyKey) },
    )
}

@Composable
fun PaymentScreen(summary: String, amount: String, state: PaymentUiState, onPay: (String) -> Unit) {
    var method by rememberSaveable { mutableStateOf(DemoMethod.APPROVED.name) }
    val processing = state is PaymentUiState.Processing
    AppScaffold(stringResource(Res.string.pay_title)) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.titleMedium)
            Text(amount, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(Res.string.pay_method), style = MaterialTheme.typography.labelLarge)
            DemoMethod.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = method == option.name,
                        enabled = !processing,
                        role = Role.RadioButton,
                        onClick = { method = option.name },
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = method == option.name, onClick = null, enabled = !processing)
                    Text(stringResource(option.label), Modifier.padding(start = 12.dp))
                }
            }
            when (state) {
                PaymentUiState.Declined -> Text(stringResource(Res.string.pay_declined), color = MaterialTheme.colorScheme.error)
                is PaymentUiState.Failed -> Text(stringResource(state.error.paymentMessageRes()), color = MaterialTheme.colorScheme.error)
                PaymentUiState.Processing -> Text(stringResource(Res.string.pay_processing))
                else -> Unit
            }
            Button(
                onClick = { onPay(DemoMethod.valueOf(method).token) },
                enabled = !processing,
                modifier = Modifier.fillMaxWidth().testTag("pay_button"),
            ) {
                if (processing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.pay_button, amount))
            }
        }
    }
}

private fun com.app.recharge.core.network.ApiError.paymentMessageRes(): StringResource = when {
    code == "PAYMENT_LIMIT_EXCEEDED" || code == "RATE_LIMITED" -> Res.string.pay_error_limit
    code == "ORDER_STATE_INVALID" -> Res.string.pay_error_expired
    kind == ApiErrorKind.OFFLINE || kind == ApiErrorKind.TIMEOUT -> messageRes()
    else -> Res.string.error_generic
}
