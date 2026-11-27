package net.primal.android.main.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import net.primal.android.core.compose.attachment.model.asEventUriUiModel
import net.primal.android.core.utils.authorNameUiFriendly
import net.primal.android.core.utils.isOnlyEmoji
import net.primal.android.core.utils.usernameUiFriendly
import net.primal.android.events.polls.votes.asPollUi
import net.primal.android.main.notifications.NotificationsContract.UiEvent
import net.primal.android.main.notifications.NotificationsContract.UiState
import net.primal.android.notes.feed.model.EventStatsUi
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.model.asNoteNostrUriUi
import net.primal.android.notifications.list.ui.NotificationUi
import net.primal.android.spam.StrangerFilter
import net.primal.android.spam.StrangerFilterProvider
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.subscriptions.SubscriptionsManager
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.contentWarning
import net.primal.domain.nostr.utils.asEllipsizedNpub
import net.primal.domain.notifications.Notification
import net.primal.domain.notifications.NotificationGroup
import net.primal.domain.notifications.NotificationRepository
import net.primal.domain.notifications.NotificationType
import net.primal.domain.posts.immediateParentId
import net.primal.domain.posts.threadRootId
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.toJavaInstant

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val notificationRepository: NotificationRepository,
    private val subscriptionsManager: SubscriptionsManager,
    private val strangerFilters: StrangerFilterProvider,
) : ViewModel() {

    /** Follows can be turned off entirely; the feed has to follow both that toggle and account switches. */
    private val activeNotificationsConfig: Flow<Pair<String, Boolean>> = combine(
        activeAccountStore.activeUserId.filter { it.isNotBlank() },
        activeAccountStore.activeUserAccount.map { it.showFollowNotifications },
    ) { userId, showFollows -> userId to showFollows }.distinctUntilChanged()

    /** Null while strangers are not filtered, which leaves the lists exactly as they were. */
    private val strangerFilter: Flow<StrangerFilter?> =
        activeAccountStore.activeUserId
            .filter { it.isNotBlank() }
            .flatMapLatest { strangerFilters.observe(it) }

    private val seenPagerCache: Map<NotificationGroup, Flow<PagingData<NotificationUi>>> =
        NotificationGroup.entries.associateWith { group ->
            activeNotificationsConfig
                .flatMapLatest { (userId, showFollows) ->
                    notificationRepository.observeSeenNotifications(
                        userId = userId,
                        group = group,
                        showFollows = showFollows,
                        utcOffsetSeconds = currentUtcOffsetSeconds(),
                    )
                }
                // Cached on both sides: the filter can change while the pages stay what they are.
                .cachedIn(viewModelScope + dispatcherProvider.io())
                .combine(strangerFilter) { paging, filter ->
                    val shown = if (filter == null) paging else paging.filter { !filter.hides(it) }
                    shown.map { notification -> notification.asNotificationUi() }
                }
                .cachedIn(viewModelScope + dispatcherProvider.io())
        }

    private val unseenCache: Map<NotificationGroup, Flow<List<List<NotificationUi>>>> =
        NotificationGroup.entries.associateWith { group ->
            activeNotificationsConfig
                .flatMapLatest { (userId, showFollows) ->
                    notificationRepository.observeUnseenNotifications(
                        ownerId = userId,
                        group = group,
                        showFollows = showFollows,
                    )
                }
                .combine(strangerFilter) { notifications, filter ->
                    if (filter == null) notifications else notifications.filterNot { filter.hides(it) }
                }
                .map { notifications -> groupUnseenNotifications(notifications) }
                // Checking proof of work hashes events: not on the main thread.
                .flowOn(dispatcherProvider.io())
                .shareIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    replay = 1,
                )
        }

    internal fun seenNotificationsForGroup(group: NotificationGroup): Flow<PagingData<NotificationUi>> =
        seenPagerCache.getValue(group)

    internal fun unseenNotificationsForGroup(group: NotificationGroup): Flow<List<List<NotificationUi>>> =
        unseenCache.getValue(group)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events: MutableSharedFlow<UiEvent> = MutableSharedFlow()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        subscribeToEvents()
        subscribeToBadgesUpdates()
    }

    private fun subscribeToEvents() =
        viewModelScope.launch {
            events.collect {
                when (it) {
                    is UiEvent.NotificationsSeen -> handleNotificationsSeen(it.group)
                }
            }
        }

    private fun subscribeToBadgesUpdates() =
        viewModelScope.launch {
            subscriptionsManager.badges.collect {
                // No sticky "already seen" override: once set it hid every later arrival for the
                // rest of the session. The flow is distinctUntilChanged, so a count seen and
                // cleared locally is not re-delivered; a new emission means something changed.
                setState { copy(badges = it) }
            }
        }

    private fun handleNotificationsSeen(group: NotificationGroup) {
        if (group != NotificationGroup.ALL) return
        viewModelScope.launch(dispatcherProvider.io()) {
            notificationRepository.markAllNotificationsAsSeenLocally(activeAccountStore.activeUserId())
        }
        // The cache-less client has no remote AUTH endpoint to acknowledge reads. Keep the
        // local navigation state authoritative so the unread dot disappears immediately.
        setState { copy(badges = badges.copy(unreadNotificationsCount = 0)) }
        Napier.d { "Skipping cache notifications last-seen AUTH" }
    }

    @Suppress("NestedBlockDepth")
    private fun groupUnseenNotifications(notifications: List<Notification>): List<List<NotificationUi>> {
        val unseenNotifications = mutableListOf<List<Notification>>()
        val groupByType = notifications.groupBy { it.type }
        groupByType.keys.forEach { notificationType ->
            groupByType[notificationType]?.let { notificationsByType ->
                when (notificationType.collapsable) {
                    true -> {
                        // Follows carry no post, so grouping them by actionPostId would collapse
                        // the entire history into one row. Bucket them by day instead, so
                        // "12 people followed you" stays about today rather than about forever.
                        val groupByPostId = if (notificationType == NotificationType.NEW_USER_FOLLOWED_YOU) {
                            notificationsByType.groupBy { it.createdAt.toDayBucket() }
                        } else {
                            notificationsByType.groupBy { it.actionPostId }
                        }
                        groupByPostId.keys.forEach { postId ->
                            groupByPostId[postId]?.let {
                                if (notificationType.isLike()) {
                                    it.map {
                                        it.copy(
                                            reaction = if (it.reaction?.isOnlyEmoji() == true) {
                                                it.reaction
                                            } else {
                                                "+"
                                            },
                                        )
                                    }.groupBy { it.reaction }
                                        .onEach { (_, notifications) ->
                                            unseenNotifications.add(notifications)
                                        }
                                } else {
                                    unseenNotifications.add(it)
                                }
                            }
                        }
                    }

                    false -> notificationsByType.forEach {
                        unseenNotifications.add(listOf(it))
                    }
                }
            }
        }

        return unseenNotifications
            .sortedWith(
                compareByDescending<List<Notification>> { group -> group.maxOfOrNull { it.createdAt } ?: 0L }
                    .thenByDescending { group -> group.maxOfOrNull { it.notificationId } ?: "" },
            )
            .map { byType -> byType.map { it.asNotificationUi() } }
    }

    /**
     * The reader's offset from UTC, so the query buckets follows into the same day the screen
     * prints. Read here because only this layer knows the timezone the rows are read in.
     */
    private fun currentUtcOffsetSeconds(): Long =
        ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds.toLong()

    /** Local-day bucket for a unix timestamp, so follows collapse per day rather than forever. */
    private fun Long.toDayBucket(): String =
        Instant.ofEpochSecond(this)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    private fun NotificationType.isLike() =
        this == NotificationType.YOUR_POST_WAS_LIKED ||
            this == NotificationType.POST_YOU_WERE_MENTIONED_IN_WAS_LIKED ||
            this == NotificationType.POST_YOUR_POST_WAS_MENTIONED_IN_WAS_LIKED

    private fun Notification.asNotificationUi(): NotificationUi {
        return NotificationUi(
            notificationId = this.notificationId,
            ownerId = this.ownerId,
            notificationType = this.type,
            createdAt = Instant.ofEpochSecond(this.createdAt),
            actionUserId = this.actionUserId,
            reaction = this.reaction,
            actionUserDisplayName = this.actionByUser?.authorNameUiFriendly()
                ?: this.actionUserId?.asEllipsizedNpub(),
            actionUserInternetIdentifier = this.actionByUser?.internetIdentifier,
            actionUserAvatarCdnImage = this.actionByUser?.avatarCdnImage,
            actionUserSatsZapped = this.satsZapped,
            actionPost = this.extractFeedPostUi(),
            groupCount = this.groupCount,
        )
    }

    private fun Notification.extractFeedPostUi(): FeedPostUi? {
        return this.actionOnPost?.let { actionOnPost ->
            FeedPostUi(
                postId = actionOnPost.eventId,
                authorId = actionOnPost.author.authorId,
                authorName = this.actionByUser?.authorNameUiFriendly()
                    ?: actionOnPost.author.displayName,
                authorHandle = this.actionByUser?.usernameUiFriendly()
                    ?: actionOnPost.author.handle,
                authorInternetIdentifier = this.actionByUser?.internetIdentifier,
                authorAvatarCdnImage = this.actionByUser?.avatarCdnImage,
                timestamp = actionOnPost.timestamp.toJavaInstant(),
                content = actionOnPost.content,
                kind = actionOnPost.kind,
                uris = actionOnPost.links.map { it.asEventUriUiModel() },
                nostrUris = actionOnPost.nostrUris.map { it.asNoteNostrUriUi() },
                stats = EventStatsUi.from(actionOnPost.stats),
                hashtags = actionOnPost.hashtags,
                rawNostrEventJson = actionOnPost.rawNostrEvent,
                poll = actionOnPost.pollInfo?.asPollUi(),
                // A gift-wrapped private reply reaches this screen like any other notification,
                // but it is not like any other note: it has to draw the lock, hide the public
                // actions, and open the thread it answers rather than its own id, which is not a
                // note any relay holds. Building the UI model by hand here dropped all three.
                isPrivate = actionOnPost.isPrivate,
                threadRootId = actionOnPost.threadRootId(),
                threadParentId = actionOnPost.immediateParentId(),
                contentWarning = actionOnPost.tags.contentWarning(),
            )
        }
    }
}
