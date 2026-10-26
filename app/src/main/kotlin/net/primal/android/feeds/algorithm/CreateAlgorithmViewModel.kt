package net.primal.android.feeds.algorithm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.core.compose.profile.model.UserProfileItemUi
import net.primal.android.core.compose.profile.model.asUserProfileItemUi
import net.primal.android.core.compose.profile.model.mapAsUserProfileUi
import net.primal.android.feeds.algorithm.CreateAlgorithmContract.ImportableList
import net.primal.android.feeds.algorithm.CreateAlgorithmContract.UiEvent
import net.primal.android.feeds.algorithm.CreateAlgorithmContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.onFailure
import net.primal.core.utils.onSuccess
import net.primal.core.utils.runCatching
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.explore.ExploreRepository
import net.primal.domain.feeds.FEED_KIND_LIST
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.FeedsRepository
import net.primal.domain.feeds.buildFollowSetFeedSpec
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.findFirstTitle
import net.primal.domain.nostr.isFollowedMuteListTag
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.asEllipsizedNpub
import net.primal.domain.nostr.utils.extractProfileId
import net.primal.domain.profile.ProfileData
import net.primal.domain.profile.ProfileRepository
import net.primal.domain.publisher.PrimalPublisher

/**
 * Creating an algorithm, or importing a list the user already has.
 *
 * The people come from two places: the user's contacts (everyone they follow, by name) and a search box that finds
 * anyone, including people they do not follow yet, and also accepts an `npub` or `nprofile` pasted into it. What is
 * chosen is published as a NIP-51 follow set and added to the user's feeds, from where it behaves like any other
 * list feed. Importing is the same last step for a follow set that is already on the relays.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreateAlgorithmViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val profileRepository: ProfileRepository,
    private val exploreRepository: ExploreRepository,
    private val feedsRepository: FeedsRepository,
    private val relayEventQuerier: RelayEventQuerier,
    private val publisher: PrimalPublisher,
    private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    /** Read when it is needed, not once: this ViewModel outlives the screen, and the account can change under it. */
    private val userId: String get() = activeAccountStore.activeUserId()

    private var listsJob: Job? = null

    /** Whose lists were last read and when, so a recent answer is not asked for again. */
    private var listsOwner: String? = null
    private var listsLoadedAtMs = 0L

    /** The feed specs the user already has, to tell which lists are added. Kept current by [observeAddedLists]. */
    private var addedSpecs: Set<String> = emptySet()

    init {
        observeContacts()
        observeSearch()
        observeAddedLists()
    }

    /**
     * Gets the screen ready before it is opened: the contacts start resolving as soon as this ViewModel exists, and
     * this reads the user's lists from the relays, which is the slow part and can run while the user is still looking
     * at the panel that leads here. Does nothing if the lists were read a moment ago.
     */
    fun prepare() {
        val owner = userId
        val fresh = listsOwner == owner && System.currentTimeMillis() - listsLoadedAtMs < LISTS_FRESH_MS
        if (fresh || listsJob?.isActive == true) return
        listsJob = viewModelScope.launch { loadImportableLists(owner) }
    }

    /**
     * A fresh start each time the screen is opened: nothing of the previous draft (or of the previous success) is
     * kept. The lists already read stay on screen while they are asked for again if they are getting old, instead of
     * the section disappearing and coming back.
     */
    fun onOpened() {
        queries.value = ""
        _state.update {
            it.copy(
                name = "",
                query = "",
                searchResults = emptyList(),
                searching = false,
                selected = emptyMap(),
                saving = false,
                failed = false,
                created = false,
            )
        }
        prepare()
    }

    fun setEvent(event: UiEvent) {
        when (event) {
            is UiEvent.SetName -> _state.update { it.copy(name = event.name, failed = false) }
            is UiEvent.SetQuery -> setQuery(event.query)
            is UiEvent.ToggleProfile -> toggle(event.profile)
            is UiEvent.ImportList -> importList(event.list)
            UiEvent.Create -> create()
        }
    }

    // --- People -----------------------------------------------------------------------------------------------

    private fun observeContacts() =
        viewModelScope.launch {
            activeAccountStore.activeUserAccount
                .map { it.following }
                .distinctUntilChanged()
                .collectLatest { following -> resolveContacts(following.toList()) }
        }

    /** Names first from what is stored, then the profiles that were never fetched, so the list never waits. */
    private suspend fun resolveContacts(ids: List<String>) {
        val known = withContext(dispatcherProvider.io()) { profileRepository.findProfileData(ids) }
        publishContacts(ids = ids, profiles = withContext(dispatcherProvider.io()) { known.asProfilesById() })
        _state.update { it.copy(loadingContacts = false) }

        val knownIds = known.mapTo(HashSet()) { it.profileId }
        val missing = ids.filterNot { it in knownIds }.take(MAX_PROFILES_TO_FETCH)
        if (missing.isEmpty()) return
        val fetched = runCatching { profileRepository.fetchProfiles(missing) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) return
        publishContacts(ids = ids, profiles = withContext(dispatcherProvider.io()) { (known + fetched).asProfilesById() })
    }

    private fun List<ProfileData>.asProfilesById(): Map<String, UserProfileItemUi> =
        associate { it.profileId to it.asUserProfileItemUi() }

    /** Ordered by name. Thousands of contacts are sorted off the main thread, so opening the screen never stalls. */
    private suspend fun publishContacts(ids: List<String>, profiles: Map<String, UserProfileItemUi>) {
        val contacts = withContext(dispatcherProvider.io()) {
            ids.map { id -> profiles[id] ?: UserProfileItemUi(profileId = id, displayName = id.nameFallback()) }
                .sortedBy { it.displayName.lowercase() }
        }
        _state.update { it.copy(contacts = contacts) }
    }

    private fun String.nameFallback(): String = runCatching { asEllipsizedNpub() }.getOrDefault(this)

    private fun setQuery(query: String) {
        _state.update { it.copy(query = query, searching = query.isNotBlank()) }
        queries.value = query
    }

    private fun observeSearch() =
        viewModelScope.launch {
            queries.debounce(SEARCH_DEBOUNCE_MS.milliseconds)
                .distinctUntilChanged()
                .collectLatest { query -> search(query.trim()) }
        }

    private suspend fun search(query: String) {
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        val results = pastedProfile(query)?.let(::listOf) ?: remoteSearch(query)
        val contactIds = _state.value.contacts.mapTo(HashSet()) { it.profileId }
        _state.update { it.copy(searchResults = results.filter { p -> p.profileId !in contactIds }, searching = false) }
    }

    /** An `npub…` or `nprofile…` (with or without `nostr:`) typed or pasted into the box: that person, no search. */
    private suspend fun pastedProfile(query: String): UserProfileItemUi? {
        val looksLikeKey = query.removePrefix("nostr:").let { it.startsWith("npub1") || it.startsWith("nprofile1") }
        if (!looksLikeKey) return null
        val profileId = runCatching { query.extractProfileId() }.getOrDefault(null) ?: return null
        val profile = runCatching { profileRepository.fetchProfiles(listOf(profileId)).firstOrNull() }.getOrDefault(null)
        return profile?.asUserProfileItemUi() ?: UserProfileItemUi(profileId = profileId, displayName = profileId.nameFallback())
    }

    private suspend fun remoteSearch(query: String): List<UserProfileItemUi> =
        try {
            exploreRepository.searchUsers(query = query, limit = SEARCH_LIMIT).map { it.mapAsUserProfileUi() }
        } catch (error: NetworkException) {
            Napier.w(throwable = error) { "Failed to search users for an algorithm, query=$query." }
            emptyList()
        }

    private fun toggle(profile: UserProfileItemUi) {
        _state.update { current ->
            val selected = LinkedHashMap(current.selected)
            if (selected.remove(profile.profileId) == null) selected[profile.profileId] = profile
            current.copy(selected = selected, failed = false)
        }
    }

    // --- Lists ------------------------------------------------------------------------------------------------

    private suspend fun loadImportableLists(owner: String) {
        val events = runCatching {
            relayEventQuerier.query(
                RelayFilter(kinds = listOf(NostrEventKind.CategorizedPeopleList.value), authors = listOf(owner)),
            )
        }.getOrDefault(emptyList())
        // An empty answer is not remembered as fresh: it may be the relays failing to answer, and the next opening
        // should try again rather than show nothing for as long as a real answer would be kept.
        val lists = withContext(dispatcherProvider.io()) { events.asImportableLists() }
        if (lists.isNotEmpty()) {
            listsOwner = owner
            listsLoadedAtMs = System.currentTimeMillis()
        }
        _state.update { it.copy(importableLists = lists.withAddedFlags(owner)) }
    }

    /** Which lists are already among the user's feeds follows the feeds, so a list shows as added the moment it is. */
    private fun observeAddedLists() =
        viewModelScope.launch {
            activeAccountStore.activeUserId
                .flatMapLatest { owner -> feedsRepository.observeNotesFeeds(owner) }
                .collect { feeds ->
                    addedSpecs = feeds.mapTo(HashSet()) { it.spec }
                    val owner = userId
                    _state.update { it.copy(importableLists = it.importableLists.withAddedFlags(owner)) }
                }
        }

    private fun List<ImportableList>.withAddedFlags(owner: String): List<ImportableList> =
        map { it.copy(added = buildFollowSetFeedSpec(owner, it.identifier) in addedSpecs) }

    /** The latest version of each of the user's follow sets, leaving out the list that carries muted-list follows. */
    private fun List<NostrEvent>.asImportableLists(): List<ImportableList> =
        filterNot { event -> event.tags.any { it.isFollowedMuteListTag() } }
            .groupBy { it.tags.findFirstIdentifier().orEmpty() }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (identifier, versions) ->
                val latest = versions.maxByOrNull { it.createdAt } ?: return@mapNotNull null
                ImportableList(
                    identifier = identifier,
                    title = latest.tags.findFirstTitle() ?: latest.content.ifBlank { identifier },
                    memberCount = latest.tags.pubkeyTagValues().distinct().size,
                )
            }
            .sortedBy { it.title.lowercase() }

    private fun importList(list: ImportableList) {
        if (list.added) return
        viewModelScope.launch {
            feedsRepository.addFeedLocally(
                userId = userId,
                feedSpec = buildFollowSetFeedSpec(pubkey = userId, dTag = list.identifier),
                title = list.title,
                description = "",
                feedSpecKind = FeedSpecKind.Notes,
                feedKind = FEED_KIND_LIST,
            )
        }
    }

    // --- Creating ---------------------------------------------------------------------------------------------

    private fun create() {
        val current = _state.value
        if (!current.canCreate) return
        _state.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            val title = current.name.trim()
            val identifier = newAlgorithmIdentifier(title)
            runCatching {
                publisher.signPublishImportNostrEvent(
                    unsignedNostrEvent = buildAlgorithmEvent(
                        userId = userId,
                        identifier = identifier,
                        title = title,
                        members = current.selected.keys,
                    ),
                )
                feedsRepository.addFeedLocally(
                    userId = userId,
                    feedSpec = buildFollowSetFeedSpec(pubkey = userId, dTag = identifier),
                    title = title,
                    description = "",
                    feedSpecKind = FeedSpecKind.Notes,
                    feedKind = FEED_KIND_LIST,
                )
            }.onFailure { error ->
                Napier.w(throwable = error) { "Failed to publish the new algorithm." }
                _state.update { it.copy(saving = false, failed = true) }
            }.onSuccess {
                _state.update { it.copy(saving = false, created = true) }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val SEARCH_LIMIT = 20
        const val MAX_PROFILES_TO_FETCH = 300

        /** The lists read from the relays are good for this long; opening the screen again within it asks nothing. */
        const val LISTS_FRESH_MS = 60_000L
    }
}
