package net.primal.android.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.paging.PagingData
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.core.compose.PrimalOverlay
import net.primal.android.core.compose.PrimalTopLevelAppBar
import net.primal.android.core.compose.PrimalTopLevelDestination
import net.primal.android.core.compose.SnackbarErrorHandler
import net.primal.android.core.compose.bubble.AnchorHandle
import net.primal.android.core.compose.bubble.AnchoredBubble
import net.primal.android.core.compose.bubble.BubblePlacement
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.OutkastNavigationIcons
import net.primal.android.core.compose.icons.primaliconpack.Bookmarks
import net.primal.android.core.compose.fab.NewPostFloatingActionButton
import net.primal.android.core.compose.runtime.DisposableLifecycleObserverEffect
import net.primal.android.core.errors.resolveUiErrorMessage
import net.primal.android.drawer.DrawerScreenDestination
import net.primal.android.drawer.PrimalDrawer
import net.primal.android.drawer.PrimalMainScaffold
import net.primal.android.drawer.multiaccount.events.AccountSwitcherCallbacks
import net.primal.android.explore.search.ui.SearchScope
import net.primal.android.feeds.list.FeedListOverlayContent
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.android.main.explore.section.ExploreSection
import net.primal.android.main.explore.section.ExploreSectionListOverlayContent
import net.primal.android.main.explore.ExploreHomeContent
import net.primal.android.main.feeds.NoteFeedTopAppBar
import net.primal.android.main.feeds.NoteFeedsContent
import net.primal.android.main.feeds.NoteFeedsContract
import net.primal.android.main.feeds.NoteFeedsViewModel
import net.primal.android.main.notifications.NotificationsContent
import net.primal.android.main.notifications.NotificationsContract
import net.primal.android.main.notifications.NotificationsTopAppBar
import net.primal.android.main.notifications.NotificationsViewModel
import net.primal.android.theme.AppTheme
import net.primal.android.main.reads.ArticleFeedTopAppBar
import net.primal.android.main.reads.ReadsContent
import net.primal.android.main.reads.ReadsScreenContract
import net.primal.android.main.reads.ReadsViewModel
import net.primal.android.navigation.CURRENT_MAIN_TAB_KEY
import net.primal.android.navigation.accountSwitcherCallbacksHandler
import net.primal.android.navigation.primalSlideInHorizontallyFromEnd
import net.primal.android.navigation.primalSlideInHorizontallyFromStart
import net.primal.android.navigation.primalSlideOutHorizontallyToEnd
import net.primal.android.navigation.primalSlideOutHorizontallyToStart
import net.primal.android.navigation.navigateToAdvancedSearch
import net.primal.android.navigation.navigateToHome
import net.primal.android.navigation.navigateToFollowPack
import net.primal.android.navigation.navigateToNoteEditor
import net.primal.android.navigation.navigateToMessages
import net.primal.android.navigation.navigateToProfile
import net.primal.android.navigation.navigateToProfileQrCodeViewer
import net.primal.android.navigation.navigateToSearch
import net.primal.android.navigation.navigateToSettings
import net.primal.android.navigation.navigateToThread
import net.primal.android.navigation.noteCallbacksHandler
import net.primal.android.zaps.AndroidLightningWallet
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.notifications.list.ui.NotificationUi
import net.primal.domain.feeds.FeedSpecKind
import net.primal.domain.feeds.defaultLibreNostrNoteFeeds
import net.primal.android.feeds.list.ui.model.asFeedUi
import net.primal.domain.links.CdnImage
import net.primal.domain.notifications.NotificationGroup

