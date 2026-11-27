package net.primal.android.explore.search

import net.primal.android.core.compose.profile.model.UserProfileItemUi
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks

interface SearchContract {

    data class UiState(
        val searching: Boolean = false,
        val searchQuery: String = "",
        /** Accounts matching the people part of [searchQuery]. */
        val searchResults: List<UserProfileItemUi> = emptyList(),
        val recentUsers: List<UserProfileItemUi> = emptyList(),
        val recentSearches: List<String> = emptyList(),
        val popularUsers: List<UserProfileItemUi> = emptyList(),
        /** The query whose results are on screen; null while still typing. */
        val submittedQuery: String? = null,
        val onlyFollows: Boolean = false,
    ) {
        val recommendedUsers: List<UserProfileItemUi> get() = recentUsers
    }

    sealed class UiEvent {
        data class SearchQueryUpdated(val query: String) : UiEvent()
        data class ProfileSelected(val profileId: String) : UiEvent()
        data class SearchSubmitted(val query: String) : UiEvent()
        data object ResetSearchQuery : UiEvent()
        data class RemoveRecentSearch(val query: String) : UiEvent()
        data object ClearRecentSearches : UiEvent()
        data class OnlyFollowsChanged(val onlyFollows: Boolean) : UiEvent()
    }

    data class ScreenCallbacks(
        val onClose: () -> Unit,
        val onAdvancedSearchClick: (query: String) -> Unit,
        val onProfileClick: (String) -> Unit,
        val onNaddrClick: (String) -> Unit,
        /** Opens the results as a feed of their own, which can then be added to the home feeds. */
        val onOpenAsFeedClick: (feedSpec: String) -> Unit = {},
        val noteCallbacks: NoteCallbacks = NoteCallbacks(),
    )
}
