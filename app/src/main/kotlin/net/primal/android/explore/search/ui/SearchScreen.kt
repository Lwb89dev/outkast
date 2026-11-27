package net.primal.android.explore.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librenostr.android.R
import kotlinx.coroutines.launch
import net.primal.android.articles.feed.ArticleFeedList
import net.primal.android.core.compose.AppBarIcon
import net.primal.android.core.compose.OutkastLoadingSpinner
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.AdvancedSearch
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.profile.model.UserProfileItemUi
import net.primal.android.explore.search.SearchContract
import net.primal.android.explore.search.SearchContract.UiEvent
import net.primal.android.explore.search.SearchViewModel
import net.primal.android.explore.search.asHashtagSuggestion
import net.primal.android.notes.feed.grid.MediaFeedGrid
import net.primal.android.notes.feed.list.NoteFeedList
import net.primal.android.theme.AppTheme
import net.primal.domain.feeds.buildAdvancedSearchNotesFeedSpec
import net.primal.domain.feeds.buildAdvancedSearchNotificationsFeedSpec
import net.primal.domain.feeds.buildAdvancedSearchReadsFeedSpec

private const val PEOPLE_ON_TOP = 3

/** The result tabs, as on X. Reads and notifications only have what makes sense for them. */
private enum class SearchTab { Top, Latest, People, Media }

private fun SearchScope.tabs(): List<SearchTab> =
    when (this) {
        SearchScope.Notes -> SearchTab.entries
        SearchScope.Reads -> listOf(SearchTab.Latest, SearchTab.People)
        SearchScope.MyNotifications -> listOf(SearchTab.Latest)
    }

private fun SearchTab.titleRes(): Int =
    when (this) {
        SearchTab.Top -> R.string.search_tab_top
        SearchTab.Latest -> R.string.search_tab_latest
        SearchTab.People -> R.string.search_tab_people
        SearchTab.Media -> R.string.search_tab_media
    }

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    searchScope: SearchScope,
    callbacks: SearchContract.ScreenCallbacks,
) {
    val uiState = viewModel.state.collectAsState()
    SearchScreen(
        state = uiState.value,
        searchScope = searchScope,
        eventPublisher = { viewModel.setEvent(it) },
        callbacks = callbacks,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchContract.UiState,
    searchScope: SearchScope,
    eventPublisher: (UiEvent) -> Unit,
    callbacks: SearchContract.ScreenCallbacks,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit: (String) -> Unit = { query ->
        if (query.isNotBlank()) {
            keyboardController?.hide()
            focusManager.clearFocus()
            eventPublisher(UiEvent.SearchSubmitted(query = query))
        }
    }

    PrimalScaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    AppBarIcon(
                        icon = PrimalIcons.ArrowBack,
                        onClick = callbacks.onClose,
                        appBarIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                    )
                },
                title = {
                    SearchTextField(
                        query = state.searchQuery,
                        // With results already on screen the keyboard would only cover them.
                        requestFocusOnStart = state.submittedQuery == null,
                        onQueryChange = { eventPublisher(UiEvent.SearchQueryUpdated(query = it)) },
                        onSearch = { submit(state.searchQuery) },
                    )
                },
                actions = {
                    val submitted = state.submittedQuery
                    if (submitted != null && searchScope == SearchScope.Notes) {
                        val spec = submitted.toNotesSpec(state.onlyFollows)
                        IconButton(onClick = { callbacks.onOpenAsFeedClick(spec) }) {
                            Icon(
                                imageVector = Icons.Outlined.BookmarkAdd,
                                contentDescription = stringResource(id = R.string.search_open_as_feed),
                            )
                        }
                    }
                    AppBarIcon(
                        icon = PrimalIcons.AdvancedSearch,
                        onClick = { callbacks.onAdvancedSearchClick(state.searchQuery) },
                        appBarIconContentDescription = stringResource(id = R.string.accessibility_search),
                    )
                },
            )
        },
        content = { paddingValues ->
            val submitted = state.submittedQuery
            if (submitted == null) {
                Suggestions(
                    state = state,
                    contentPadding = paddingValues,
                    onSubmit = submit,
                    eventPublisher = eventPublisher,
                    onProfileClick = callbacks.onProfileClick,
                )
            } else {
                SearchResults(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    query = submitted,
                    state = state,
                    searchScope = searchScope,
                    eventPublisher = eventPublisher,
                    callbacks = callbacks,
                )
            }
        },
    )
}

