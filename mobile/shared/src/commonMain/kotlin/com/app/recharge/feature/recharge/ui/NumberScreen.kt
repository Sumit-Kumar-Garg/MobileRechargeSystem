package com.app.recharge.feature.recharge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.app.recharge.core.ui.AppScaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.recharge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.app.recharge.feature.recharge.domain.LinkedNumber

@Composable
fun NumberRoute(onLinked: (LinkedNumber) -> Unit, viewModel: NumberViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.linked) {
        state.linked?.let {
            onLinked(it)
            viewModel.consumeLinked()
        }
    }
    NumberScreen(state = state, onSubmit = viewModel::submit)
}

/** Stateless: the text lives in rememberSaveable so it survives rotation and process death. */
@Composable
fun NumberScreen(state: NumberUiState, onSubmit: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    AppScaffold(stringResource(Res.string.number_title)) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(Res.string.number_hint)) },
                supportingText = {
                    val error = state.error
                    if (error != null) {
                        Text(stringResource(error.messageRes()), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(Res.string.number_supporting))
                    }
                },
                isError = state.error != null,
                singleLine = true,
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().testTag("number_input"),
            )
            Button(
                onClick = { onSubmit(input) },
                enabled = !state.loading && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("number_continue"),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.number_continue))
            }
        }
    }
}

private fun NumberError.messageRes(): StringResource = when (this) {
    NumberError.INVALID -> Res.string.number_error_invalid
    NumberError.UNSUPPORTED -> Res.string.number_error_unsupported
    NumberError.LIMIT -> Res.string.number_error_limit
    NumberError.OFFLINE -> Res.string.error_offline
    NumberError.GENERIC -> Res.string.error_generic
}
