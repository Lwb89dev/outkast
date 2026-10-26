package net.primal.android.feeds.algorithm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.primal.android.R
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.feeds.list.ui.model.FeedUi
import net.primal.android.theme.AppTheme

private val ALGORITHMS_MAX_HEIGHT = 320.dp

/**
 * "Your algorithms" in the Discover panel of Orbit: the lists the user created or imported, to pick one as the feed,
 * and the way to make a new one. With none yet, the empty state says what an algorithm is and offers to create the
 * first one.
 */
@Composable
fun YourAlgorithmsPanel(
    activeFeedSpec: String?,
    onFeedSelected: (FeedUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<YourAlgorithmsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Created with the panel rather than with the dialog: the contacts and the user's lists start loading while the
    // panel is on screen, so by the time "Create" is tapped there is something to show.
    val creator = hiltViewModel<CreateAlgorithmViewModel>(key = "CreateAlgorithmViewModel")
    LaunchedEffect(creator) { creator.prepare() }
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) CreateAlgorithmDialog(viewModel = creator, onDismiss = { creating = false })

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            modifier = Modifier.padding(top = 18.dp),
            text = stringResource(R.string.algorithms_your_title),
            style = AppTheme.typography.titleMedium,
            color = AppTheme.colorScheme.onSurface,
        )
        when {
            state.loading -> Unit
            state.algorithms.isEmpty() -> EmptyAlgorithms(onCreate = { creating = true })
            else -> {
                Column(
                    modifier = Modifier.heightIn(max = ALGORITHMS_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.algorithms.forEach { feed ->
                        AlgorithmCard(
                            feed = feed,
                            selected = feed.spec == activeFeedSpec,
                            onClick = { onFeedSelected(feed) },
                            onRemove = { viewModel.remove(feed) },
                        )
                    }
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { creating = true }) {
                    Text(stringResource(R.string.algorithms_create_another_button))
                }
            }
        }
    }
}

@Composable
private fun EmptyAlgorithms(onCreate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.algorithms_empty_title),
                style = AppTheme.typography.titleMedium,
                color = AppTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.algorithms_empty_description),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
            PrimalLoadingButton(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                height = 44.dp,
                text = stringResource(R.string.algorithms_create_button),
                onClick = onCreate,
            )
        }
    }
}

@Composable
private fun AlgorithmCard(feed: FeedUi, selected: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val tokens = AppTheme.outkastTokens
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) tokens.accentSubtle else AppTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, tokens.accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = feed.title,
                    style = AppTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (feed.description.isNotBlank()) {
                    Text(
                        modifier = Modifier.padding(top = 3.dp),
                        text = feed.description,
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.algorithms_remove),
                    tint = AppTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
            }
        }
    }
}