internal const val REQUESTED_TAB_KEY = "requestedTab"

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    navBackStackEntry: NavBackStackEntry,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
) {
    val uiScope = rememberCoroutineScope()

    // Tab state management
    var activeTab by rememberSaveable { mutableStateOf(PrimalTopLevelDestination.Feeds) }
    // Observe requestedTab from external navigation
    val requestedTab = navBackStackEntry.savedStateHandle
        .getStateFlow<String?>(REQUESTED_TAB_KEY, null)
        .collectAsState()

    LaunchedEffect(requestedTab.value) {
        val tabName = requestedTab.value ?: return@LaunchedEffect
        val destination = PrimalTopLevelDestination.entries.find { it.name == tabName }
        if (destination != null && destination != activeTab && destination != PrimalTopLevelDestination.Settings) {
            activeTab = destination
        }
        navBackStackEntry.savedStateHandle[REQUESTED_TAB_KEY] = null
    }

    // Unlike requestedTab above (a one-shot request, cleared right after being read), this
    // always reflects what's currently on screen — PrimalAppNavigation's transitions read it to
    // pick a slide direction when leaving/returning to "main" (see CURRENT_MAIN_TAB_KEY's doc).
    LaunchedEffect(activeTab) {
        navBackStackEntry.savedStateHandle[CURRENT_MAIN_TAB_KEY] = activeTab.name
    }

    // Shared callbacks
    val externalWallet = AndroidLightningWallet(LocalContext.current)
    val noteCallbacks = noteCallbacksHandler(
        navController = navController,
        onPayInvoice = { invoice -> externalWallet.payBolt11(invoice) },
    )
    val accountSwitcherCallbacks = accountSwitcherCallbacksHandler(navController)

    val mainViewModel = hiltViewModel<MainViewModel>(navBackStackEntry)
    val mainState by mainViewModel.state.collectAsState()

    MainScreenSharedEffects(mainViewModel, navController)

    val noteFeedsViewModel = hiltViewModel<NoteFeedsViewModel>(navBackStackEntry)
    val noteFeedsState by noteFeedsViewModel.state.collectAsState()

    val readsViewModel = hiltViewModel<ReadsViewModel>(navBackStackEntry)
    val readsState by readsViewModel.state.collectAsState()

    val notificationsViewModel = hiltViewModel<NotificationsViewModel>(navBackStackEntry)
    val notificationsState by notificationsViewModel.state.collectAsState()

    val homeTopAppBarState = rememberHomeTopAppBarState()
    val currentTopAppBarState = rememberPerTabTopAppBarState(activeTab, homeTopAppBarState)

    val sharedState = rememberMainScreenSharedState(
        noteFeedsState = noteFeedsState,
        readsState = readsState,
    )

    SnackbarErrorHandler(
        error = noteFeedsState.uiError,
        snackbarHostState = sharedState.snackbarHostState,
        errorMessageResolver = { it.resolveUiErrorMessage(context = LocalContext.current) },
        onErrorDismiss = { noteFeedsViewModel.setEvent(NoteFeedsContract.UiEvent.DismissError) },
    )

    val onActiveDestinationClick: () -> Unit = {
        handleActiveDestinationClick(activeTab, sharedState, uiScope)
    }

    val onTabChanged: (PrimalTopLevelDestination) -> Unit = { destination ->
        if (destination != activeTab) {
            activeTab = destination
        }
    }

    // The bottom navigation remains visible; only the Home header collapses with the feed.
    val focusModeEnabled = activeTab == PrimalTopLevelDestination.Feeds

    BackHandler(enabled = activeTab != PrimalTopLevelDestination.Feeds) {
        activeTab = PrimalTopLevelDestination.Feeds
    }

    MainScreenScaffold(
        activeTab = activeTab,
        mainState = mainState,
        mainEventPublisher = mainViewModel::setEvent,
        homeState = noteFeedsState,
        homeEventPublisher = noteFeedsViewModel::setEvent,
        readsState = readsState,
        readsEventPublisher = readsViewModel::setEvent,
        notificationsState = notificationsState,
        notificationsSeenProvider = notificationsViewModel::seenNotificationsForGroup,
        notificationsUnseenProvider = notificationsViewModel::unseenNotificationsForGroup,
        onNotificationsSeen = { group ->
            notificationsViewModel.setEvent(
                NotificationsContract.UiEvent.NotificationsSeen(group = group),
            )
            mainViewModel.setEvent(MainContract.UiEvent.NotificationsViewed)
        },
        currentTopAppBarState = currentTopAppBarState,
        sharedState = sharedState,
        noteCallbacks = noteCallbacks,
        accountSwitcherCallbacks = accountSwitcherCallbacks,
        focusModeEnabled = focusModeEnabled,
        onActiveDestinationClick = onActiveDestinationClick,
        onTabChanged = onTabChanged,
        onDrawerDestinationClick = onDrawerDestinationClick,
        navController = navController,
    )
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenTopAppBar(
    activeTab: PrimalTopLevelDestination,
    scrollBehavior: TopAppBarScrollBehavior?,
    onAvatarClick: () -> Unit,
    onAvatarSwipeDown: (() -> Unit)? = null,
    onReadPickerRequest: () -> Unit,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    chevronExpanded: Boolean = false,
    avatarCdnImage: CdnImage?,
    avatarBlossoms: List<String>,
    homeActiveFeed: FeedUi?,
    readsActiveFeed: FeedUi?,
    homePagerState: PagerState,
    readsPagerState: PagerState,
    onExploreSearchClick: () -> Unit,
    onExploreSearchSubmit: (String) -> Unit,
    onExploreSearchProfileClick: (String) -> Unit,
    readsFeeds: List<FeedUi>,
) {
    when (activeTab) {
        PrimalTopLevelDestination.Feeds -> {
            NoteFeedTopAppBar(
                title = homeActiveFeed?.title ?: "",
                pagerState = homePagerState,
                activeFeed = homeActiveFeed,
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                onMenuClick = null,
                onSearchClick = onExploreSearchClick,
                scrollBehavior = scrollBehavior,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                chevronExpanded = chevronExpanded,
                showAvatar = false,
                onSearchSubmit = onExploreSearchSubmit,
                onSearchProfileClick = onExploreSearchProfileClick,
            )
        }

        PrimalTopLevelDestination.Reads -> {
            ArticleFeedTopAppBar(
                title = readsActiveFeed?.title ?: "",
                pagerState = readsPagerState,
                feeds = readsFeeds,
                activeFeed = readsActiveFeed,
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                onFeedPickerRequest = onReadPickerRequest,
                scrollBehavior = scrollBehavior,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                chevronExpanded = chevronExpanded,
                showAvatar = false,
            )
        }

        PrimalTopLevelDestination.Explore -> {
            PrimalTopLevelAppBar(
                title = stringResource(id = R.string.algorithm_title),
                subtitle = stringResource(id = R.string.algorithm_subtitle),
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                scrollBehavior = scrollBehavior,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                chevronExpanded = chevronExpanded,
                showAvatar = false,
            )
        }

        PrimalTopLevelDestination.Messages -> Unit

        PrimalTopLevelDestination.Alerts -> {
            NotificationsTopAppBar(
                avatarCdnImage = avatarCdnImage,
                avatarBlossoms = avatarBlossoms,
                scrollBehavior = scrollBehavior,
                onAvatarClick = onAvatarClick,
                onAvatarSwipeDown = onAvatarSwipeDown,
                titleOverride = titleOverride,
                subtitleOverride = subtitleOverride,
                pagerState = null,
                pages = emptyList(),
                showTitleChevron = false,
                chevronExpanded = false,
                onTitleClick = null,
                showAvatar = false,
            )
        }

        PrimalTopLevelDestination.You,
        PrimalTopLevelDestination.Settings,
        -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaffoldTopAppBar(
    activeTab: PrimalTopLevelDestination,
    scrollBehavior: TopAppBarScrollBehavior?,
    mainState: MainContract.UiState,
    mainEventPublisher: (MainContract.UiEvent) -> Unit,
    accountDrawerVisible: Boolean,
    readPickerVisible: Boolean,
    exploreSectionPickerVisible: Boolean,
    sharedState: MainScreenSharedState,
    toggleOverlay: (ActiveOverlay) -> Unit,
    onExploreSearchClick: () -> Unit,
    onExploreSearchProfileClick: (String) -> Unit,
    readsFeeds: List<FeedUi>,
    navController: NavController,
) {
    val drawerTitle = if (accountDrawerVisible) stringResource(id = R.string.account_drawer_title) else null
    val drawerSubtitle = if (accountDrawerVisible) {
        stringResource(id = R.string.account_drawer_subtitle)
    } else {
        null
    }
    MainScreenTopAppBar(
        activeTab = activeTab,
        scrollBehavior = scrollBehavior,
        onAvatarClick = {
            if (mainState.activeAccountId.isNotEmpty()) {
                navController.navigateToProfile(profileId = mainState.activeAccountId)
            }
        },
        onAvatarSwipeDown = if (mainState.hasMultipleAccounts) {
            { mainEventPublisher(MainContract.UiEvent.SwitchToNextAccount) }
        } else {
            null
        },
        onReadPickerRequest = { toggleOverlay(ActiveOverlay.ReadPicker) },
        titleOverride = drawerTitle,
        subtitleOverride = drawerSubtitle,
        chevronExpanded = readPickerVisible ||
            exploreSectionPickerVisible,
        avatarCdnImage = mainState.activeAccountAvatarCdnImage,
        avatarBlossoms = mainState.activeAccountBlossoms,
        homeActiveFeed = sharedState.homeActiveFeed.value,
        readsActiveFeed = sharedState.readsActiveFeed.value,
        homePagerState = sharedState.homePagerState,
        readsPagerState = sharedState.readsPagerState,
        onExploreSearchClick = onExploreSearchClick,
        onExploreSearchSubmit = { query ->
            navController.navigateToSearch(
                searchScope = SearchScope.Notes,
                initialQuery = query,
            )
        },
        onExploreSearchProfileClick = onExploreSearchProfileClick,
        readsFeeds = readsFeeds,
    )
}

// Every other tab pair switches instantly (existing behavior, untouched) — Home and Notifications
// are the one pair reachable from the bottom bar with no navigation-graph transition of their own
// (MainScreen switches them via internal state), so they used to have no animation at all.
// Pulled out of MainScreenContent to keep this `when`'s complexity off of that already-large
// function rather than adding to it.
private val mainScreenTabTransitionSpec:
    AnimatedContentTransitionScope<PrimalTopLevelDestination>.() -> ContentTransform = {
    val initialIndex = OUTKAST_WORLDS.indexOf(initialState)
    val targetIndex = OUTKAST_WORLDS.indexOf(targetState)
    when {
        initialIndex >= 0 && targetIndex > initialIndex ->
            primalSlideInHorizontallyFromEnd togetherWith primalSlideOutHorizontallyToStart
        initialIndex >= 0 && targetIndex in 0 until initialIndex ->
            primalSlideInHorizontallyFromStart togetherWith primalSlideOutHorizontallyToEnd
        else -> EnterTransition.None togetherWith ExitTransition.None
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    activeTab: PrimalTopLevelDestination,
    saveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    paddingValues: PaddingValues,
    sharedState: MainScreenSharedState,
    noteCallbacks: NoteCallbacks,
    homeState: NoteFeedsContract.UiState,
    homeEventPublisher: (NoteFeedsContract.UiEvent) -> Unit,
    readsState: ReadsScreenContract.UiState,
    readsEventPublisher: (ReadsScreenContract.UiEvent) -> Unit,
    notificationsState: NotificationsContract.UiState,
    notificationsSeenProvider: (NotificationGroup) -> Flow<PagingData<NotificationUi>>,
    notificationsUnseenProvider: (NotificationGroup) -> Flow<List<List<NotificationUi>>>,
    onNotificationsSeen: (NotificationGroup) -> Unit,
    navController: NavController,
    onTabChanged: (PrimalTopLevelDestination) -> Unit,
    onHomeNewNotesChanged: (Boolean) -> Unit,
) {
    Box {
        // Every other tab pair here switches instantly (existing behavior, untouched) — Home and
        // Notifications are the one pair reachable from the bottom bar with no navigation-graph
        // transition of their own (MainScreen switches them via this internal state), so they
        // used to have literally no animation at all switching between them directly.
        AnimatedContent(
            targetState = activeTab,
            transitionSpec = mainScreenTabTransitionSpec,
            label = "MainScreenContent",
        ) { tab ->
            saveableStateHolder.SaveableStateProvider(tab.name) {
                when (tab) {
                    PrimalTopLevelDestination.Feeds -> NoteFeedsContent(
                        state = homeState,
                        pagerState = sharedState.homePagerState,
                        noteCallbacks = noteCallbacks,
                        eventPublisher = homeEventPublisher,
                        onActiveFeedChanged = { sharedState.homeActiveFeed.value = it },
                        selectedFeed = sharedState.homeActiveFeed.value,
                        shouldAnimateScrollToTop = sharedState.homeShouldAnimateScrollToTop,
                        scrollToFeed = sharedState.homeScrollToFeed,
                        snackbarHostState = sharedState.snackbarHostState,
                        paddingValues = paddingValues,
                        onNewNotesStateChanged = onHomeNewNotesChanged,
                    )

                    PrimalTopLevelDestination.Reads -> ReadsContent(
                        state = readsState,
                        pagerState = sharedState.readsPagerState,
                        eventPublisher = readsEventPublisher,
                        onActiveFeedChanged = { sharedState.readsActiveFeed.value = it },
                        shouldAnimateScrollToTop = sharedState.readsShouldAnimateScrollToTop,
                        scrollToFeed = sharedState.readsScrollToFeed,
                        snackbarHostState = sharedState.snackbarHostState,
                        paddingValues = paddingValues,
                        navController = navController,
                    )

                    PrimalTopLevelDestination.Explore -> {
                        ExploreHomeContent(
                            pagerState = sharedState.explorePagerState,
                            paddingValues = paddingValues,
                            noteCallbacks = noteCallbacks,
                            snackbarHostState = sharedState.snackbarHostState,
                            onFollowPackClick = { profileId, identifier ->
                                navController.navigateToFollowPack(profileId, identifier)
                            },
                            onRecentSearchEditClick = { query ->
                                navController.navigateToSearch(SearchScope.Notes, query)
                            },
                            onRecentSearchExecuteClick = { query ->
                                navController.navigateToSearch(SearchScope.Notes, query)
                            },
                        )
                    }

                    PrimalTopLevelDestination.Messages -> Unit

                    PrimalTopLevelDestination.Alerts -> NotificationsContent(
                        pagerState = sharedState.notificationsPagerState,
                        badges = notificationsState.badges,
                        seenNotificationsProvider = notificationsSeenProvider,
                        unseenNotificationsProvider = notificationsUnseenProvider,
                        onNotificationsSeen = onNotificationsSeen,
                        paddingValues = paddingValues,
                        noteCallbacks = noteCallbacks,
                        shouldAnimateScrollToTop = sharedState.notificationsShouldAnimateScrollToTop,
                        onMessagesClick = { navController.navigateToMessages() },
                    )

                    PrimalTopLevelDestination.You,
                    PrimalTopLevelDestination.Settings,
                    -> Unit
                }
            }
        }
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenScaffold(
    activeTab: PrimalTopLevelDestination,
    mainState: MainContract.UiState,
    mainEventPublisher: (MainContract.UiEvent) -> Unit,
    homeState: NoteFeedsContract.UiState,
    homeEventPublisher: (NoteFeedsContract.UiEvent) -> Unit,
    readsState: ReadsScreenContract.UiState,
    readsEventPublisher: (ReadsScreenContract.UiEvent) -> Unit,
    notificationsState: NotificationsContract.UiState,
    notificationsSeenProvider: (NotificationGroup) -> Flow<PagingData<NotificationUi>>,
    notificationsUnseenProvider: (NotificationGroup) -> Flow<List<List<NotificationUi>>>,
    onNotificationsSeen: (NotificationGroup) -> Unit,
    currentTopAppBarState: TopAppBarState,
    sharedState: MainScreenSharedState,
    noteCallbacks: NoteCallbacks,
    accountSwitcherCallbacks: AccountSwitcherCallbacks,
    focusModeEnabled: Boolean,
    onActiveDestinationClick: () -> Unit,
    onTabChanged: (PrimalTopLevelDestination) -> Unit,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
    navController: NavController,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val exploreAnchor = remember { AnchorHandle() }
    var activeOverlay by rememberSaveable { mutableStateOf<ActiveOverlay?>(null) }
    var orbitVisible by rememberSaveable { mutableStateOf(false) }
    var homeHasNewNotes by rememberSaveable { mutableStateOf(false) }
    val readPickerVisible = activeOverlay == ActiveOverlay.ReadPicker
    val exploreSectionPickerVisible = activeOverlay == ActiveOverlay.ExploreSectionPicker
    val accountDrawerVisible = activeOverlay == ActiveOverlay.AccountDrawer
    val exploreActiveSection = ExploreSection.entries
        .getOrElse(sharedState.explorePagerState.currentPage) { ExploreSection.Explore }

    fun toggleOverlay(overlay: ActiveOverlay) {
        activeOverlay = if (activeOverlay == overlay) null else overlay
    }

    PrimalMainScaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        focusModeEnabled = focusModeEnabled,
        topAppBarState = currentTopAppBarState,
        topAppBar = { scrollBehavior ->
            ScaffoldTopAppBar(
                activeTab = activeTab,
                scrollBehavior = scrollBehavior,
                mainState = mainState,
                mainEventPublisher = mainEventPublisher,
                accountDrawerVisible = accountDrawerVisible,
                readPickerVisible = readPickerVisible,
                exploreSectionPickerVisible = exploreSectionPickerVisible,
                sharedState = sharedState,
                toggleOverlay = ::toggleOverlay,
                onExploreSearchClick = {},
                onExploreSearchProfileClick = { profileId ->
                    navController.navigateToProfile(profileId = profileId)
                },
                readsFeeds = readsState.feeds,
                navController = navController,
            )
        },
        content = { paddingValues ->
            MainScreenContent(
                activeTab = activeTab,
                saveableStateHolder = saveableStateHolder,
                paddingValues = paddingValues,
                sharedState = sharedState,
                noteCallbacks = noteCallbacks,
                homeState = homeState,
                homeEventPublisher = homeEventPublisher,
                readsState = readsState,
                readsEventPublisher = readsEventPublisher,
                notificationsState = notificationsState,
                notificationsSeenProvider = notificationsSeenProvider,
                notificationsUnseenProvider = notificationsUnseenProvider,
                onNotificationsSeen = onNotificationsSeen,
                navController = navController,
                onTabChanged = onTabChanged,
                onHomeNewNotesChanged = { homeHasNewNotes = it },
            )
        },
        overlay = {
            MainScreenOverlays(
                accountDrawerVisible = accountDrawerVisible,
                readPickerVisible = readPickerVisible,
                exploreSectionPickerVisible = exploreSectionPickerVisible,
                exploreActiveSection = exploreActiveSection,
                sharedState = sharedState,
                onDismissOverlay = { activeOverlay = null },
                onDrawerDestinationClick = onDrawerDestinationClick,
                accountSwitcherCallbacks = accountSwitcherCallbacks,
                navController = navController,
            )


            AnchoredBubble(
                anchor = exploreAnchor,
                text = stringResource(id = R.string.explore_double_tap_hint_text),
                visible = false,
                onDismiss = { mainEventPublisher(MainContract.UiEvent.DismissExploreHint) },
                placement = BubblePlacement.Above,
            )
        },
        overlayCoversTopBar = false,
        navigationOverlay = {
            OutkastGestureNavigation(
                activeDestination = activeTab,
                orbitVisible = orbitVisible,
                badges = mainState.badges.copy(
                    unreadFeedCount = if (homeHasNewNotes) 1 else 0,
                ),
                homeFeeds = homeState.feeds.ifEmpty {
                    defaultLibreNostrNoteFeeds(mainState.activeAccountId).map { it.asFeedUi() }
                },
                activeHomeFeed = sharedState.homeActiveFeed.value,
                onOpenOrbit = { orbitVisible = true },
                onDismissOrbit = { orbitVisible = false },
                onDestinationChanged = { destination ->
                    orbitVisible = false
                    if (destination == activeTab) onActiveDestinationClick() else onTabChanged(destination)
                },
                onBookmarksClick = {
                    orbitVisible = false
                    onDrawerDestinationClick(
                        DrawerScreenDestination.Bookmarks(userId = mainState.activeAccountId),
                    )
                },
                onFeedSelected = { feed ->
                    orbitVisible = false
                    sharedState.homeActiveFeed.value = feed
                    sharedState.homeScrollToFeed.value = feed
                    onTabChanged(PrimalTopLevelDestination.Feeds)
                },
                onEditAdvancedSearch = { feedSpec ->
                    orbitVisible = false
                    navController.navigateToAdvancedSearch(editingFeedSpec = feedSpec)
                },
                onProfileClick = {
                    orbitVisible = false
                    if (mainState.activeAccountId.isNotEmpty()) {
                        navController.navigateToProfile(profileId = mainState.activeAccountId)
                    }
                },
                onSettingsClick = {
                    orbitVisible = false
                    navController.navigateToSettings()
                },
            )
        },
        floatingActionButton = { MainScreenFab(navController = navController) },
        snackbarHost = {
            SnackbarHost(hostState = sharedState.snackbarHostState)
        },
    )
}

@Composable
private fun MainScreenFab(navController: NavController) {
    NewPostFloatingActionButton(
        onNewPostClick = { navController.navigateToNoteEditor(null) },
    )
}

private val OUTKAST_WORLDS = listOf(
    PrimalTopLevelDestination.Feeds,
    PrimalTopLevelDestination.Explore,
    PrimalTopLevelDestination.Reads,
    PrimalTopLevelDestination.Alerts,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutkastGestureNavigation(
    activeDestination: PrimalTopLevelDestination,
    orbitVisible: Boolean,
    badges: net.primal.android.user.domain.Badges,
    homeFeeds: List<FeedUi>,
    activeHomeFeed: FeedUi?,
    onOpenOrbit: () -> Unit,
    onDismissOrbit: () -> Unit,
    onDestinationChanged: (PrimalTopLevelDestination) -> Unit,
    onBookmarksClick: () -> Unit,
    onFeedSelected: (FeedUi) -> Unit,
    onEditAdvancedSearch: (String) -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThreshold = with(density) { 64.dp.toPx() }
    val activeIndex = OUTKAST_WORLDS.indexOf(activeDestination).coerceAtLeast(0)

    Box(modifier = Modifier.fillMaxSize()) {
        OutkastEdgeGesture(
            modifier = Modifier.align(Alignment.CenterStart),
            direction = 1f,
            threshold = swipeThreshold,
            onTriggered = {
                OUTKAST_WORLDS.getOrNull(activeIndex - 1)?.let(onDestinationChanged)
            },
        )
        OutkastEdgeGesture(
            modifier = Modifier.align(Alignment.CenterEnd),
            direction = -1f,
            threshold = swipeThreshold,
            onTriggered = {
                OUTKAST_WORLDS.getOrNull(activeIndex + 1)?.let(onDestinationChanged)
            },
        )

        OutkastPortalHandle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp),
            activeDestination = activeDestination,
            pageIndex = activeIndex,
            onOpenOrbit = onOpenOrbit,
        )
    }

    if (orbitVisible) {
        OutkastOrbitSheet(
            activeDestination = activeDestination,
            badges = badges,
            homeFeeds = homeFeeds,
            activeHomeFeed = activeHomeFeed,
            onDismiss = onDismissOrbit,
            onDestinationChanged = onDestinationChanged,
            onBookmarksClick = onBookmarksClick,
            onFeedSelected = onFeedSelected,
            onEditAdvancedSearch = onEditAdvancedSearch,
            onProfileClick = onProfileClick,
            onSettingsClick = onSettingsClick,
        )
    }
}

@Composable
private fun OutkastEdgeGesture(
    modifier: Modifier,
    direction: Float,
    threshold: Float,
    onTriggered: () -> Unit,
) {
    var accumulatedDrag by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .width(22.dp)
            .fillMaxHeight()
            .pointerInput(direction) {
                detectDragGestures(
                    onDragStart = { accumulatedDrag = 0f },
                    onDrag = { change, amount ->
                        accumulatedDrag += amount.x
                        change.consume()
                    },
                    onDragEnd = {
                        if (accumulatedDrag * direction > threshold) onTriggered()
                        accumulatedDrag = 0f
                    },
                    onDragCancel = { accumulatedDrag = 0f },
                )
            },
    )
}

@Composable
private fun OutkastPortalHandle(
    modifier: Modifier,
    activeDestination: PrimalTopLevelDestination,
    pageIndex: Int,
    onOpenOrbit: () -> Unit,
) {
    val tokens = AppTheme.outkastTokens
    var verticalDrag by remember { mutableStateOf(0f) }
    Surface(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { verticalDrag = 0f },
                    onDrag = { change, amount ->
                        verticalDrag += amount.y
                        change.consume()
                    },
                    onDragEnd = {
                        if (verticalDrag < -32.dp.toPx()) onOpenOrbit()
                        verticalDrag = 0f
                    },
                )
            },
        onClick = onOpenOrbit,
        shape = RoundedCornerShape(50),
        color = tokens.dockSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, tokens.softOutline),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            OUTKAST_WORLDS.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .size(if (index == pageIndex) 7.dp else 4.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (index == pageIndex) tokens.accent else AppTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        ),
                )
            }
            Text(
                text = activeDestination.outkastLabel(),
                style = AppTheme.typography.labelMedium,
                color = AppTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutkastOrbitSheet(
    activeDestination: PrimalTopLevelDestination,
    badges: net.primal.android.user.domain.Badges,
    homeFeeds: List<FeedUi>,
    activeHomeFeed: FeedUi?,
    onDismiss: () -> Unit,
    onDestinationChanged: (PrimalTopLevelDestination) -> Unit,
    onBookmarksClick: () -> Unit,
    onFeedSelected: (FeedUi) -> Unit,
    onEditAdvancedSearch: (String) -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(OrbitSection.Main) }
    val fallbackActiveFeed = activeHomeFeed ?: homeFeeds.firstOrNull()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            when (section) {
                OrbitSection.Main -> OrbitMainSection(
                    activeDestination = activeDestination,
                    badges = badges,
                    onHomeClick = { section = OrbitSection.Home },
                    onAlgorithmsClick = { section = OrbitSection.Algorithms },
                    onDestinationChanged = onDestinationChanged,
                    onBookmarksClick = onBookmarksClick,
                    onProfileClick = onProfileClick,
                    onSettingsClick = onSettingsClick,
                )
                OrbitSection.Home -> {
                    OrbitSectionHeader(
                        title = stringResource(id = R.string.primary_destination_feed_label),
                        onBack = { section = OrbitSection.Main },
                    )
                    homeFeeds.take(2).forEach { feed ->
                        OrbitFeedChoice(
                            feed = feed,
                            selected = feed.spec == activeHomeFeed?.spec,
                            onClick = { onFeedSelected(feed) },
                        )
                    }
                }
                OrbitSection.Algorithms -> {
                    OrbitSectionHeader(
                        title = stringResource(id = R.string.home_quick_algorithms),
                        onBack = { section = OrbitSection.Main },
                    )
                    if (fallbackActiveFeed != null) {
                        Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                            FeedListOverlayContent(
                                activeFeed = fallbackActiveFeed,
                                feedSpecKind = FeedSpecKind.Notes,
                                onFeedClick = onFeedSelected,
                                onDismiss = { section = OrbitSection.Main },
                                onEditAdvancedSearchFeedClick = onEditAdvancedSearch,
                                applyNavigationBarsPadding = false,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun OutkastOrbitTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val tokens = AppTheme.outkastTokens
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = if (selected) tokens.accentSubtle else AppTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, tokens.accent) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                modifier = Modifier.size(25.dp),
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) tokens.accent else AppTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = label, style = AppTheme.typography.titleMedium)
            if (badge > 0) {
                Text(
                    text = badge.coerceAtMost(99).toString(),
                    style = AppTheme.typography.labelSmall,
                    color = tokens.accent,
                )
            }
        }
    }
}

