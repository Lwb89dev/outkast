package net.primal.android.core.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val GradientColorLight = Color(0xFFF4F2EE)
private val GradientColorMid = Color(0xFFF0E6DE)
private val GradientColorDeep = Color(0xFFFFC7B7)

fun primalGradientBrush(size: Size) =
    Brush.verticalGradient(
        colors = listOf(GradientColorLight, GradientColorMid, GradientColorDeep),
        startY = 0f,
        endY = size.height,
    )

const val PrimalGradientAlpha = 0.85f
val PrimalGradientBackgroundColor = Color(0xFFF4F2EE)
val PrimalDarkTextColor = Color(0xFF191A1D)
val PrimalSecondaryTextColor = Color(0xFF666970)
val PrimalDarkButtonColor = Color(0xFF191B20)
