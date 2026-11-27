package net.primal.android.explore.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import net.primal.android.core.compose.profile.model.mapAsUserProfileUi
import net.primal.android.explore.search.SearchContract.UiEvent
import net.primal.android.explore.search.SearchContract.UiState
import net.primal.android.navigation.initialQuery
import net.primal.android.navigation.searchSubmit
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.explore.ExploreRepository

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val exploreRepository: ExploreRepository,
    private val userRepository: UserRepository,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {

    private val _state = MutableStateFlow(restoredState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private var searchJob: Job? = null

    init {
        observeRecentUsers()
        observeRecentSearches()
        if (state.value.searchQuery.isNotEmpty()) findPeople(state.value.searchQuery, debounce = false)
    }

    /** What was on screen before the process was killed, else what the screen was opened with. */
    private fun restoredState(): UiState {
        val initial = savedStateHandle.initialQuery.orEmpty()
        val query = savedStateHandle.get<String>(KEY_QUERY) ?: initial
        val submitted = if (savedStateHandle.contains(KEY_SUBMITTED)) {
            savedStateHandle.get<String>(KEY_SUBMITTED)
        } else {
            initial.takeIf { savedStateHandle.searchSubmit && it.isNotBlank() }
        }
        return UiState(searchQuery = query, submittedQuery = submitted)
    }

    fun setEvent(event: UiEvent) {
        when (event) {
            is UiEvent.SearchQueryUpdated -> onQueryChanged(event.query)
            is UiEvent.SearchSubmitted -> submit(event.query)
            is UiEvent.ProfileSelected -> markProfileInteraction(profileId = event.profileId)
            UiEvent.ResetSearchQuery -> onQueryChanged("")
            is UiEvent.RemoveRecentSearch -> editHistory { ownerId ->
                userRepository.deleteRecentSearch(ownerId = ownerId, query = event.query)
            }
            UiEvent.ClearRecentSearches -> editHistory { ownerId ->
                userRepository.clearRecentSearches(ownerId = ownerId)
            }
            is UiEvent.OnlyFollowsChanged -> setState { copy(onlyFollows = event.onlyFollows) }
        }
    }

    private fun onQueryChanged(query: String) {
        savedStateHandle[KEY_QUERY] = query
        savedStateHandle[KEY_SUBMITTED] = null
        setState { copy(searchQuery = query, submittedQuery = null, searchResults = emptyList()) }
        findPeople(query, debounce = true)
    }

    private fun submit(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        savedStateHandle[KEY_QUERY] = text
        savedStateHandle[KEY_SUBMITTED] = text
        val changed = text != state.value.searchQuery
        setState { copy(searchQuery = text, submittedQuery = text) }
        if (changed) findPeople(text, debounce = false)
        editHistory { ownerId -> userRepository.saveRecentSearch(ownerId = ownerId, query = text) }
    }

    /**
     * Only the latest query may run: each one used to launch independently, so a slow relay answer
     * for "jack" could land after — and overwrite — the results for "jackdorsey". Local matches are
     * shown the moment they are read, the relay ones replace them when (and if) they arrive.
     */
    @OptIn(FlowPreview::class)
    private fun findPeople(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val name = query.toPeopleQuery()
        if (name.isEmpty()) {
            setState { copy(searching = false, searchResults = emptyList()) }
            return
        }
        setState { copy(searching = true) }
        searchJob = viewModelScope.launch {
            try {
                val local = exploreRepository.searchUsersLocally(query = name)
                if (local.isNotEmpty()) setState { copy(searchResults = local.map { it.mapAsUserProfileUi() }) }

                if (debounce) delay(TYPING_PAUSE)
                val result = exploreRepository.searchUsers(query = name)
                setState { copy(searchResults = result.map { it.mapAsUserProfileUi() }) }
            } catch (error: NetworkException) {
                Napier.w(throwable = error) { "Failed to search users." }
            }
            setState { copy(searching = false) }
        }
    }

    private fun editHistory(edit: suspend (ownerId: String) -> Unit) {
        viewModelScope.launch {
            runCatching { edit(activeAccountStore.activeUserId()) }
                .onFailure { error -> Napier.w(throwable = error) { "Failed to update recent searches." } }
        }
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
            ).collect { queries ->
                setState { copy(recentSearches = queries) }
            }
        }

    private fun markProfileInteraction(profileId: String) {
        viewModelScope.launch {
            userRepository.markAsInteracted(profileId = profileId, ownerId = activeAccountStore.activeUserId())
        }
    }

    private companion object {
        const val MAX_RECENT_SEARCHES = 10
        val TYPING_PAUSE = 0.42.seconds
        const val KEY_QUERY = "searchText"
        const val KEY_SUBMITTED = "searchSubmittedText"
    }
}