private enum class OrbitSection { Main, Home, Algorithms }

@Composable
private fun OrbitMainSection(
    activeDestination: PrimalTopLevelDestination,
    badges: net.primal.android.user.domain.Badges,
    onHomeClick: () -> Unit,
    onAlgorithmsClick: () -> Unit,
    onDestinationChanged: (PrimalTopLevelDestination) -> Unit,
    onBookmarksClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Text(
        text = stringResource(id = R.string.outkast_orbit_title),
        style = AppTheme.typography.headlineSmall,
        color = AppTheme.colorScheme.onSurface,
    )
    Text(
        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
        text = stringResource(id = R.string.outkast_orbit_hint),
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.colorScheme.onSurface.copy(alpha = 0.62f),
    )
    OrbitTileRow(
        first = OrbitTileData(
            OutkastNavigationIcons.Home,
            stringResource(id = R.string.primary_destination_feed_label),
            activeDestination == PrimalTopLevelDestination.Feeds,
            badges.unreadFeedCount,
            onHomeClick,
        ),
        second = OrbitTileData(
            OutkastNavigationIcons.Algorithm,
            stringResource(id = R.string.home_quick_algorithms),
            onClick = onAlgorithmsClick,
        ),
    )
    OrbitTileRow(
        first = OrbitTileData(
            OutkastNavigationIcons.LongReads,
            stringResource(id = R.string.primary_destination_reads_label),
            activeDestination == PrimalTopLevelDestination.Reads,
            onClick = { onDestinationChanged(PrimalTopLevelDestination.Reads) },
        ),
        second = OrbitTileData(
            OutkastNavigationIcons.Algorithm,
            stringResource(id = R.string.primary_destination_explore_label),
            activeDestination == PrimalTopLevelDestination.Explore,
            onClick = { onDestinationChanged(PrimalTopLevelDestination.Explore) },
        ),
    )
    OrbitTileRow(
        first = OrbitTileData(
            OutkastNavigationIcons.Notifications,
            stringResource(id = R.string.primary_destination_notifications_label),
            activeDestination == PrimalTopLevelDestination.Alerts,
            badges.unreadNotificationsCount + badges.unreadMessagesCount,
            onClick = { onDestinationChanged(PrimalTopLevelDestination.Alerts) },
        ),
        second = OrbitTileData(
            PrimalIcons.Bookmarks,
            stringResource(id = R.string.home_quick_bookmarks),
            onClick = onBookmarksClick,
        ),
    )
    OrbitTileRow(
        first = OrbitTileData(
            OutkastNavigationIcons.Profile,
            stringResource(id = R.string.primary_destination_you_label),
            onClick = onProfileClick,
        ),
        second = OrbitTileData(
            OutkastNavigationIcons.Settings,
            stringResource(id = R.string.drawer_destination_settings),
            onClick = onSettingsClick,
        ),
    )
}

