package com.app.recharge.feature.recharge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import com.app.recharge.core.ui.AppScaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.recharge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.app.recharge.core.ui.ErrorState
import com.app.recharge.core.ui.LoadingState
import com.app.recharge.LocalAppGraph
import com.app.recharge.feature.recharge.domain.Plan

@Composable
fun PlansRoute(masked: String, onSelected: (Plan) -> Unit, viewModel: PlansViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlansScreen(
        masked = masked,
        state = state,
        onRetry = viewModel::load,
        onSelect = {
            viewModel.onPlanSelected(it)
            onSelected(it.plan)
        },
    )
}

@Composable
fun PlansScreen(masked: String, state: PlansUiState, onRetry: () -> Unit, onSelect: (PlanItem) -> Unit) {
    AppScaffold(stringResource(Res.string.plans_title)) { padding ->
        when (state) {
            PlansUiState.Loading -> LoadingState(Modifier.padding(padding))
            is PlansUiState.Error -> ErrorState(state.error, onRetry, Modifier.padding(padding))
            is PlansUiState.Content -> if (state.items.isEmpty()) {
                Text(stringResource(Res.string.plans_empty), Modifier.padding(padding).padding(24.dp))
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).testTag("plans_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Text(stringResource(Res.string.plans_for_number, masked), style = MaterialTheme.typography.titleMedium) }
                    items(state.items, key = { it.plan.id }) { PlanCard(it, onSelect) }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(item: PlanItem, onSelect: (PlanItem) -> Unit) {
    val plan = item.plan
    Card(
        modifier = Modifier.fillMaxWidth().testTag("plan_${plan.code}"),
        colors = CardDefaults.cardColors(
            containerColor = if (item.recommended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.recommended) {
                Text(stringResource(Res.string.plan_recommended), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                item.explanation?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("plan_explanation"))
                }
            }
            if (item.cheaperAlternative) {
                Text(stringResource(Res.string.plan_cheaper_alternative), style = MaterialTheme.typography.labelLarge)
            }
            Text(plan.name, style = MaterialTheme.typography.titleLarge)
            Text(LocalAppGraph.current.platform.formatMoney(plan.priceMinor, plan.currency), style = MaterialTheme.typography.headlineSmall)
            Text(pluralStringResource(Res.plurals.plan_valid_days, plan.validityDays, plan.validityDays))
            plan.dataMb?.let { Text(stringResource(Res.string.plan_data_mb, gigabytes(it))) }
            plan.voiceMin?.let { Text(stringResource(Res.string.plan_voice, it)) }
            plan.sms?.let { Text(stringResource(Res.string.plan_sms, it)) }
            plan.benefits.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { onSelect(item) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.plan_select))
            }
        }
    }
}

/** 9500 MB -> "9.5", 10000 MB -> "10". One decimal at most, no platform formatter needed. */
internal fun gigabytes(megabytes: Int): String {
    val tenths = (megabytes + 50) / 100
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
