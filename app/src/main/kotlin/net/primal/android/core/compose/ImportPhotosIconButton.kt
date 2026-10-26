package net.primal.android.core.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aakira.napier.Napier
import net.primal.android.imageedit.ui.rememberPhotoEditGate

@Composable
fun ImportPhotosIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    onPhotosImported: (List<Uri>) -> Unit,
) {
    // A photo goes through the photo editor before it is handed on; a video passes straight through.
    val attach = rememberPhotoEditGate(onReady = onPhotosImported)
    val multiplePhotosImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        Napier.e("uri = $uri")
        if (uri != null) attach(listOf(uri))
    }

    IconButton(
        onClick = {
            multiplePhotosImportLauncher.launch(
                input = PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                ),
            )
        },
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}
