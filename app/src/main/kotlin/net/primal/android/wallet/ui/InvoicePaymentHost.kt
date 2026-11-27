package net.primal.android.wallet.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.librenostr.android.R
import java.text.NumberFormat
import net.primal.android.core.errors.walletRefusalMessage
import net.primal.android.theme.AppTheme
import net.primal.android.zaps.AndroidLightningWallet
import net.primal.android.zaps.LightningWalletMissingException

/** Pays a BOLT11 invoice the way this account pays: see [InvoicePaymentViewModel]. */
val LocalInvoicePayer = staticCompositionLocalOf<(String) -> Unit> {
    error("InvoicePaymentHost is not in the composition.")
}

@Composable
fun InvoicePaymentHost(content: @Composable () -> Unit) {
    val viewModel = hiltViewModel<InvoicePaymentViewModel>()
    val context = LocalContext.current

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect -> context.handle(effect) }
    }

    CompositionLocalProvider(LocalInvoicePayer provides viewModel::requestPayment) {
        content()
    }

    val pending by viewModel.state.collectAsState()
    pending?.let {
        ConfirmInvoiceDialog(
            pending = it,
            onConfirm = viewModel::confirm,
            onOtherWallet = viewModel::payWithOtherWallet,
            onDismiss = viewModel::dismiss,
        )
    }
}

private fun Context.handle(effect: InvoicePaymentEffect) {
    val message = when (effect) {
        is InvoicePaymentEffect.OpenExternalWallet ->
            try {
                AndroidLightningWallet(this).payBolt11(effect.invoice)
                null
            } catch (_: LightningWalletMissingException) {
                getString(R.string.wallet_no_lightning_app)
            }

        InvoicePaymentEffect.Paid -> getString(R.string.wallet_invoice_paid)
        is InvoicePaymentEffect.Refused -> walletRefusalMessage(this, effect.code, effect.reason)
        InvoicePaymentEffect.NotSent -> getString(R.string.wallet_payment_not_sent)
        InvoicePaymentEffect.Unconfirmed -> getString(R.string.wallet_payment_unconfirmed)
    }
    message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
}

@Composable
private fun ConfirmInvoiceDialog(
    pending: PendingInvoice,
    onConfirm: () -> Unit,
    onOtherWallet: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = AppTheme.colorScheme.surfaceVariant,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.wallet_confirm_title,
                    NumberFormat.getNumberInstance().format(pending.amountInSats),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pending.description?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (pending.expired) {
                    Text(
                        text = stringResource(R.string.feed_lightning_invoice_expired),
                        color = AppTheme.colorScheme.error,
                    )
                }
                if (pending.paying) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !pending.paying && !pending.expired) {
                Text(text = stringResource(R.string.wallet_confirm_pay))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pending.paying) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(onClick = onOtherWallet, enabled = !pending.paying) {
                Text(text = stringResource(R.string.wallet_confirm_other_app))
            }
        },
    )
}