private data class OrbitTileData(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val badge: Int = 0,
    val onClick: () -> Unit,
)

@Composable
private fun OrbitTileRow(first: OrbitTileData, second: OrbitTileData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        listOf(first, second).forEach { tile ->
            OutkastOrbitTile(
                modifier = Modifier.weight(1f),
                icon = tile.icon,
                label = tile.label,
                selected = tile.selected,
                badge = tile.badge,
                onClick = tile.onClick,
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun OrbitSectionHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(onClick = onBack, shape = RoundedCornerShape(50), color = AppTheme.colorScheme.surfaceVariant) {
            Text(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), text = "←")
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, style = AppTheme.typography.headlineSmall)
    }
}

@Composable
private fun OrbitFeedChoice(feed: FeedUi, selected: Boolean, onClick: () -> Unit) {
    val tokens = AppTheme.outkastTokens
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) tokens.accentSubtle else AppTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, tokens.accent) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = feed.title, style = AppTheme.typography.titleMedium)
            Text(
                modifier = Modifier.padding(top = 3.dp),
                text = feed.description,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        }
    }
}

@Composable
private fun PrimalTopLevelDestination.outkastLabel(): String = when (this) {
    PrimalTopLevelDestination.Feeds -> stringResource(id = R.string.primary_destination_feed_label)
    PrimalTopLevelDestination.Explore -> stringResource(id = R.string.primary_destination_explore_label)
    PrimalTopLevelDestination.Reads -> stringResource(id = R.string.primary_destination_reads_label)
    PrimalTopLevelDestination.Alerts -> stringResource(id = R.string.primary_destination_notifications_label)
    else -> name
}

