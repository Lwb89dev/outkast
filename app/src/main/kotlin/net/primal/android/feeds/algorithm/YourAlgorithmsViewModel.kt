package net.primal.android.feeds.algorithm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.android.feeds.list.ui.model.asFeedUi
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.PrimalFeed
import net.primal.domain.feeds.isFollowSetFeedSpec

/**
 * The user's own algorithms: the list feeds they created or imported. The two default feeds (recent notes, with and
 * without replies) are not among them, they already have their own place in the Home menu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YourAlgorithmsViewModel @Inject constructor(
    private val feedsRepository: FeedsRepository,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    data class UiState(
        val algorithms: List<FeedUi> = emptyList(),
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Follows the active account: this ViewModel stays alive across an account switch.
            activeAccountStore.activeUserId
                .flatMapLatest { userId -> feedsRepository.observeNotesFeeds(userId = userId) }
                .collect { feeds -> _state.update { UiState(algorithms = feeds.asAlgorithms(), loading = false) } }
        }
    }

    /** Takes the algorithm out of the user's feeds. The list itself stays on the relays and can be imported again. */
    fun remove(feed: FeedUi) {
        viewModelScope.launch {
            feedsRepository.removeFeedLocally(userId = activeAccountStore.activeUserId(), feedSpec = feed.spec)
        }
    }
}

internal fun List<PrimalFeed>.asAlgorithms(): List<FeedUi> =
    filter { it.spec.isFollowSetFeedSpec() }.sortedBy { it.position }.map { it.asFeedUi() }
