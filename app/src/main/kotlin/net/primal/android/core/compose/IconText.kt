package net.primal.android.core.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IconText(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    /**
     * A character (typically an emoji) drawn in the leading slot instead of [leadingIcon]. It takes
     * the icon's place and size, so a stat can show, say, the reaction the user gave in the spot where
     * the heart normally is.
     */
    leadingEmoji: String? = null,
    trailingIcon: ImageVector? = null,
    iconSize: TextUnit = 24.sp,
    color: Color = LocalContentColor.current,
    leadingIconTintColor: Color? = color,
    trailingIconTintColor: Color? = color,
    leadingIconContentDescription: String? = null,
    trailingIconContentDescription: String? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val hasLeading = leadingIcon != null || leadingEmoji != null
    val contentText = buildAnnotatedString {
        if (hasLeading) {
            appendInlineContent("leadingIcon", "[leadingIcon]")
            append(" ")
        }
        append(text)
        if (trailingIcon != null) {
            append(" ")
            appendInlineContent("trailingIcon", "[trailingIcon]")
        }
    }

    val inlineContent = mutableMapOf<String, InlineTextContent>().apply {
        if (leadingEmoji != null) {
            this["leadingIcon"] = buildEmojiInlineTextContent(
                iconSize = iconSize,
                emoji = leadingEmoji,
                description = leadingIconContentDescription,
            )
        } else if (leadingIcon != null) {
            this["leadingIcon"] = buildIconInlineTextContent(
                iconSize = iconSize,
                icon = leadingIcon,
                iconTintColor = leadingIconTintColor,
                iconContentDescription = leadingIconContentDescription,
            )
        }

        if (trailingIcon != null) {
            this["trailingIcon"] = buildIconInlineTextContent(
                iconSize = iconSize,
                icon = trailingIcon,
                iconTintColor = trailingIconTintColor,
                iconContentDescription = trailingIconContentDescription,
            )
        }
    }

    Text(
        modifier = modifier,
        text = contentText,
        style = style,
        fontWeight = fontWeight,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
        inlineContent = inlineContent,
    )
}

@Composable
private fun buildIconInlineTextContent(
    iconSize: TextUnit,
    icon: ImageVector,
    iconTintColor: Color?,
    iconContentDescription: String? = null,
) = InlineTextContent(
    placeholder = Placeholder(
        iconSize,
        iconSize,
        PlaceholderVerticalAlign.TextCenter,
    ),
) {
    Box(
        modifier = Modifier.padding(bottom = 2.dp).fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = iconContentDescription,
            colorFilter = if (iconTintColor != null) {
                ColorFilter.tint(color = iconTintColor)
            } else {
                null
            },
        )
    }
}

/**
 * An emoji sized like an icon. The emoji is drawn a little smaller than the placeholder because a
 * glyph at the full icon size sits taller than the vector icons do and would push the line apart.
 */
@Composable
private fun buildEmojiInlineTextContent(
    iconSize: TextUnit,
    emoji: String,
    description: String? = null,
) = InlineTextContent(
    placeholder = Placeholder(
        iconSize,
        iconSize,
        PlaceholderVerticalAlign.TextCenter,
    ),
) {
    Box(
        modifier = Modifier.fillMaxSize().semantics { description?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            fontSize = iconSize * EMOJI_TO_ICON_RATIO,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val EMOJI_TO_ICON_RATIO = 0.85f