@Suppress("LongMethod")
@Composable
private fun MainScreenOverlays(
    accountDrawerVisible: Boolean,
    readPickerVisible: Boolean,
    exploreSectionPickerVisible: Boolean,
    exploreActiveSection: ExploreSection,
    sharedState: MainScreenSharedState,
    onDismissOverlay: () -> Unit,
    onDrawerDestinationClick: (DrawerScreenDestination) -> Unit,
    accountSwitcherCallbacks: AccountSwitcherCallbacks,
    navController: NavController,
) {
    PrimalOverlay(
        visible = accountDrawerVisible,
        onDismiss = onDismissOverlay,
    ) {
        PrimalDrawer(
            onDismiss = onDismissOverlay,
            onDrawerDestinationClick = onDrawerDestinationClick,
            onQrCodeClick = { navController.navigateToProfileQrCodeViewer() },
            accountSwitcherCallbacks = accountSwitcherCallbacks,
        )
    }

    val readsActiveFeed = sharedState.readsActiveFeed.value
    if (readsActiveFeed != null) {
        PrimalOverlay(
            visible = readPickerVisible,
            onDismiss = onDismissOverlay,
        ) {
            FeedListOverlayContent(
                activeFeed = readsActiveFeed,
                feedSpecKind = FeedSpecKind.Reads,
                onFeedClick = { feed ->
                    onDismissOverlay()
                    sharedState.readsScrollToFeed.value = feed
                },
                onDismiss = onDismissOverlay,
                onEditAdvancedSearchFeedClick = { feedSpec ->
                    onDismissOverlay()
                    navController.navigateToAdvancedSearch(editingFeedSpec = feedSpec)
                },
            )
        }
    }

    // Notification filters were intentionally removed: the ALL stream is always visible.

    ExploreSectionPickerOverlay(
        visible = exploreSectionPickerVisible,
        activeSection = exploreActiveSection,
        explorePagerState = sharedState.explorePagerState,
        onDismissOverlay = onDismissOverlay,
    )

}

