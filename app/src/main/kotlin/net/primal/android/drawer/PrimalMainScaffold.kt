package net.primal.android.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.stream.player.LocalStreamState

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun PrimalMainScaffold(
    modifier: Modifier = Modifier,
    topAppBarState: TopAppBarState = remember {
        TopAppBarState(
            initialHeightOffsetLimit = -Float.MAX_VALUE,
            initialHeightOffset = 0f,
            initialContentOffset = 0f,
        )
    },
    topAppBar: @Composable (TopAppBarScrollBehavior?) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit = {},
    floatingNewDataHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    navigationOverlay: @Composable () -> Unit = {},
    overlayCoversTopBar: Boolean = false,
    focusModeEnabled: Boolean = true,
) {
    val localDensity = LocalDensity.current
    val streamState = LocalStreamState.current
    val topAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    val focusModeOn by remember(topAppBarState) {
        derivedStateOf { topAppBarState.collapsedFraction > FOCUS_MODE_COLLAPSE_THRESHOLD }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PrimalScaffold(
            modifier = modifier.then(
                if (focusModeEnabled) {
                    Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                } else {
                    Modifier
                },
            ),
            topBar = {
                topAppBar(if (focusModeEnabled) topAppBarScrollBehavior else null)
            },
            content = { paddingValues ->
                ScaffoldContent(
                    paddingValues = paddingValues,
                    focusModeOn = focusModeOn,
                    collapsedFraction = topAppBarState.collapsedFraction,
                    content = content,
                    floatingNewDataHost = floatingNewDataHost,
                )
            },
            floatingActionButton = floatingActionButton,
            snackbarHost = snackbarHost,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (overlayCoversTopBar) {
                        Modifier.padding(
                            top = with(localDensity) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            },
                        )
                    } else {
                        Modifier.padding(top = with(localDensity) { streamState.topBarHeight.toDp() })
                    },
                )
                .clipToBounds(),
        ) {
            overlay()
        }

        navigationOverlay()
    }
}

@Composable
private fun ScaffoldContent(
    paddingValues: PaddingValues,
    focusModeOn: Boolean,
    collapsedFraction: Float,
    content: @Composable (PaddingValues) -> Unit,
    floatingNewDataHost: @Composable () -> Unit,
) {
    Box {
        content(paddingValues)

        AnimatedVisibility(
            visible = !focusModeOn,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .padding(paddingValues)
                .padding(top = FloatingNewDataHostTopPadding)
                .wrapContentHeight()
                .wrapContentWidth()
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    this.alpha = (1 - collapsedFraction) * 1.0f
                },
        ) {
            floatingNewDataHost()
        }
    }
}

private const val FOCUS_MODE_COLLAPSE_THRESHOLD = 0.5f

val FloatingNewDataHostTopPadding = 42.dp
