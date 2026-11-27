package net.primal.android.spam

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librenostr.android.R
import net.primal.android.theme.AppTheme

/** Says how many replies the filter left out, with the way to see them anyway. */
@Composable
fun HiddenRepliesRow(count: Int, onShowClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = pluralStringResource(R.plurals.hidden_replies, count, count),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
        )
        TextButton(onClick = onShowClick) { Text(stringResource(R.string.hidden_replies_show)) }
    }
}