@Composable
private fun ExploreSectionPickerOverlay(
    visible: Boolean,
    activeSection: ExploreSection,
    explorePagerState: PagerState,
    onDismissOverlay: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    PrimalOverlay(visible = visible, onDismiss = onDismissOverlay) {
        ExploreSectionListOverlayContent(
            activeSection = activeSection,
            onSectionClick = { section ->
                scope.launch { explorePagerState.scrollToPage(section.ordinal) }
                onDismissOverlay()
            },
            onDismiss = onDismissOverlay,
        )
    }
}

@Composable
private fun MainScreenSharedEffects(mainViewModel: MainViewModel, navController: NavController) {
    DisposableLifecycleObserverEffect(mainViewModel) {
        when (it) {
            Lifecycle.Event.ON_START -> {
                mainViewModel.setEvent(MainContract.UiEvent.RequestUserDataUpdate)
            }

            else -> Unit
        }
    }

    LaunchedEffect(mainViewModel, mainViewModel.effects) {
        mainViewModel.effects.collect {
            when (it) {
                MainContract.SideEffect.AccountSwitched -> navController.navigateToHome()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberHomeTopAppBarState(): TopAppBarState {
    return rememberSaveable(saver = TopAppBarState.Saver) {
        TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = 0f,
            initialContentOffset = 0f,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberPerTabTopAppBarState(
    activeTab: PrimalTopLevelDestination,
    homeTopAppBarState: TopAppBarState,
): TopAppBarState {
    val readsTopAppBarState = rememberTopAppBarState()
    val exploreTopAppBarState = rememberTopAppBarState()
    val notificationsTopAppBarState = rememberTopAppBarState()

    return when (activeTab) {
        PrimalTopLevelDestination.Feeds -> homeTopAppBarState
        PrimalTopLevelDestination.Reads -> readsTopAppBarState
        PrimalTopLevelDestination.Explore -> exploreTopAppBarState
        PrimalTopLevelDestination.Messages -> homeTopAppBarState
        PrimalTopLevelDestination.Alerts -> notificationsTopAppBarState
        PrimalTopLevelDestination.You,
        PrimalTopLevelDestination.Settings,
        -> homeTopAppBarState
    }
}

private fun handleActiveDestinationClick(
    activeTab: PrimalTopLevelDestination,
    sharedState: MainScreenSharedState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val target = when (activeTab) {
        PrimalTopLevelDestination.Feeds -> sharedState.homeShouldAnimateScrollToTop
        PrimalTopLevelDestination.Reads -> sharedState.readsShouldAnimateScrollToTop
        PrimalTopLevelDestination.Alerts -> sharedState.notificationsShouldAnimateScrollToTop
        PrimalTopLevelDestination.Messages -> null
        PrimalTopLevelDestination.You,
        PrimalTopLevelDestination.Settings,
        PrimalTopLevelDestination.Explore,
        -> null
    }
    target?.let {
        it.value = true
        scope.launch {
            delay(500.milliseconds)
            it.value = false
        }
    }
}

private enum class ActiveOverlay {
    AccountDrawer,
    ReadPicker,
    ExploreSectionPicker,
}
