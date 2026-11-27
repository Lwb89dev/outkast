package net.primal.android.settings.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.wallet.nwc.NwcRepository
import net.primal.core.networking.nwc.NwcFailure
import net.primal.core.networking.nwc.NwcWalletError
import net.primal.core.utils.runCatching

data class WalletSettingsState(
    val loading: Boolean = true,
    val connected: ConnectedWallet? = null,
    val input: String = "",
    val connecting: Boolean = false,
    val error: WalletSettingsError? = null,
)

data class ConnectedWallet(
    val relay: String,
    val lightningAddress: String?,
    val name: String? = null,
    val balanceInSats: Long? = null,
    val loadingDetails: Boolean = true,
    /** Nothing came back at all, which is not the same as a wallet refusing to say. */
    val unreachable: Boolean = false,
)

sealed class WalletSettingsError {
    data object InvalidString : WalletSettingsError()
    data object NoAnswer : WalletSettingsError()
    data class Refused(val code: String, val reason: String) : WalletSettingsError()
}

@HiltViewModel
class WalletSettingsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val nwcRepository: NwcRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletSettingsState())
    val state = _state.asStateFlow()

    private var detailsJob: Job? = null

    init {
        observeConnection()
    }

    fun onInputChanged(value: String) = _state.update { it.copy(input = value, error = null) }

    fun connect() {
        val uri = _state.value.input
        if (uri.isBlank() || _state.value.connecting) return
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            val error = try {
                nwcRepository.connect(userId = activeAccountStore.activeUserId(), uri = uri)
                null
            } catch (_: IllegalArgumentException) {
                WalletSettingsError.InvalidString
            } catch (error: NwcWalletError) {
                WalletSettingsError.Refused(error.code, error.message.orEmpty())
            } catch (error: NwcFailure) {
                Napier.w(throwable = error) { "Wallet connection failed." }
                WalletSettingsError.NoAnswer
            }
            _state.update {
                it.copy(connecting = false, error = error, input = if (error == null) "" else it.input)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { nwcRepository.disconnect(activeAccountStore.activeUserId()) }
    }

    fun refresh() {
        if (_state.value.connected == null) return
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch { loadDetails(activeAccountStore.activeUserId()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConnection() =
        viewModelScope.launch {
            activeAccountStore.activeUserId
                .flatMapLatest { nwcRepository.observeConnection(it) }
                .collectLatest { connection ->
                    detailsJob?.cancel()
                    _state.update { state ->
                        state.copy(
                            loading = false,
                            connected = connection?.let {
                                ConnectedWallet(
                                    relay = it.relays.first().removePrefix("wss://"),
                                    lightningAddress = it.lightningAddress,
                                )
                            },
                        )
                    }
                    refresh()
                }
        }

    private suspend fun loadDetails(userId: String) {
        updateWallet { copy(loadingDetails = true, unreachable = false) }
        // Neither is required: a connection limited to paying refuses both. But a wallet that
        // does not answer get_info will not answer get_balance either, so it is not asked twice.
        val name = try {
            nwcRepository.getInfo(userId).alias
        } catch (_: NwcWalletError) {
            null
        } catch (error: NwcFailure) {
            Napier.w(throwable = error) { "The wallet did not answer get_info." }
            updateWallet { copy(balanceInSats = null, loadingDetails = false, unreachable = true) }
            return
        }
        updateWallet { copy(name = name ?: this.name) }
        val balance = runCatching { nwcRepository.getBalanceInSats(userId) }.getOrNull()
        updateWallet { copy(balanceInSats = balance, loadingDetails = false) }
    }

    private fun updateWallet(change: ConnectedWallet.() -> ConnectedWallet) =
        _state.update { it.copy(connected = it.connected?.change()) }
}
