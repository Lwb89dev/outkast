package net.primal.android.theme.domain

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import net.primal.android.R
import net.primal.android.theme.PrimalShapes
import net.primal.android.theme.PrimalTypography
import net.primal.android.theme.colors.ExtraColorScheme
import net.primal.android.theme.colors.outkastDarkColorScheme
import net.primal.android.theme.colors.outkastDarkExtraColorScheme
import net.primal.android.theme.colors.outkastLightColorScheme
import net.primal.android.theme.colors.outkastLightExtraColorScheme

enum class PrimalTheme(
    val themeName: String,
    val colorScheme: ColorScheme,
    val extraColorScheme: ExtraColorScheme,
    val isDarkTheme: Boolean,
    val logoId: Int,
    val displayName: String,
    val typography: Typography = PrimalTypography,
    val shapes: Shapes = PrimalShapes,
) {
    OutkastDark(
        themeName = "outkast-dark",
        colorScheme = outkastDarkColorScheme,
        extraColorScheme = outkastDarkExtraColorScheme,
        isDarkTheme = true,
        logoId = R.drawable.outkast_icon_monochrome,
        displayName = "OUTKAST Dark",
    ),

    OutkastLight(
        themeName = "outkast-light",
        colorScheme = outkastLightColorScheme,
        extraColorScheme = outkastLightExtraColorScheme,
        isDarkTheme = false,
        logoId = R.drawable.outkast_icon_monochrome,
        displayName = "OUTKAST Light",
    ),
    ;

    val inverse: PrimalTheme
        get() = when (this) {
            OutkastDark -> OutkastLight
            OutkastLight -> OutkastDark
        }

    companion object {
        fun valueOf(themeName: String): PrimalTheme? {
            return when (themeName) {
                // Preserve every value written by earlier LibreNostr releases.
                "sunset", "midnight", "midnight-relay", "dark-pixel", "dark-green-pixel", "fire" -> OutkastDark
                "sunrise", "ice", "ice-relay", "light-pixel", "green-pixel", "fire-light" -> OutkastLight
                else -> enumValues<PrimalTheme>().find { it.themeName == themeName }
            }
        }
    }
}
