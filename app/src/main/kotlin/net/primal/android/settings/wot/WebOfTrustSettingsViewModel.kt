package net.primal.android.settings.wot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.primal.android.pow.PowSettings
import net.primal.android.pow.PowSettingsStore
import net.primal.android.settings.wot.WebOfTrustSettingsContract.UiEvent
import net.primal.android.settings.wot.WebOfTrustSettingsContract.UiState
import net.primal.android.spam.TrustNetworkRefresher
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.wot.WebOfTrustRepository

@HiltViewModel
class WebOfTrustSettingsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val webOfTrustRepository: WebOfTrustRepository,
    private val settingsStore: PowSettingsStore,
    private val trustNetworkRefresher: TrustNetworkRefresher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _uiState.getAndUpdate { it.reducer() }

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        observeFilterEnabled()
        observeDiscoveryState()
        observeStrangerSettings()
        observeNetworkSize()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.ToggleFilter -> toggleFilter(enabled = it.enabled)
                    UiEvent.RefreshNetworkClick -> trustNetworkRefresher.refresh()
                    is UiEvent.ToggleHideStrangers -> toggleHideStrangers(enabled = it.enabled)
                    is UiEvent.ChangeNetworkWidth -> updateSettings { copy(networkMinFollowers = it.minFollowers) }
                }
            }
        }

    private fun observeFilterEnabled() =
        viewModelScope.launch {
            webOfTrustRepository.observeFilterEnabled(ownerId = activeAccountStore.activeUserId())
                .collect { enabled -> setState { copy(filterEnabled = enabled) } }
        }

    private fun observeDiscoveryState() =
        viewModelScope.launch {
            webOfTrustRepository.observeDiscoveryState(ownerId = activeAccountStore.activeUserId())
                .collect { discoveryState -> setState { copy(discoveryState = discoveryState) } }
        }

    private fun toggleFilter(enabled: Boolean) =
        viewModelScope.launch {
            webOfTrustRepository.setFilterEnabled(ownerId = activeAccountStore.activeUserId(), enabled = enabled)
            // Turning the filter on is the natural moment to also compute a network for the first
            // time; without this the toggle would just sit there doing nothing until the user found
            // the refresh button on their own, having no way to know it was needed at all.
            if (enabled) trustNetworkRefresher.refresh()
        }

    private fun toggleHideStrangers(enabled: Boolean) {
        updateSettings { copy(networkOnly = enabled) }
        // Computed afresh: a network from before this setting only knows the narrowest width.
        if (enabled) trustNetworkRefresher.refresh()
    }

    private fun updateSettings(change: PowSettings.() -> PowSettings) {
        viewModelScope.launch { settingsStore.update(activeAccountStore.activeUserId(), change) }
    }

    private fun observeStrangerSettings() =
        viewModelScope.launch {
            settingsStore.observe(activeAccountStore.activeUserId()).collect {
                setState { copy(hideStrangers = it.networkOnly, networkMinFollowers = it.networkMinFollowers) }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeNetworkSize() =
        viewModelScope.launch {
            val ownerId = activeAccountStore.activeUserId()
            val size = settingsStore.observe(ownerId)
                .map { it.networkMinFollowers }
                .distinctUntilChanged()
                .flatMapLatest { webOfTrustRepository.observeNetworkSize(ownerId, it) }
            combine(size, webOfTrustRepository.observeComputedAt(ownerId)) { count, computedAt ->
                count to (computedAt > 0)
            }.collect { (count, computed) -> setState { copy(networkSize = count, networkComputed = computed) } }
        }
}
