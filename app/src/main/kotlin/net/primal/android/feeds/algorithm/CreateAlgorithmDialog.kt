package net.primal.android.feeds.algorithm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.primal.android.R
import net.primal.android.core.compose.UniversalAvatarThumbnail
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.core.compose.profile.model.UserProfileItemUi
import net.primal.android.feeds.algorithm.CreateAlgorithmContract.ImportableList
import net.primal.android.feeds.algorithm.CreateAlgorithmContract.UiEvent
import net.primal.android.theme.AppTheme

/**
 * Full-screen "Create your algorithm": a name, then the people, chosen from the contacts or found with the search box
 * (which also finds people who are not contacts yet), and above them the user's existing lists to import in one tap.
 */
@Composable
fun CreateAlgorithmDialog(viewModel: CreateAlgorithmViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(viewModel) { viewModel.onOpened() }
    LaunchedEffect(state.created) { if (state.created) currentOnDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                TopBar(state = state, onClose = onDismiss, onEvent = viewModel::setEvent)
                PeopleList(state = state, onEvent = viewModel::setEvent)
            }
        }
    }
}

@Composable
private fun TopBar(state: CreateAlgorithmContract.UiState, onClose: () -> Unit, onEvent: (UiEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.accessibility_close),
                tint = AppTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.algorithms_create_button),
            style = AppTheme.typography.titleMedium,
            color = AppTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        PrimalLoadingButton(
            modifier = Modifier.padding(end = 8.dp),
            height = 36.dp,
            text = stringResource(R.string.algorithm_create_submit),
            fontSize = 14.sp,
            enabled = state.canCreate,
            loading = state.saving,
            onClick = { onEvent(UiEvent.Create) },
        )
    }
}

@Composable
private fun PeopleList(state: CreateAlgorithmContract.UiState, onEvent: (UiEvent) -> Unit) {
    // Filtering thousands of contacts and finding who is chosen from outside them is only redone when its own inputs
    // change; typing in the name box changes neither and used to redo both on every keystroke.
    val visibleContacts = remember(state.contacts, state.query) { state.visibleContacts }
    val chosenElsewhere = remember(state.contacts, state.selected) { state.selectedOutsideContacts }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "name") {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                value = state.name,
                onValueChange = { onEvent(UiEvent.SetName(it)) },
                label = { Text(stringResource(R.string.algorithm_create_name_label)) },
                singleLine = true,
            )
        }
        if (state.failed) {
            item(key = "failed") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    text = stringResource(R.string.algorithm_create_failed),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colorScheme.error,
                )
            }
        }
        importSection(lists = state.importableLists, onImport = { onEvent(UiEvent.ImportList(it)) })
        item(key = "people-title") {
            SectionHeader(
                title = stringResource(R.string.algorithm_create_people_title),
                trailing = stringResource(R.string.algorithm_create_selected, state.selected.size),
            )
        }
        item(key = "search") {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                value = state.query,
                onValueChange = { onEvent(UiEvent.SetQuery(it)) },
                placeholder = { Text(stringResource(R.string.algorithm_create_search_hint)) },
                singleLine = true,
            )
        }
        peopleSections(
            state = state,
            visibleContacts = visibleContacts,
            chosenElsewhere = chosenElsewhere,
            onToggle = { onEvent(UiEvent.ToggleProfile(it)) },
        )
    }
}

private fun LazyListScope.importSection(
    lists: List<ImportableList>,
    onImport: (ImportableList) -> Unit,
) {
    if (lists.isEmpty()) return
    item(key = "import-title") { SectionHeader(title = stringResource(R.string.algorithm_create_import_title)) }
    items(lists, key = { "list-${it.identifier}" }) { list ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = list.title,
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.algorithm_create_import_members, list.memberCount),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                )
            }
            TextButton(onClick = { onImport(list) }, enabled = !list.added) {
                Text(
                    stringResource(
                        if (list.added) R.string.algorithm_create_import_added else R.string.algorithm_create_import_add,
                    ),
                )
            }
        }
    }
}

private fun LazyListScope.peopleSections(
    state: CreateAlgorithmContract.UiState,
    visibleContacts: List<UserProfileItemUi>,
    chosenElsewhere: List<UserProfileItemUi>,
    onToggle: (UserProfileItemUi) -> Unit,
) {
    val searching = state.query.isNotBlank()
    if (!searching && chosenElsewhere.isNotEmpty()) {
        items(chosenElsewhere, key = { "chosen-${it.profileId}" }) { person ->
            PersonRow(person = person, checked = true, onToggle = onToggle)
        }
    }
    val contacts = visibleContacts
    if (contacts.isNotEmpty()) {
        item(key = "contacts-title") { SectionHeader(title = stringResource(R.string.algorithm_create_contacts_header)) }
        items(contacts, key = { "contact-${it.profileId}" }) { person ->
            PersonRow(person = person, checked = person.profileId in state.selected, onToggle = onToggle)
        }
    }
    if (searching && state.searchResults.isNotEmpty()) {
        item(key = "others-title") { SectionHeader(title = stringResource(R.string.algorithm_create_others_header)) }
        items(state.searchResults, key = { "found-${it.profileId}" }) { person ->
            PersonRow(person = person, checked = person.profileId in state.selected, onToggle = onToggle)
        }
    }
    item(key = "status") { PeopleStatus(state = state, hasContacts = contacts.isNotEmpty()) }
}

@Composable
private fun PeopleStatus(state: CreateAlgorithmContract.UiState, hasContacts: Boolean) {
    val message = when {
        state.searching -> null
        state.query.isNotBlank() && !hasContacts && state.searchResults.isEmpty() ->
            stringResource(R.string.algorithm_create_no_results)
        state.query.isBlank() && state.contacts.isEmpty() && !state.loadingContacts ->
            stringResource(R.string.algorithm_create_no_contacts)
        else -> return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message == null) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.algorithm_create_searching),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
            )
        } else {
            Text(
                text = message,
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = AppTheme.typography.titleSmall,
            color = AppTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
            )
        }
    }
}

@Composable
private fun PersonRow(person: UserProfileItemUi, checked: Boolean, onToggle: (UserProfileItemUi) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(person) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UniversalAvatarThumbnail(
            avatarSize = 40.dp,
            avatarCdnImage = person.avatarCdnImage,
            avatarBlossoms = person.avatarBlossoms,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = person.displayName,
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            person.internetIdentifier?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle(person) })
    }
}
