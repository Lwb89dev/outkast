package net.primal.android.settings.wot

import net.primal.android.pow.PowSettings
import net.primal.domain.wot.WotDiscoveryState

interface WebOfTrustSettingsContract {
    data class UiState(
        val filterEnabled: Boolean = false,
        val discoveryState: WotDiscoveryState = WotDiscoveryState.Idle,
        val hideStrangers: Boolean = false,
        val networkMinFollowers: Int = PowSettings.DEFAULT_NETWORK_MIN_FOLLOWERS,
        val networkSize: Int = 0,
        val networkComputed: Boolean = false,
    )

    sealed class UiEvent {
        data class ToggleFilter(val enabled: Boolean) : UiEvent()
        data object RefreshNetworkClick : UiEvent()
        data class ToggleHideStrangers(val enabled: Boolean) : UiEvent()
        data class ChangeNetworkWidth(val minFollowers: Int) : UiEvent()
    }
}
