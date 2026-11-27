package net.primal.android.settings.pow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librenostr.android.R
import java.text.NumberFormat
import kotlin.math.roundToInt
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalSwitch
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.settings.SettingsItem
import net.primal.android.pow.PowSettings
import net.primal.android.theme.AppTheme
import net.primal.domain.nostr.pow.ProofOfWork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowSettingsScreen(
    viewModel: PowSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val settings by viewModel.state.collectAsState()

    if (embedded) {
        PowSettingsContent(settings = settings, onChange = viewModel::update)
    } else {
        PrimalScaffold(
            topBar = {
                PrimalTopAppBar(
                    title = stringResource(id = R.string.settings_pow_title),
                    navigationIcon = PrimalIcons.ArrowBack,
                    navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                    onNavigationIconClick = onClose,
                )
            },
            content = { padding ->
                PowSettingsContent(
                    settings = settings,
                    onChange = viewModel::update,
                    modifier = Modifier.padding(padding),
                )
            },
        )
    }
}

@Composable
private fun PowSettingsContent(
    settings: PowSettings?,
    onChange: (PowSettings.() -> PowSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (settings == null) return
    Column(
        modifier = modifier
            .background(color = AppTheme.colorScheme.surfaceVariant)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Explanation(text = stringResource(R.string.pow_send_explanation))
        PowOption(
            title = stringResource(R.string.pow_notes),
            enabled = settings.notes,
            bits = settings.notesBits,
            onEnabledChange = { onChange { copy(notes = it) } },
            onBitsChange = { onChange { copy(notesBits = it) } },
        )
        PowOption(
            title = stringResource(R.string.pow_reactions),
            enabled = settings.reactions,
            bits = settings.reactionsBits,
            onEnabledChange = { onChange { copy(reactions = it) } },
            onBitsChange = { onChange { copy(reactionsBits = it) } },
        )
        PowOption(
            title = stringResource(R.string.pow_messages),
            enabled = settings.messages,
            bits = settings.messagesBits,
            onEnabledChange = { onChange { copy(messages = it) } },
            onBitsChange = { onChange { copy(messagesBits = it) } },
        )
        PrimalDivider()
        PowOption(
            title = stringResource(R.string.pow_filter),
            description = stringResource(R.string.pow_filter_explanation),
            enabled = settings.filter,
            bits = settings.filterBits,
            showAttempts = false,
            onEnabledChange = { onChange { copy(filter = it) } },
            onBitsChange = { onChange { copy(filterBits = it) } },
        )
    }
}

@Composable
private fun PowOption(
    title: String,
    enabled: Boolean,
    bits: Int,
    onEnabledChange: (Boolean) -> Unit,
    onBitsChange: (Int) -> Unit,
    description: String? = null,
    showAttempts: Boolean = true,
) {
    SettingsItem(
        headlineText = title,
        supportText = description,
        trailingContent = { PrimalSwitch(checked = enabled, onCheckedChange = onEnabledChange) },
        onClick = { onEnabledChange(!enabled) },
    )
    if (!enabled) return

    // Follows the finger, stored only when it lets go.
    var sliding by remember(bits) { mutableFloatStateOf(bits.toFloat()) }
    val shown = sliding.roundToInt()
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = if (showAttempts) {
                val attempts = NumberFormat.getIntegerInstance().format(1 shl shown)
                stringResource(R.string.pow_bits_with_attempts, shown, attempts)
            } else {
                stringResource(R.string.pow_bits_minimum, shown)
            },
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliding,
            onValueChange = { sliding = it },
            onValueChangeFinished = { onBitsChange(sliding.roundToInt()) },
            valueRange = ProofOfWork.MIN_DIFFICULTY.toFloat()..ProofOfWork.MAX_DIFFICULTY.toFloat(),
            steps = ProofOfWork.MAX_DIFFICULTY - ProofOfWork.MIN_DIFFICULTY - 1,
        )
    }
}

@Composable
private fun Explanation(text: String) {
    Text(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        text = text,
        style = AppTheme.typography.bodySmall,
        color = AppTheme.colorScheme.onSurfaceVariant,
    )
}
