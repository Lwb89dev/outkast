package net.primal.android.wallet.nwc

import androidx.datastore.core.DataStore
import io.github.aakira.napier.Napier
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.primal.core.networking.nwc.NwcClient
import net.primal.core.networking.nwc.NwcNotSent
import net.primal.core.networking.nwc.NwcUri
import net.primal.core.networking.nwc.NwcWalletError
import net.primal.core.networking.nwc.nip47.GetInfoResponsePayload
import net.primal.core.networking.nwc.nip47.PayInvoiceResponsePayload
import net.primal.core.nips.encryption.service.NostrEncryptionService
import net.primal.core.utils.MSATS_IN_SATS
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.domain.wallet.NostrWalletConnect

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NwcConnectionsStore

/**
 * The external wallet each account has connected over Nostr Wallet Connect.
 *
 * The connection string holds a spending key, so it is kept encrypted with the Android Keystore
 * like the account credentials, one per account, and removed when the account logs out.
 */
@Singleton
class NwcRepository @Inject constructor(
    @NwcConnectionsStore private val persistence: DataStore<Map<String, String>>,
    private val encryptionService: NostrEncryptionService,
    dispatcherProvider: DispatcherProvider,
) {

    private val scope = CoroutineScope(dispatcherProvider.io() + SupervisorJob())

    /** One client per connection string, so what was negotiated with the wallet is remembered. */
    private val clients = ConcurrentHashMap<String, NwcClient>()

    fun observeConnection(userId: String): Flow<NostrWalletConnect?> =
        persistence.data
            .map { it[userId]?.toConnectionOrNull() }
            .distinctUntilChanged()

    suspend fun findConnection(userId: String): NostrWalletConnect? =
        persistence.data.first()[userId]?.toConnectionOrNull()

    /**
     * Saves the connection only after the wallet has answered something, so a mistyped or revoked
     * string is refused here and not on the first zap. An error answer counts: some wallets do not
     * allow `get_info` and are otherwise fine.
     *
     * @throws IllegalArgumentException if [uri] is not a usable connection string.
     * @throws net.primal.core.networking.nwc.NwcFailure if the wallet did not answer.
     */
    suspend fun connect(userId: String, uri: String): GetInfoResponsePayload? {
        val trimmed = uri.trim()
        val client = newClient(NwcUri.parse(trimmed))
        val info = try {
            client.getInfo()
        } catch (error: NwcWalletError) {
            if (error.code == UNAUTHORIZED) throw error
            Napier.i { "Wallet answered get_info with ${error.code}." }
            null
        }
        persistence.updateData { it + (userId to trimmed) }
        clients[trimmed] = client
        return info
    }

    suspend fun disconnect(userId: String) {
        persistence.updateData { current ->
            current[userId]?.let(clients::remove)
            current - userId
        }
    }

    suspend fun getInfo(userId: String): GetInfoResponsePayload = clientFor(userId).getInfo()

    suspend fun getBalanceInSats(userId: String): Long = clientFor(userId).getBalance().balance / MSATS_IN_SATS

    /**
     * Pays [invoice] from the account's wallet. The payment runs in this repository's own scope:
     * leaving the screen that asked for it must not abandon a request that is already on its way.
     */
    suspend fun payInvoice(userId: String, invoice: String): PayInvoiceResponsePayload {
        val client = clientFor(userId)
        return scope.async { client.payInvoice(invoice.removePrefix("lightning:")) }.await()
    }

    private suspend fun clientFor(userId: String): NwcClient {
        val uri = persistence.data.first()[userId] ?: throw NwcNotSent("No wallet is connected.")
        return clients.getOrPut(uri) { newClient(NwcUri.parse(uri)) }
    }

    private fun newClient(connection: NostrWalletConnect) =
        NwcClient(connection = connection, encryptionService = encryptionService)

    private fun String.toConnectionOrNull(): NostrWalletConnect? = runCatching { NwcUri.parse(this) }.getOrNull()

    private companion object {
        const val UNAUTHORIZED = "UNAUTHORIZED"
    }
}
