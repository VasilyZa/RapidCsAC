package com.xiaobai.rapidcsac.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import com.xiaobai.rapidcsac.platform.InterfaceFont
import kotlin.math.PI
import kotlin.math.pow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles

object RapidMotion {
    val easeOut = CubicBezierEasing(.23f, 1f, .32f, 1f)
    const val navigationMillis = 500
    const val feedbackMillis = 140
    const val ambientCycleMillis = 24_000

    // Miuix's navigation curve uses response 0.8 and damping 0.95 over a fixed 500 ms.
    private val navigationSpring =
        FloatSpringSpec(
            dampingRatio = .95f,
            stiffness = (2.0 * PI / .8).pow(2).toFloat(),
            visibilityThreshold = .000001f,
        )
    private val navigationEasing = Easing { fraction ->
        if (fraction >= 1f) 1f
        else
            navigationSpring.getValueFromNanos(
                (fraction * 1_000_000_000L).toLong(),
                initialValue = 0f,
                targetValue = 1f,
                initialVelocity = 0f,
            )
    }

    fun <T> navigationSpec(): TweenSpec<T> = tween(navigationMillis, easing = navigationEasing)
}

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun RapidTheme(graph: AppGraph, content: @Composable () -> Unit) {
    val preference by graph.theme.collectAsStateWithLifecycle()
    val personalization by graph.personalization.style.collectAsStateWithLifecycle()
    val wallpaper by graph.wallpapers.image.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val reduce by graph.reducedMotion.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark =
        when (preference) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
    val context = LocalContext.current
    var systemReduced by remember {
        mutableStateOf(
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        )
    }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    systemReduced =
                        Settings.Global.getFloat(
                            context.contentResolver,
                            Settings.Global.ANIMATOR_DURATION_SCALE,
                            1f,
                        ) == 0f
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val colors =
        remember(dark, personalization.accent, personalization.highContrast) {
            personalizedColors(personalization, dark)
        }
    val typography =
        remember(personalization.font) {
            val family =
                when (personalization.font) {
                    InterfaceFont.SYSTEM -> FontFamily.Default
                    InterfaceFont.SERIF -> FontFamily.Serif
                    InterfaceFont.MONOSPACE -> FontFamily.Monospace
                    InterfaceFont.ROUNDED ->
                        FontFamily(
                            Typeface(
                                android.graphics.Typeface.create(
                                    "sans-serif-rounded",
                                    android.graphics.Typeface.NORMAL,
                                )
                            )
                        )
                }
            val base = defaultTextStyles()
            base.copy(
                main = base.main.copy(fontFamily = family),
                paragraph = base.paragraph.copy(fontFamily = family),
                body1 = base.body1.copy(fontFamily = family),
                body2 = base.body2.copy(fontFamily = family),
                button = base.button.copy(fontFamily = family),
                footnote1 = base.footnote1.copy(fontFamily = family),
                footnote2 = base.footnote2.copy(fontFamily = family),
                headline1 = base.headline1.copy(fontFamily = family),
                headline2 = base.headline2.copy(fontFamily = family),
                subtitle = base.subtitle.copy(fontFamily = family),
                title1 = base.title1.copy(fontFamily = family),
                title2 = base.title2.copy(fontFamily = family),
                title3 = base.title3.copy(fontFamily = family),
                title4 = base.title4.copy(fontFamily = family),
            )
        }
    CompositionLocalProvider(
        LocalReducedMotion provides (reduce || systemReduced),
        LocalDarkTheme provides dark,
        LocalPersonalization provides personalization,
        LocalChatWallpaper provides wallpaper,
        LocalDensity provides
            Density(density.density, density.fontScale * personalization.fontScale),
    ) {
        MiuixTheme(colors = colors, textStyles = typography, content = content)
    }
}
