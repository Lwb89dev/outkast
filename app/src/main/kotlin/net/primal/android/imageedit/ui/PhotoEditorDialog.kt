package net.primal.android.imageedit.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.primal.android.R
import net.primal.android.core.compose.ConfirmActionAlertDialog
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.imageedit.data.PhotoEditExporter
import net.primal.android.imageedit.data.PhotoEditLoader
import net.primal.android.theme.AppTheme

/**
 * The photo editor, full screen: crop, rotate, straighten, filters and the tone and color sliders over the photo at
 * [sourceUri]. Nothing is written until the user presses Done.
 *
 * @param onDone called with the file holding the edited photo, or with null when nothing was changed (or the photo
 *   could not be read as a picture), meaning the caller should use [sourceUri] as it is.
 * @param onCancel called when the user leaves without wanting the photo at all.
 */
@Composable
fun PhotoEditorDialog(sourceUri: Uri, onDone: (editedUri: Uri?) -> Unit, onCancel: () -> Unit) {
    // Keyed by the photo: when the next photo of a queue takes over, the previous editor's scope (and the drawing it
    // still has running) is cancelled with it instead of living on as long as the screen that hosts the editor.
    key(sourceUri) { PhotoEditorDialogContent(sourceUri, onDone, onCancel) }
}

@Composable
private fun PhotoEditorDialogContent(sourceUri: Uri, onDone: (editedUri: Uri?) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(sourceUri) {
        val loader = PhotoEditLoader(context.applicationContext)
        PhotoEditorState(
            sourceUri = sourceUri,
            loader = loader,
            exporter = PhotoEditExporter(context.applicationContext, loader),
            scope = scope,
        )
    }
    var confirmingDiscard by remember { mutableStateOf(false) }
    val leave = { if (state.changed) confirmingDiscard = true else onCancel() }

    // A photo that cannot be read as a picture (a format the system decoder does not know) has nothing to edit: it is
    // attached as it is, without making the user close an empty editor first.
    LaunchedEffect(state.loadFailed) { if (state.loadFailed) onDone(null) }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppTheme.colorScheme.background) {
            EditorBody(state = state, onCancel = leave, onDone = onDone)
        }
    }

    if (confirmingDiscard) {
        ConfirmActionAlertDialog(
            dialogTitle = stringResource(R.string.photo_edit_discard_title),
            dialogText = stringResource(R.string.photo_edit_discard_message),
            confirmText = stringResource(R.string.photo_edit_discard),
            dismissText = stringResource(R.string.photo_edit_keep_editing),
            onConfirmation = {
                confirmingDiscard = false
                onCancel()
            },
            onDismissRequest = { confirmingDiscard = false },
        )
    }
}

@Composable
private fun EditorBody(state: PhotoEditorState, onCancel: () -> Unit, onDone: (Uri?) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        EditorTopBar(state = state, onCancel = onCancel, onDone = onDone)
        if (state.saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.saveFailed) {
            Text(
                text = stringResource(R.string.photo_edit_failed),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                PhotoEditorPreview(state)
            }
        }
        if (!state.loading && !state.loadFailed) PhotoEditorTools(state)
    }
}

@Composable
private fun EditorTopBar(state: PhotoEditorState, onCancel: () -> Unit, onDone: (Uri?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.photo_edit_cancel),
                tint = AppTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.photo_edit_title),
            style = AppTheme.typography.titleMedium,
            color = AppTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = state::revert, enabled = state.changed && !state.saving) {
            Text(stringResource(R.string.photo_edit_reset))
        }
        PrimalLoadingButton(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            height = 36.dp,
            text = stringResource(R.string.photo_edit_done),
            fontSize = 14.sp,
            enabled = !state.loading && !state.saving,
            onClick = { state.finish(onDone) },
        )
    }
}
