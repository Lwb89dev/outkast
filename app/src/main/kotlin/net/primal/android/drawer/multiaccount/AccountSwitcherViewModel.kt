package net.primal.android.drawer.multiaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.primal.android.drawer.multiaccount.model.UserAccountUi
import net.primal.android.drawer.multiaccount.model.asUserAccountUi
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.domain.UserAccount
import net.primal.android.user.repository.UserRepository

@HiltViewModel
class AccountSwitcherViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val activeAccountStore: ActiveAccountStore,
    private val accountsStore: UserAccountsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountSwitcherContract.UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: AccountSwitcherContract.UiState.() -> AccountSwitcherContract.UiState) =
        _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<AccountSwitcherContract.UiEvent> = MutableSharedFlow()
    fun setEvent(event: AccountSwitcherContract.UiEvent) = viewModelScope.launch { events.emit(event) }

    private val _effects = Channel<AccountSwitcherContract.SideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: AccountSwitcherContract.SideEffect) = viewModelScope.launch { _effects.send(effect) }

    init {
        observeEvents()
        observeAccounts()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is AccountSwitcherContract.UiEvent.SwitchAccount -> setActiveAccount(userId = it.userId)
                }
            }
        }

    private fun setActiveAccount(userId: String) =
        viewModelScope.launch {
            if (userId == activeAccountStore.activeUserId()) return@launch
            userRepository.setActiveAccount(userId = userId)
            setEffect(AccountSwitcherContract.SideEffect.AccountSwitched)
        }

    private fun observeAccounts() =
        viewModelScope.launch {
            combine(
                activeAccountStore.activeUserAccount,
                accountsStore.userAccounts,
            ) { activeAccount, accounts ->
                val resolvedActive = activeAccount.takeUnless { it == UserAccount.EMPTY }?.asUserAccountUi()
                val otherAccounts = accounts
                    .map { it.asUserAccountUi() }
                    .sortAndFilterAccounts(activeUserId = resolvedActive?.pubkey)
                resolvedActive to otherAccounts
            }.collect { (activeAccount, otherAccounts) ->
                setState {
                    copy(
                        activeAccount = activeAccount,
                        userAccounts = otherAccounts,
                    )
                }
            }
        }

    private fun List<UserAccountUi>.sortAndFilterAccounts(activeUserId: String?) =
        sortedByDescending { it.lastAccessedAt }
            .filterNot { it.pubkey == activeUserId }
}
