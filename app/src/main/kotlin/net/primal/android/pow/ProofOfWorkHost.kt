package net.primal.android.pow

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.librenostr.android.R
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import net.primal.android.theme.AppTheme

/** Mining that ends quicker than this is not worth a dialog flashing up. */
private val SHOW_AFTER = 400.milliseconds

/**
 * Says that proof of work is being computed, with a way to stop it, wherever the publish was
 * started from. Calling it off leaves the unsent text where it was.
 */
@Composable
fun ProofOfWorkHost() {
    val viewModel = hiltViewModel<ProofOfWorkViewModel>()
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsState()
    var slow by remember { mutableStateOf(false) }

    val mining = tasks.isNotEmpty()
    LaunchedEffect(mining) {
        slow = false
        if (mining) {
            delay(SHOW_AFTER)
            slow = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.signerDroppedWork.collect {
            Toast.makeText(context, R.string.pow_signer_dropped_work, Toast.LENGTH_LONG).show()
        }
    }

    val task = tasks.firstOrNull()
    if (slow && task != null) {
        AlertDialog(
            containerColor = AppTheme.colorScheme.surfaceVariant,
            // Only the button stops it: a stray tap outside must not throw the work away.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pow_mining_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.pow_mining_description, task.difficulty))
                }
            },
            confirmButton = {
                TextButton(onClick = task.cancel) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
