package com.xiaobai.rapidcsac.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaobai.rapidcsac.AppGraph
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

object RapidMotion {
    val easeOut = CubicBezierEasing(.23f, 1f, .32f, 1f)
    const val navigationMillis = 220
    const val feedbackMillis = 140
}

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun RapidTheme(graph: AppGraph, content: @Composable () -> Unit) {
    val preference by graph.theme.collectAsStateWithLifecycle()
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
        remember(dark) {
            if (dark)
                darkColorScheme(
                    primary = Color(0xFF9AB8FF),
                    onPrimary = Color(0xFF14264C),
                    primaryContainer = Color(0xFF223147),
                    onSurfaceVariantSummary = Color(0xFFB0BACD),
                    onSurfaceVariantActions = Color(0xFFB0BACD),
                    onSecondaryContainer = Color(0xFFB0BACD),
                )
            else
                lightColorScheme(
                    primary = Color(0xFF4169D8),
                    primaryVariant = Color(0xFF4169D8),
                    primaryContainer = Color(0xFFEEF3FF),
                    onSurfaceVariantSummary = Color(0xFF626D80),
                    onSurfaceVariantActions = Color(0xFF626D80),
                    onSecondaryContainer = Color(0xFF626D80),
                )
        }
    CompositionLocalProvider(
        LocalReducedMotion provides (reduce || systemReduced),
        LocalDarkTheme provides dark,
    ) {
        MiuixTheme(colors = colors, content = content)
    }
}
