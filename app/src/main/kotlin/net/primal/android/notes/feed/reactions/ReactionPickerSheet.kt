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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import net.primal.android.R
import net.primal.android.theme.AppTheme

/** What a heart gets replaced by in one tap, before opening the whole emoji list. */
internal val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "🔥", "😮", "😢")

private const val PICKER_HEIGHT_DP = 360
private const val PICKER_COLUMNS = 8

/**
 * The sheet a long press on a note's heart opens: a few common reactions in one row, and under them
 * every emoji the system knows, so a reaction can be anything. The emoji is handed to [onEmojiSelected]
 * as the reaction's own text (NIP-25 sends the emoji itself as the content of the reaction event), and
 * the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
            EmojiList(onEmojiSelected = onEmojiSelected)
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
            .height(PICKER_HEIGHT_DP.dp),
        factory = {
            EmojiPickerView(context).apply {
                emojiGridColumns = PICKER_COLUMNS
                setOnEmojiPickedListener { item -> onEmojiSelected(item.emoji) }
            }
        },
    )
}
