package net.primal.android.notes.feed.reactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.librenostr.android.R
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.picker.EmojiGrid
import net.primal.android.emoji.picker.EmojiPackTabs
import net.primal.android.emoji.picker.EmojiPickerViewModel
import net.primal.android.emoji.picker.displayTitle
import net.primal.android.emoji.picker.emojiTab
import net.primal.android.theme.AppTheme

/** What a heart gets replaced by in one tap, before opening the whole emoji list. */
internal val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "🔥", "😮", "😢")

private const val PICKER_HEIGHT_DP = 360
private const val KEYBOARD_PICKER_HEIGHT_DP = 280
private const val PICKER_COLUMNS = 8
private const val SYSTEM_EMOJI_TAB_ICON = "\uD83D\uDE00"

/**
 * The sheet a long press on a note's heart opens: a few common reactions in one row, and under them
 * a row of tabs. The first tab is a scrollable list of every emoji the system knows, with a field to
 * type one from the keyboard; the
 * others are the custom emoji packs on the account's emoji list (the ones the composer offers), so a
 * reaction can be one of those too.
 *
 * A system emoji is handed to [onEmojiSelected] as the reaction's own text (NIP-25 sends the emoji
 * itself as the content of the reaction event); a custom one goes to [onCustomEmojiSelected], which
 * sends `:shortcode:` with the `emoji` tag (NIP-30). Either way the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: (CustomEmoji) -> Unit,
    emojiPickerViewModel: EmojiPickerViewModel = hiltViewModel(),
) {
    val packs by emojiPickerViewModel.packs.collectAsState()
    LaunchedEffect(emojiPickerViewModel) { emojiPickerViewModel.onPickerShown() }
    // Kept by address, not position: a refresh from relays can reorder or add packs under the user's finger.
    // Null is the system emoji tab.
    var selectedPackAddress by remember { mutableStateOf<String?>(null) }
    val selectedPack = packs.firstOrNull { it.address == selectedPackAddress }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The emoji list scrolls vertically inside the sheet: without this the sheet's own drag steals the
        // gesture. Tapping outside or going back still closes it.
        sheetGesturesEnabled = selectedPack != null,
        containerColor = AppTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                modifier = Modifier.padding(horizontal = 24.dp),
                text = stringResource(id = R.string.reaction_picker_title),
                style = AppTheme.typography.titleMedium,
                color = AppTheme.colorScheme.onSurface,
            )
            QuickReactionsRow(onEmojiSelected = onEmojiSelected)
            EmojiPackTabs(
                modifier = Modifier.padding(horizontal = 16.dp),
                packs = packs,
                selectedPack = selectedPack,
                onPackClick = { selectedPackAddress = packs.getOrNull(it)?.address },
                leadingTab = {
                    Box(
                        modifier = Modifier.emojiTab(
                            isSelected = selectedPack == null,
                            selectedBorderColor = AppTheme.colorScheme.primary,
                            onClick = { selectedPackAddress = null },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = SYSTEM_EMOJI_TAB_ICON, fontSize = 18.sp)
                    }
                },
            )
            if (selectedPack == null) {
                EmojiList(onEmojiSelected = onEmojiSelected)
                EmojiKeyboardInput(onEmojiSelected = onEmojiSelected)
            } else {
                Text(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                    text = selectedPack.displayTitle(),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EmojiGrid(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PICKER_HEIGHT_DP.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    emojis = selectedPack.emojis,
                    onEmojiClick = onCustomEmojiSelected,
                )
            }
        }
    }
}

@Composable
private fun QuickReactionsRow(onEmojiSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        QUICK_REACTIONS.forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onEmojiSelected(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun EmojiList(onEmojiSelected: (String) -> Unit) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(KEYBOARD_PICKER_HEIGHT_DP.dp),
        factory = {
            EmojiPickerView(context).apply {
                emojiGridColumns = PICKER_COLUMNS
                setOnEmojiPickedListener { item -> onEmojiSelected(item.emoji) }
            }
        },
    )
}

@Composable
private fun EmojiKeyboardInput(onEmojiSelected: (String) -> Unit) {
    var reaction by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = reaction,
            onValueChange = { reaction = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.reaction_keyboard_label)) },
            singleLine = true,
        )
        TextButton(onClick = { onEmojiSelected(reaction.trim()) }, enabled = reaction.isNotBlank()) {
            Text(stringResource(R.string.reaction_keyboard_send))
        }
    }
}
