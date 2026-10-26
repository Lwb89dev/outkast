package net.primal.android.core.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import net.primal.android.BuildConfig
import net.primal.android.imageedit.ui.rememberPhotoEditGate
import net.primal.core.utils.runCatching

fun Context.createImageFile(): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val imageFileName = "JPEG_" + timeStamp + "_"
    return File.createTempFile(
        imageFileName,
        ".jpg",
        externalCacheDir,
    )
}

@Composable
fun TakePhotoIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    onPhotoTaken: (Uri) -> Unit,
) {
    val context = LocalContext.current

    // The file the camera writes to is created when the button is tapped, one per photo. It used to be created on
    // every recomposition (which left a stray file behind each time, and opening and closing the photo editor
    // recomposes the button), and a single remembered file would let a second photo overwrite one that is still
    // attached to the note.
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    // The photo just taken opens in the photo editor before it is handed on.
    val attach = rememberPhotoEditGate(onReady = { photos -> photos.forEach(onPhotoTaken) })
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        pendingUri?.takeIf { captured }?.let { attach(listOf(it)) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingUri?.let(cameraLauncher::launch)
    }

    IconButton(
        onClick = {
            val uri = runCatching {
                FileProvider.getUriForFile(
                    Objects.requireNonNull(context),
                    BuildConfig.APPLICATION_ID + ".provider",
                    context.createImageFile(),
                )
            }.getOrNull() ?: return@IconButton
            pendingUri = uri
            val cameraPermissionResult = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            if (cameraPermissionResult == PackageManager.PERMISSION_GRANTED) {
                cameraLauncher.launch(uri)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}
