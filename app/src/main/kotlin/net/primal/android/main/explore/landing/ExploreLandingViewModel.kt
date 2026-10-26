package net.primal.android.main.explore.landing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.main.explore.landing.ExploreLandingContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.runCatching
import net.primal.domain.posts.FeedRepository

@HiltViewModel
class ExploreLandingViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val activeAccountStore: ActiveAccountStore,
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    init {
        observeRecentUsers()
        observeRecentSearches()
        prefetchNetwork()
    }

    /**
     * The Network section ranks what the user's friends engaged with, which takes several rounds of requests. Opening
     * Discover is the best sign that the section is next, so that work starts here and is waiting when the user gets
     * there. It is one pass that the section would run anyway, and it is kept for a few minutes.
     */
    private fun prefetchNetwork() =
        viewModelScope.launch {
            runCatching { feedRepository.prefetchNetworkNotes(userId = activeAccountStore.activeUserId()) }
        }

    private fun observeRecentUsers() =
        viewModelScope.launch {
            userRepository.observeRecentUsers(ownerId = activeAccountStore.activeUserId())
                .collect { users ->
                    setState { copy(recentUsers = users) }
                }
        }

    private fun observeRecentSearches() =
        viewModelScope.launch {
            userRepository.observeRecentSearches(
                ownerId = activeAccountStore.activeUserId(),
                limit = MAX_RECENT_SEARCHES,
            )
                .collect { queries ->
                    setState { copy(recentSearches = queries, recentSearchesLoading = false) }
                }
        }

    companion object {
        private const val MAX_RECENT_SEARCHES = 5
    }
}
