package net.primal.android.zaps

import android.content.Context
import android.widget.Toast
import com.librenostr.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import net.primal.android.networking.relays.FALLBACK_RELAYS
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.domain.mapToRelayDO
import net.primal.android.user.repository.RelayRepository
import net.primal.android.wallet.nwc.NwcRepository
import net.primal.core.lightning.LightningPayHelper
import net.primal.core.networking.nwc.NwcNoReply
import net.primal.core.networking.nwc.NwcNotSent
import net.primal.core.networking.nwc.NwcWalletError
import net.primal.core.utils.MSATS_IN_SATS
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.cryptography.utils.getOrNull
import net.primal.domain.nostr.zaps.ZapError
import net.primal.domain.nostr.zaps.ZapResult
import net.primal.domain.nostr.zaps.ZapTarget
import net.primal.domain.notifications.DEFAULT_ZAP_DEFAULT

/**
 * Sends a NIP-57 zap. With a wallet connected over Nostr Wallet Connect the invoice is paid right
 * here; otherwise it is handed to whichever Lightning app the phone has, which asks for confirmation.
 */
@Singleton
@Suppress("LongParameterList")
class ZapHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val relayRepository: RelayRepository,
    private val notary: NostrNotary,
    private val lightningPayHelper: LightningPayHelper,
    private val androidLightningWallet: AndroidLightningWallet,
    private val nwcRepository: NwcRepository,
    private val accountsStore: UserAccountsStore,
) {

    /** Zaps being sent now, so a double tap does not pay the same thing twice. */
    private val inFlight = ConcurrentHashMap.newKeySet<List<Any?>>()

    suspend fun zap(
        userId: String,
        target: ZapTarget,
        amountInSats: ULong? = null,
        comment: String? = null,
        optionalTags: List<JsonArray> = emptyList(),
        @Suppress("UNUSED_PARAMETER") walletId: String = "",
    ): ZapResult {
        // Only an identical zap counts as a repeat: another amount or comment is meant.
        val key = listOf(userId, target, amountInSats, comment)
        if (!inFlight.add(key)) return ZapResult.Success
        return try {
            send(userId, target, amountInSats, comment, optionalTags)
        } finally {
            inFlight.remove(key)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(
        userId: String,
        target: ZapTarget,
        amountInSats: ULong?,
        comment: String?,
        optionalTags: List<JsonArray>,
    ): ZapResult =
        withContext(dispatcherProvider.io()) {
            val zapDefault = accountsStore.findByIdOrNull(userId)?.appSettings?.zapDefault ?: DEFAULT_ZAP_DEFAULT
            val zapComment = comment ?: zapDefault.message
            val zapAmountInSats = amountInSats ?: zapDefault.amount.toULong()

            val userRelays = relayRepository.findRelays(userId, RelayKind.UserRelay)
                .map { it.mapToRelayDO() }
                .ifEmpty { FALLBACK_RELAYS }

            val zapRequestEvent = notary.signZapRequestNostrEvent(
                userId = userId,
                comment = zapComment,
                target = target,
                relays = userRelays,
                optionalTags = optionalTags,
            ).getOrNull() ?: return@withContext ZapResult.Failure(error = ZapError.FailedToSignEvent)

            try {
                // The amount of the returned invoice is checked against the one asked for.
                val invoice = withTimeout(30.seconds) {
                    val payRequest = lightningPayHelper.fetchPayRequest(target.recipientLnUrlDecoded)
                    lightningPayHelper.fetchInvoice(
                        payRequest = payRequest,
                        amountInMilliSats = zapAmountInSats * MSATS_IN_SATS.toULong(),
                        comment = zapComment,
                        zapEvent = zapRequestEvent.takeIf { payRequest.allowsNostr == true },
                    )
                }
                pay(
                    userId = userId,
                    invoice = invoice.invoice,
                    // The profile screen reports its own zaps.
                    announceSats = zapAmountInSats.takeIf { target !is ZapTarget.Profile },
                )
                ZapResult.Success
            } catch (error: TimeoutCancellationException) {
                ZapResult.Failure(error = ZapError.Timeout(error))
            } catch (error: NwcWalletError) {
                ZapResult.Failure(error = ZapError.WalletRefused(error.code, error.message.orEmpty()))
            } catch (error: NwcNotSent) {
                ZapResult.Failure(error = ZapError.FailedToPayZap(error))
            } catch (_: NwcNoReply) {
                ZapResult.Failure(error = ZapError.PaymentUnconfirmed)
            } catch (error: LightningWalletMissingException) {
                ZapResult.Failure(error = ZapError.FailedToPayZap(error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ZapResult.Failure(error = ZapError.Unknown(error))
            }
        }

    private suspend fun pay(userId: String, invoice: String, announceSats: ULong?) {
        if (nwcRepository.findConnection(userId) == null) {
            androidLightningWallet.payBolt11(invoice)
            return
        }
        // From here on the money may move, so the outcome is waited for even if the screen closes.
        withContext(NonCancellable) {
            nwcRepository.payInvoice(userId = userId, invoice = invoice)
            if (announceSats != null) {
                withContext(dispatcherProvider.main()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.wallet_zap_sent, announceSats.toLong()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}
