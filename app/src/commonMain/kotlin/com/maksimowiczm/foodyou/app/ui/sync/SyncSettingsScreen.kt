package com.maksimowiczm.foodyou.app.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maksimowiczm.foodyou.app.ui.common.component.ArrowBackIconButton
import com.maksimowiczm.foodyou.app.ui.common.component.SettingsListItem
import com.maksimowiczm.foodyou.common.compose.extension.add
import com.maksimowiczm.foodyou.common.compose.utility.LocalDateFormatter
import foodyou.app.generated.resources.*
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

internal enum class ConnectionState {
    Idle,
    Testing,
    Success,
    Failure,
}

@Composable
fun SyncSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SyncSettingsViewModel = koinViewModel()
    val model by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(ConnectionState.Idle) }

    SyncSettingsScreen(
        onBack = onBack,
        model = model,
        connectionState = connectionState,
        onServerUrlChange = viewModel::setServerUrl,
        onEnabledChange = viewModel::setEnabled,
        onSaveAndTest = { token ->
            connectionState = ConnectionState.Testing
            scope.launch {
                val success = viewModel.saveAndTestConnection(token)
                connectionState =
                    if (success) ConnectionState.Success else ConnectionState.Failure
            }
        },
        onSyncNow = viewModel::syncNow,
        modifier = modifier,
    )
}

@Composable
private fun SyncSettingsScreen(
    onBack: () -> Unit,
    model: SyncSettingsModel,
    connectionState: ConnectionState,
    onServerUrlChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSaveAndTest: (String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val tokenState = rememberTextFieldState()
    var hideToken by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        modifier = modifier,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(Res.string.headline_self_hosted_sync)) },
                navigationIcon = { ArrowBackIconButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = paddingValues.add(vertical = 8.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.description_self_hosted_sync),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                OutlinedTextField(
                    value = model.serverUrl,
                    onValueChange = onServerUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.headline_sync_server_url)) },
                    placeholder = { Text(stringResource(Res.string.neutral_sync_server_url_example)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                )
            }

            item {
                SecureTextField(
                    state = tokenState,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.headline_sync_token)) },
                    supportingText =
                        if (model.hasToken) {
                            { Text(stringResource(Res.string.neutral_sync_token_saved)) }
                        } else {
                            null
                        },
                    textObfuscationMode =
                        if (hideToken) TextObfuscationMode.RevealLastTyped
                        else TextObfuscationMode.Visible,
                    trailingIcon = {
                        IconButton(
                            onClick = { hideToken = !hideToken },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector =
                                    if (hideToken) Icons.Outlined.Visibility
                                    else Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                )
            }

            item {
                Button(
                    onClick = { onSaveAndTest(tokenState.text.toString()) },
                    enabled =
                        model.serverUrl.isNotBlank() &&
                            connectionState != ConnectionState.Testing,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.action_test_connection))
                }
            }

            item { ConnectionStatus(connectionState) }

            item {
                SettingsListItem(
                    label = { Text(stringResource(Res.string.neutral_enable_sync)) },
                    onClick = { onEnabledChange(!model.enabled) },
                    supportingContent = {
                        Text(stringResource(Res.string.neutral_enable_sync_description))
                    },
                    trailingContent = {
                        Switch(checked = model.enabled, onCheckedChange = onEnabledChange)
                    },
                )
            }

            item {
                Button(
                    onClick = onSyncNow,
                    enabled = model.enabled && !model.syncing,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.action_sync_now))
                }
            }

            if (model.syncing) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            item { LastSyncStatus(model) }
        }
    }
}

@Composable
private fun ConnectionStatus(state: ConnectionState) {
    when (state) {
        ConnectionState.Idle -> Unit
        ConnectionState.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth())
        ConnectionState.Success ->
            Text(
                text = stringResource(Res.string.neutral_sync_connection_successful),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        ConnectionState.Failure ->
            Text(
                text = stringResource(Res.string.error_sync_connection_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

@Composable
private fun LastSyncStatus(model: SyncSettingsModel) {
    val formatter = LocalDateFormatter.current
    when {
        model.lastError != null ->
            Text(
                text = stringResource(Res.string.error_sync_last_error, model.lastError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        model.lastSyncEpochSeconds != null -> {
            val dateTime =
                Instant.fromEpochSeconds(model.lastSyncEpochSeconds)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                text =
                    stringResource(
                        Res.string.neutral_sync_last_synced,
                        formatter.formatDateTime(dateTime),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else ->
            Text(
                text = stringResource(Res.string.neutral_sync_never_synced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
