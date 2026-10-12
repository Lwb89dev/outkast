package net.primal.android.events.reactions.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.ListNoContent
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.heightAdjustableLoadingLazyListPlaceholder
import net.primal.android.notes.feed.model.FeedPostUi
import net.primal.android.notes.feed.note.FeedNoteCard
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks

/**
 * The notes that quote a note, as notes: each one a full card, and a tap anywhere on it opens that
 * note's thread, so the list is a way to read the quotes and not only a list of who wrote them.
 */
@Composable
fun QuotesLazyColumn(
    modifier: Modifier,
    quotes: List<FeedPostUi>,
    loading: Boolean,
    noContentText: String,
    onNoteClick: (noteId: String) -> Unit,
    onProfileClick: (profileId: String) -> Unit,
) {
    val noteCallbacks = NoteCallbacks(onNoteClick = onNoteClick, onProfileClick = onProfileClick)

    LazyColumn(modifier = modifier) {
        items(items = quotes, key = { it.postId }) { quote ->
            Column {
                FeedNoteCard(
                    data = quote,
                    shape = RectangleShape,
                    cardPadding = PaddingValues(vertical = 4.dp),
                    headerSingleLine = true,
                    showReplyTo = false,
                    noteCallbacks = noteCallbacks,
                )
                PrimalDivider()
            }
        }

        if (quotes.isEmpty()) {
            if (loading) {
                heightAdjustableLoadingLazyListPlaceholder(height = QUOTE_PLACEHOLDER_HEIGHT)
            } else {
                item(contentType = "NoContent") {
                    ListNoContent(
                        modifier = Modifier.fillParentMaxSize(),
                        noContentText = noContentText,
                        refreshButtonVisible = false,
                    )
                }
            }
        }
    }
}

private val QUOTE_PLACEHOLDER_HEIGHT = 120.dp