/** What is offered while typing: with an empty field the history, otherwise ways to go on. */
@Composable
private fun Suggestions(
    state: SearchContract.UiState,
    contentPadding: PaddingValues,
    onSubmit: (String) -> Unit,
    eventPublisher: (UiEvent) -> Unit,
    onProfileClick: (String) -> Unit,
) {
    val query = state.searchQuery.trim()
    val openProfile: (UserProfileItemUi) -> Unit = {
        eventPublisher(UiEvent.ProfileSelected(profileId = it.profileId))
        onProfileClick(it.profileId)
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (query.isEmpty()) {
            if (state.recentSearches.isNotEmpty()) {
                item(key = "history-header") {
                    SectionHeader(
                        title = stringResource(R.string.search_recent),
                        action = stringResource(R.string.search_clear_all),
                        onAction = { eventPublisher(UiEvent.ClearRecentSearches) },
                    )
                }
                items(items = state.recentSearches, key = { "history-$it" }) { recent ->
                    SuggestionRow(
                        icon = Icons.Outlined.History,
                        text = recent,
                        onClick = { onSubmit(recent) },
                        onRemove = { eventPublisher(UiEvent.RemoveRecentSearch(recent)) },
                    )
                }
            } else if (state.recentUsers.isEmpty()) {
                item(key = "hint") {
                    Text(
                        modifier = Modifier.padding(24.dp),
                        text = stringResource(R.string.search_operators_hint),
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
                    )
                }
            }
            items(items = state.recommendedUsers, key = { "recent-${it.profileId}" }) {
                UserProfileListItem(data = it, onClick = openProfile)
            }
        } else {
            item(key = "search-text") {
                SuggestionRow(
                    icon = Icons.Filled.Search,
                    text = stringResource(R.string.search_for, query),
                    onClick = { onSubmit(query) },
                )
            }
            query.asHashtagSuggestion()?.let { hashtag ->
                item(key = "search-hashtag") {
                    SuggestionRow(icon = Icons.Filled.Search, text = hashtag, onClick = { onSubmit(hashtag) })
                }
            }
            items(items = state.searchResults, key = { "match-${it.profileId}" }) {
                UserProfileListItem(data = it, onClick = openProfile)
            }
            if (state.searching) {
                item(key = "searching") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OutkastLoadingSpinner()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = AppTheme.typography.titleMedium, color = AppTheme.colorScheme.onSurface)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = AppTheme.colorScheme.surfaceVariant),
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        headlineContent = { Text(text = text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = onRemove?.let { remove ->
            {
                IconButton(onClick = remove) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_remove_recent),
                    )
                }
            }
        },
    )
}

private fun String.withScope(onlyFollows: Boolean) = if (onlyFollows) "$this scope:myfollows" else this

