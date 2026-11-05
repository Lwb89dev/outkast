package net.primal.android.notes.feed.note.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.theme.AppTheme

/**
 * NIP-36 cover. The note's text and pictures stay behind it until [onReveal].
 * An empty [reason] means the tag was present and named no reason.
 */
@Composable
fun SensitiveContentNotice(
    reason: String,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = sensitiveContentLabel(reason),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
        )
        Text(
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(onClick = onReveal),
            text = stringResource(R.string.feed_sensitive_content_show),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun sensitiveContentLabel(reason: String): String =
    if (reason.isBlank()) {
        stringResource(R.string.feed_sensitive_content)
    } else {
        stringResource(R.string.feed_sensitive_content_reason, reason)
    }
