package net.primal.android.wallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.acinq.lightning.payment.Bolt11Invoice
import io.github.aakira.napier.Napier
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.wallet.nwc.NwcRepository
import net.primal.core.networking.nwc.NwcNoReply
import net.primal.core.networking.nwc.NwcNotSent
import net.primal.core.networking.nwc.NwcWalletError
import net.primal.core.utils.MSATS_IN_SATS

/** An invoice waiting for the user to say whether the connected wallet should pay it. */
data class PendingInvoice(
    val userId: String,
    val invoice: String,
    val amountInSats: Long,
    val description: String?,
    val expired: Boolean,
    val paying: Boolean = false,
)

sealed class InvoicePaymentEffect {
    data class OpenExternalWallet(val invoice: String) : InvoicePaymentEffect()
    data object Paid : InvoicePaymentEffect()
    data class Refused(val code: String, val reason: String) : InvoicePaymentEffect()
    data object NotSent : InvoicePaymentEffect()
    data object Unconfirmed : InvoicePaymentEffect()
}

/**
 * Decides what a "Pay" tap does. Without a connected wallet, or for an invoice this cannot price,
 * it goes to the phone's Lightning app as before. Otherwise the amount is shown and nothing is paid
 * until the user confirms: an invoice can come from anyone's note.
 */
@HiltViewModel
class InvoicePaymentViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val nwcRepository: NwcRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PendingInvoice?>(null)
    val state = _state.asStateFlow()

    private val _effects = Channel<InvoicePaymentEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun requestPayment(invoice: String) {
        viewModelScope.launch {
            if (_state.value != null) return@launch
            val userId = activeAccountStore.activeUserId()
            val parsed = Bolt11Invoice.read(invoice.removePrefix("lightning:"))
                .takeIf { it.isSuccess }?.get()
            val amountMsats = parsed?.amount?.msat
            if (nwcRepository.findConnection(userId) == null || parsed == null || amountMsats == null) {
                _effects.send(InvoicePaymentEffect.OpenExternalWallet(invoice))
                return@launch
            }
            _state.value = PendingInvoice(
                userId = userId,
                invoice = invoice,
                // Rounded up: the dialog must never promise less than what leaves the wallet.
                amountInSats = (amountMsats + MSATS_IN_SATS - 1) / MSATS_IN_SATS,
                description = parsed.description,
                expired = parsed.isExpired(currentTimestampSeconds = Instant.now().epochSecond),
            )
        }
    }

    fun dismiss() {
        _state.update { if (it?.paying == true) it else null }
    }

    fun payWithOtherWallet() {
        val pending = _state.value ?: return
        if (pending.paying) return
        _state.value = null
        viewModelScope.launch { _effects.send(InvoicePaymentEffect.OpenExternalWallet(pending.invoice)) }
    }

    fun confirm() {
        val pending = _state.value ?: return
        if (pending.paying || pending.expired) return
        _state.value = pending.copy(paying = true)
        viewModelScope.launch {
            val outcome = try {
                nwcRepository.payInvoice(userId = pending.userId, invoice = pending.invoice)
                InvoicePaymentEffect.Paid
            } catch (error: NwcWalletError) {
                InvoicePaymentEffect.Refused(error.code, error.message.orEmpty())
            } catch (error: NwcNotSent) {
                Napier.w(throwable = error) { "Invoice payment was not sent." }
                InvoicePaymentEffect.NotSent
            } catch (error: NwcNoReply) {
                Napier.w(throwable = error) { "Invoice payment got no reply." }
                InvoicePaymentEffect.Unconfirmed
            }
            _state.value = null
            _effects.send(outcome)
        }
    }
}
