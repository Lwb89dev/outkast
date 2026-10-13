package net.primal.android.theme.colors

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val OutkastOrange = Color(0xFFFF6238)
private val OutkastOrangeDark = Color(0xFFD94D2B)
private val OutkastDarkBackground = Color(0xFF101114)
private val OutkastDarkSurface = Color(0xFF191B20)
private val OutkastDarkSurfaceHigh = Color(0xFF24262C)
private val OutkastDarkText = Color(0xFFF2F2F2)
private val OutkastDarkTextMuted = Color(0xFFA2A5AD)
private val OutkastDarkOutline = Color(0xFF34373F)

private val OutkastLightBackground = Color(0xFFF4F2EE)
private val OutkastLightSurface = Color(0xFFFFFFFF)
private val OutkastLightSurfaceHigh = Color(0xFFEAE7E1)
private val OutkastLightText = Color(0xFF191A1D)
private val OutkastLightTextMuted = Color(0xFF666970)
private val OutkastLightOutline = Color(0xFFD3CEC5)

val outkastDarkColorScheme = darkColorScheme(
    primary = OutkastOrange,
    onPrimary = Color(0xFF1A0A05),
    primaryContainer = Color(0xFF4A1D12),
    onPrimaryContainer = Color(0xFFFFDAD0),
    secondary = Color(0xFFC8A89E),
    onSecondary = Color(0xFF2A1711),
    tertiary = Color(0xFFD0B56A),
    onTertiary = Color(0xFF241B00),
    background = OutkastDarkBackground,
    onBackground = OutkastDarkText,
    surface = OutkastDarkBackground,
    onSurface = OutkastDarkText,
    surfaceVariant = OutkastDarkSurface,
    onSurfaceVariant = OutkastDarkTextMuted,
    surfaceContainer = OutkastDarkSurface,
    surfaceContainerHigh = OutkastDarkSurfaceHigh,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF310000),
    outline = OutkastDarkOutline,
    outlineVariant = Color(0xFF292C32),
)

val outkastDarkExtraColorScheme = extraColorScheme(
    onBrand = OutkastDarkText,
    surfaceVariantAlt1 = OutkastDarkSurfaceHigh,
    surfaceVariantAlt2 = OutkastDarkSurface,
    surfaceVariantAlt3 = Color(0xFF14161A),
    onSurfaceVariantAlt1 = OutkastDarkText,
    onSurfaceVariantAlt2 = OutkastDarkTextMuted,
    onSurfaceVariantAlt3 = Color(0xFF7D818B),
    onSurfaceVariantAlt4 = Color(0xFF62666F),
    warning = Color(0xFF42110B),
    successBright = Color(0xFF69D08B),
    successDim = Color(0xFF173522),
    replied = Color(0xFFB4B7BF),
    zapped = Color(0xFFFFB33B),
    liked = Color(0xFFFF668F),
    reposted = Color(0xFF69D08B),
    bookmarked = Color(0xFF62A8FF),
)

val outkastLightColorScheme = lightColorScheme(
    primary = OutkastOrangeDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF74584F),
    onSecondary = Color.White,
    tertiary = Color(0xFF6B5A1B),
    onTertiary = Color.White,
    background = OutkastLightBackground,
    onBackground = OutkastLightText,
    surface = OutkastLightBackground,
    onSurface = OutkastLightText,
    surfaceVariant = OutkastLightSurface,
    onSurfaceVariant = OutkastLightTextMuted,
    surfaceContainer = OutkastLightSurface,
    surfaceContainerHigh = OutkastLightSurfaceHigh,
    error = Color(0xFFB3261E),
    onError = Color.White,
    outline = OutkastLightOutline,
    outlineVariant = Color(0xFFE1DDD6),
)

val outkastLightExtraColorScheme = extraColorScheme(
    onBrand = OutkastLightText,
    surfaceVariantAlt1 = OutkastLightSurfaceHigh,
    surfaceVariantAlt2 = OutkastLightSurface,
    surfaceVariantAlt3 = Color(0xFFE3DFD8),
    onSurfaceVariantAlt1 = OutkastLightText,
    onSurfaceVariantAlt2 = OutkastLightTextMuted,
    onSurfaceVariantAlt3 = Color(0xFF7B7E84),
    onSurfaceVariantAlt4 = Color(0xFF8F9298),
    warning = Color(0xFFFFDAD6),
    successBright = Color(0xFF247A45),
    successDim = Color(0xFFD7F5DF),
    replied = Color(0xFF55585E),
    zapped = Color(0xFF9A5B00),
    liked = Color(0xFFB51C52),
    reposted = Color(0xFF247A45),
    bookmarked = Color(0xFF1261A6),
)
