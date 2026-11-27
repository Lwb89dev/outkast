package net.primal.android.settings.pow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.primal.android.pow.PowSettings
import net.primal.android.pow.PowSettingsStore
import net.primal.android.user.accounts.active.ActiveAccountStore

@HiltViewModel
class PowSettingsViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val settingsStore: PowSettingsStore,
) : ViewModel() {

    /** Null until read, so the switches do not flick from the defaults to what is stored. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state = activeAccountStore.activeUserId
        .flatMapLatest { settingsStore.observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null as PowSettings?)

    fun update(change: PowSettings.() -> PowSettings) {
        viewModelScope.launch { settingsStore.update(activeAccountStore.activeUserId(), change) }
    }
}
