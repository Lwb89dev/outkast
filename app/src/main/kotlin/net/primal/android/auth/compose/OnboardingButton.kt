package net.primal.android.auth.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.primal.android.core.compose.MAX_COMPONENT_WIDTH
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.theme.AppTheme

@Composable
fun OnboardingButton(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    secondary: Boolean = false,
) {
    PrimalLoadingButton(
        modifier = modifier
            .widthIn(240.dp, MAX_COMPONENT_WIDTH.dp)
            .height(50.dp)
            .fillMaxWidth(),
        containerColor = if (secondary) AppTheme.colorScheme.surfaceVariant else AppTheme.colorScheme.primary,
        disabledContainerColor = AppTheme.colorScheme.primary.copy(alpha = 0.20f),
        contentColor = if (secondary) AppTheme.colorScheme.onSurface else AppTheme.colorScheme.onPrimary,
        disabledContentColor = AppTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        enabled = enabled,
        loading = loading,
        onClick = onClick,
        text = text,
    )
}