private fun String.toNotesSpec(onlyFollows: Boolean) = buildAdvancedSearchNotesFeedSpec(withScope(onlyFollows))

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod") // The tab row, the filter chips and one branch per tab.
@Composable
private fun SearchResults(
    query: String,
    state: SearchContract.UiState,
    searchScope: SearchScope,
    eventPublisher: (UiEvent) -> Unit,
    callbacks: SearchContract.ScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    val tabs = searchScope.tabs()
    // Keyed by the query: a new search starts again from the first tab.
    var selected by rememberSaveable(query) { mutableStateOf(0) }
    val pagerState = rememberPagerState(initialPage = selected) { tabs.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) { selected = pagerState.currentPage }

    Column(modifier = modifier) {
        if (tabs.size > 1) {
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = AppTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(text = stringResource(tab.titleRes()), maxLines = 1) },
                        unselectedContentColor = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
                    )
                }
            }
        }
        if (searchScope == SearchScope.Notes && tabs[pagerState.currentPage] != SearchTab.People) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colorScheme.background)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !state.onlyFollows,
                    onClick = { eventPublisher(UiEvent.OnlyFollowsChanged(false)) },
                    label = { Text(stringResource(R.string.search_from_anyone)) },
                )
                FilterChip(
                    selected = state.onlyFollows,
                    onClick = { eventPublisher(UiEvent.OnlyFollowsChanged(true)) },
                    label = { Text(stringResource(R.string.search_from_follows)) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (tabs[page]) {
                SearchTab.Top -> NoteResults(
                    feedSpec = "$query orderby:top".toNotesSpec(state.onlyFollows),
                    people = state.searchResults.take(PEOPLE_ON_TOP),
                    callbacks = callbacks,
                )

                SearchTab.Latest -> when (searchScope) {
                    SearchScope.Notes -> NoteResults(
                        feedSpec = query.toNotesSpec(state.onlyFollows),
                        people = emptyList(),
                        callbacks = callbacks,
                    )

                    SearchScope.MyNotifications -> NoteResults(
                        feedSpec = buildAdvancedSearchNotificationsFeedSpec(query),
                        people = emptyList(),
                        callbacks = callbacks,
                    )

                    SearchScope.Reads -> ArticleFeedList(
                        modifier = Modifier.fillMaxSize(),
                        feedSpec = buildAdvancedSearchReadsFeedSpec(query),
                        onArticleClick = callbacks.noteCallbacks.onArticleClick ?: callbacks.onNaddrClick,
                        onGetPremiumClick = callbacks.noteCallbacks.onGetPrimalPremiumClick ?: {},
                        contentPadding = PaddingValues(0.dp),
                    )
                }

                SearchTab.People -> PeopleResults(state = state, onProfileClick = callbacks.onProfileClick)

                SearchTab.Media -> MediaFeedGrid(
                    modifier = Modifier.fillMaxSize(),
                    feedSpec = "$query filter:media".toNotesSpec(state.onlyFollows),
                    onNoteClick = { callbacks.noteCallbacks.onNoteClick?.invoke(it) },
                    onGetPrimalPremiumClick = { callbacks.noteCallbacks.onGetPrimalPremiumClick?.invoke() },
                )
            }
        }
    }
}

@Composable
private fun NoteResults(
    feedSpec: String,
    people: List<UserProfileItemUi>,
    callbacks: SearchContract.ScreenCallbacks,
) {
    NoteFeedList(
        feedSpec = feedSpec,
        noteCallbacks = callbacks.noteCallbacks,
        contentPadding = PaddingValues(0.dp),
        pollingEnabled = false,
        noContentText = stringResource(R.string.search_no_results),
        header = if (people.isEmpty()) {
            null
        } else {
            {
                Column {
                    people.forEach { person ->
                        UserProfileListItem(data = person, onClick = { callbacks.onProfileClick(it.profileId) })
                    }
                }
            }
        },
    )
}

@Composable
private fun PeopleResults(state: SearchContract.UiState, onProfileClick: (String) -> Unit) {
    when {
        state.searchResults.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.searching) {
                OutkastLoadingSpinner()
            } else {
                Text(
                    text = stringResource(R.string.search_no_results),
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
                )
            }
        }

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = state.searchResults, key = { it.profileId }) {
                UserProfileListItem(data = it, onClick = { item -> onProfileClick(item.profileId) })
            }
        }
    }
}

@Composable
fun SearchTextField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    requestFocusOnStart: Boolean = true,
    onSearch: () -> Unit,
) {
    var focusRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusRequester) {
        if (!focusRequested) {
            if (requestFocusOnStart) focusRequester.requestFocus()
            focusRequested = true
        }
    }

    var fieldValue by remember { mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length))) }
    LaunchedEffect(query) {
        if (fieldValue.text != query) {
            fieldValue = TextFieldValue(text = query, selection = TextRange(query.length))
        }
    }

    OutlinedTextField(
        modifier = Modifier.focusRequester(focusRequester),
        value = fieldValue,
        onValueChange = { newValue ->
            fieldValue = newValue
            if (newValue.text != query) {
                onQueryChange(newValue.text)
            }
        },
        trailingIcon = if (fieldValue.text.isEmpty()) {
            null
        } else {
            {
                IconButton(
                    onClick = {
                        fieldValue = TextFieldValue()
                        onQueryChange("")
                        focusRequester.requestFocus()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_clear_text),
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSearch()
            },
        ),
        singleLine = true,
    )
}
