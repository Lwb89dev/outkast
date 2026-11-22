package net.primal.android.main

import net.primal.android.drawer.multiaccount.model.UserAccountUi
import net.primal.android.user.domain.Badges
import net.primal.domain.links.CdnImage

interface MainContract {

    data class UiState(
        val activeAccountId: String = "",
        val activeAccountAvatarCdnImage: CdnImage? = null,
        val activeAccountBlossoms: List<String> = emptyList(),
        val activeAccount: UserAccountUi? = null,
        val otherAccounts: List<UserAccountUi> = emptyList(),
        val badges: Badges = Badges(),
        val hasMultipleAccounts: Boolean = false,
        val showExploreHint: Boolean = false,
    )

    sealed class UiEvent {
        data object RequestUserDataUpdate : UiEvent()
        data object SwitchToNextAccount : UiEvent()
        data class SwitchAccount(val userId: String) : UiEvent()
        data object DismissExploreHint : UiEvent()
        data object NotificationsViewed : UiEvent()
    }

    sealed class SideEffect {
        data object AccountSwitched : SideEffect()
    }
}
