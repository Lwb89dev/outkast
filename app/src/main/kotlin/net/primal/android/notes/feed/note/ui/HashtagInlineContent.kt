package net.primal.android.notes.feed.note.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.librenostr.android.R
import net.primal.android.notes.feed.model.NOSTR_HASHTAG_INLINE_CONTENT_ID

private val NostrPurple = Color(0xFF8B5CF6)

/** Bundled hashtag decoration, sized with the surrounding text and available offline. */
@Composable
fun rememberHashtagInlineContent(): Map<String, InlineTextContent> =
    remember {
        mapOf(
            NOSTR_HASHTAG_INLINE_CONTENT_ID to InlineTextContent(
                placeholder = Placeholder(
                    width = 1.3.em,
                    height = 1.3.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Image(
                    painter = painterResource(R.drawable.nostr_hashtag_ostrich),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(NostrPurple),
                )
            },
        )
    }
