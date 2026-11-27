package net.primal.android.settings.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.librenostr.android.R
import java.text.NumberFormat
import kotlinx.coroutines.launch
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.errors.walletRefusalMessage
import net.primal.android.scanner.QrCodeScanner
import net.primal.android.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSettingsScreen(
    viewModel: WalletSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsState()

    if (embedded) {
        WalletSettingsContent(state = state, viewModel = viewModel, modifier = Modifier)
    } else {
        PrimalScaffold(
            topBar = {
                PrimalTopAppBar(
                    title = stringResource(id = R.string.settings_wallet_title),
                    navigationIcon = PrimalIcons.ArrowBack,
                    navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                    onNavigationIconClick = onClose,
                )
            },
            content = { padding ->
                WalletSettingsContent(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .padding(padding)
                        .imePadding(),
                )
            },
        )
    }
}

@Composable
private fun WalletSettingsContent(
    state: WalletSettingsState,
    viewModel: WalletSettingsViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val wallet = state.connected
        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            wallet == null -> ConnectWallet(state = state, viewModel = viewModel)
            else -> ConnectedWalletDetails(wallet = wallet, viewModel = viewModel)
        }
    }
}

@Composable
private fun ConnectWallet(state: WalletSettingsState, viewModel: WalletSettingsViewModel) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }

    Explanation(text = stringResource(R.string.wallet_connect_description))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = state.input,
        onValueChange = viewModel::onInputChanged,
        label = { Text(stringResource(R.string.wallet_connection_string)) },
        placeholder = { Text("nostr+walletconnect://…") },
        // It contains the key that spends from the wallet.
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = !state.connecting,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = !state.connecting,
            onClick = {
                scope.launch {
                    clipboard.getClipEntry()?.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString()
                        ?.let(viewModel::onInputChanged)
                }
            },
        ) { Text(stringResource(R.string.wallet_paste)) }
        TextButton(enabled = !state.connecting, onClick = { scanning = !scanning }) {
            Text(stringResource(if (scanning) R.string.wallet_scan_stop else R.string.wallet_scan))
        }
    }
    if (scanning) {
        QrCodeScanner(
            modifier = Modifier.fillMaxWidth(),
            cameraVisible = true,
            onQrCodeDetected = { result ->
                if (result.value.startsWith("nostr", ignoreCase = true) && "walletconnect" in result.value) {
                    scanning = false
                    viewModel.onInputChanged(result.value)
                }
            },
            hint = { Explanation(text = stringResource(R.string.wallet_scan_hint)) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = viewModel::connect, enabled = state.input.isNotBlank() && !state.connecting) {
            Text(stringResource(R.string.wallet_connect_button))
        }
        if (state.connecting) CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
    state.error?.let { error ->
        Text(
            text = when (error) {
                WalletSettingsError.InvalidString -> stringResource(R.string.wallet_error_invalid_string)
                WalletSettingsError.NoAnswer -> stringResource(R.string.wallet_error_no_answer)
                is WalletSettingsError.Refused -> walletRefusalMessage(
                    context = androidx.compose.ui.platform.LocalContext.current,
                    code = error.code,
                    reason = error.reason,
                )
            },
            color = AppTheme.colorScheme.error,
            style = AppTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnectedWalletDetails(wallet: ConnectedWallet, viewModel: WalletSettingsViewModel) {
    var confirmDisconnect by remember { mutableStateOf(false) }

    Text(
        text = wallet.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.wallet_connected),
        style = AppTheme.typography.titleLarge,
        color = AppTheme.colorScheme.onSurface,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            modifier = Modifier.weight(1f),
            text = when {
                wallet.loadingDetails -> stringResource(R.string.wallet_balance_loading)
                wallet.balanceInSats != null -> stringResource(
                    R.string.wallet_balance,
                    NumberFormat.getNumberInstance().format(wallet.balanceInSats),
                )
                else -> stringResource(R.string.wallet_balance_unavailable)
            },
            style = AppTheme.typography.bodyLarge,
            color = AppTheme.colorScheme.onSurface,
        )
        TextButton(onClick = viewModel::refresh, enabled = !wallet.loadingDetails) {
            Text(stringResource(R.string.wallet_refresh))
        }
    }
    Explanation(text = stringResource(R.string.wallet_relay, wallet.relay))
    wallet.lightningAddress?.let { Explanation(text = it) }
    Explanation(text = stringResource(R.string.wallet_connected_description))
    OutlinedButton(onClick = { confirmDisconnect = true }) {
        Text(stringResource(R.string.wallet_disconnect))
    }

    if (confirmDisconnect) {
        AlertDialog(
            containerColor = AppTheme.colorScheme.surfaceVariant,
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.wallet_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.wallet_disconnect_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        viewModel.disconnect()
                    },
                ) { Text(stringResource(R.string.wallet_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
    )
}
