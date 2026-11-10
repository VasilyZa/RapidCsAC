package com.xiaobai.rapidcsac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil.compose.AsyncImage
import com.xiaobai.rapidcsac.platform.Personalization
import java.io.File
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

val LocalPersonalization = staticCompositionLocalOf { Personalization() }
val LocalChatWallpaper = staticCompositionLocalOf<File?> { null }

fun personalizedColors(style: Personalization, dark: Boolean): Colors {
    val base =
        if (dark)
            darkColorScheme(
                primary = Color(0xff9ab8ff),
                onPrimary = Color(0xff14264c),
                primaryContainer = Color(0xff223147),
                onSurfaceVariantSummary = Color(0xffb0bacd),
                onSurfaceVariantActions = Color(0xffb0bacd),
                onSecondaryContainer = Color(0xffb0bacd),
            )
        else
            lightColorScheme(
                primary = Color(0xff4169d8),
                primaryVariant = Color(0xff4169d8),
                primaryContainer = Color(0xffeef3ff),
                onSurfaceVariantSummary = Color(0xff626d80),
                onSurfaceVariantActions = Color(0xff626d80),
                onSecondaryContainer = Color(0xff626d80),
            )
    val originalBlue = style.accent == Personalization().accent
    if (originalBlue && !style.highContrast) return base
    val primary =
        accessibleAccent(
            if (originalBlue) base.primary.toArgb() else style.accent,
            base.surface.toArgb(),
            dark,
            if (style.highContrast) 7.0 else 4.5,
        )
    val container =
        if (originalBlue) base.primaryContainer.toArgb()
        else ColorUtils.blendARGB(style.accent, base.surface.toArgb(), if (dark) .78f else .92f)
    val summary =
        if (style.highContrast) base.onSurface else Color(if (dark) 0xffb0bacd else 0xff626d80)
    val error =
        if (style.highContrast)
            Color(accessibleAccent(base.error.toArgb(), base.surface.toArgb(), dark, 7.0))
        else base.error
    return base.copy(
        primary = Color(primary),
        primaryVariant = Color(primary),
        onPrimary = readableForeground(Color(primary)),
        primaryContainer = Color(container),
        onPrimaryContainer = readableForeground(Color(container)),
        tertiaryContainer = if (originalBlue) base.tertiaryContainer else Color(container),
        onTertiaryContainer =
            if (originalBlue) base.onTertiaryContainer else readableForeground(Color(container)),
        onSurfaceVariantSummary = summary,
        onSurfaceVariantActions = summary,
        onSecondaryContainer = summary,
        error = error,
        onError = if (style.highContrast) readableForeground(error) else base.onError,
        outline =
            if (style.highContrast)
                Color(accessibleAccent(base.outline.toArgb(), base.surface.toArgb(), dark, 3.0))
            else base.outline,
    )
}

private fun accessibleAccent(seed: Int, surface: Int, dark: Boolean, contrast: Double): Int {
    val target = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    for (step in 0..20) {
        val candidate = ColorUtils.blendARGB(seed, target, step / 20f)
        if (ColorUtils.calculateContrast(candidate, surface) >= contrast) return candidate
    }
    return target
}

fun readableForeground(background: Color): Color =
    if (
        ColorUtils.calculateContrast(android.graphics.Color.WHITE, background.toArgb()) >=
            ColorUtils.calculateContrast(android.graphics.Color.BLACK, background.toArgb())
    )
        Color.White
    else Color.Black

@Composable
fun chatBackgroundColor(): Color {
    val style = LocalPersonalization.current
    val base = MiuixTheme.colorScheme.surface
    val tint = style.backgroundTint ?: return base
    return Color(
        ColorUtils.blendARGB(tint, base.toArgb(), if (LocalDarkTheme.current) .82f else .9f)
    )
}

@Composable
fun ChatBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val style = LocalPersonalization.current
    val wallpaper = LocalChatWallpaper.current
    val base = chatBackgroundColor()
    Box(modifier.background(base)) {
        if (wallpaper != null) {
            AsyncImage(
                wallpaper,
                null,
                Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.matchParentSize()
                    .background(
                        base.copy(alpha = if (style.highContrast) .85f else style.wallpaperDim)
                    )
            )
        }
        content()
    }
}

/** The same surface is used by live messages and the non-interactive settings preview. */
@Composable
fun ChatBubble(
    own: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalPersonalization.current
    val colors = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val customColor = if (own) style.ownBubble else style.otherBubble
    val fill =
        customColor?.let(::Color) ?: if (own) colors.primaryContainer else colors.surfaceContainer
    val opacity = if (style.highContrast) 1f else style.bubbleOpacity
    val background = fill.copy(alpha = opacity)
    val composite = background.compositeOver(chatBackgroundColor())
    val stockColors = customColor == null && opacity == 1f && !style.highContrast
    val foreground = if (stockColors) colors.onSurfaceContainer else readableForeground(composite)
    val inset =
        if (style.compact) PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        else PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val bubbleColors =
        if (stockColors) colors
        else
            colors.copy(
                primary =
                    Color(
                        accessibleAccent(
                            colors.primary.toArgb(),
                            composite.toArgb(),
                            foreground == Color.White,
                            if (style.highContrast) 7.0 else 4.5,
                        )
                    ),
                onSurface = foreground,
                onSurfaceContainer = foreground,
                onSurfaceVariantSummary = foreground,
                secondaryContainer = foreground.copy(alpha = .08f).compositeOver(composite),
                onSecondaryContainer = foreground,
            )
    MiuixTheme(colors = bubbleColors, textStyles = textStyles) {
        Card(
            modifier = modifier,
            cornerRadius = style.corners.radius.dp,
            colors = CardDefaults.defaultColors(background, foreground),
            insideMargin = inset,
            onClick = onClick,
            onLongPress = onLongPress,
            content = content,
        )
    }
}
