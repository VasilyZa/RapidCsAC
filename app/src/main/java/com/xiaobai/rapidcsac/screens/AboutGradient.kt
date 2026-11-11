package com.xiaobai.rapidcsac.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xiaobai.rapidcsac.ui.LocalReducedMotion
import com.xiaobai.rapidcsac.ui.RapidMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.isActive

private val darkBackgroundTones = listOf(Color(0xff7750bb), Color(0xff497fae), Color(0xffbc657e))
private val lightBackgroundTones = listOf(Color(0xffb49be8), Color(0xff91b9e0), Color(0xffe7a7b8))
private val darkTitleTones = listOf(Color(0xffd3baff), Color(0xffb4dbff), Color(0xffffc3d4))
private val lightTitleTones = listOf(Color(0xff69439b), Color(0xff325b96), Color(0xff913d60))

@Composable
internal fun rememberAboutGradientPhase(): State<Float> {
    val phase = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduce = LocalReducedMotion.current
    LaunchedEffect(lifecycle, reduce) {
        if (reduce) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val remaining =
                    ((1f - phase.value) * RapidMotion.ambientCycleMillis)
                        .roundToInt()
                        .coerceAtLeast(1)
                phase.animateTo(1f, tween(remaining, easing = LinearEasing))
                phase.snapTo(0f)
            }
        }
    }
    return phase.asState()
}

@Composable
internal fun AboutGradientBackground(
    phase: State<Float>,
    dark: Boolean,
    background: Color,
    modifier: Modifier = Modifier,
) {
    val palette = if (dark) darkBackgroundTones else lightBackgroundTones
    val opacity = if (dark) .62f else .68f
    Canvas(modifier) {
        // Reading the shared clock while drawing keeps animation out of layout and composition.
        val progress = phase.value
        val tones = gradientTones(palette, progress)
        val radius = size.maxDimension * .7f
        drawRect(background)
        tones.forEachIndexed { index, color ->
            val angle = (progress + index / 3f) * (2 * PI).toFloat()
            val center =
                Offset(
                    size.width * (.5f + .65f * cos(angle)),
                    size.height * (.5f + .48f * sin(angle)),
                )
            drawRect(
                Brush.radialGradient(
                    0f to color.copy(alpha = opacity),
                    .45f to color.copy(alpha = opacity * .55f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius,
                )
            )
        }
    }
}

internal fun Modifier.aboutTitleGradient(phase: State<Float>, dark: Boolean): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        // Mask the moving gradient with the glyphs, leaving text layout and semantics intact.
        drawRect(
            Brush.horizontalGradient(
                gradientTones(if (dark) darkTitleTones else lightTitleTones, phase.value)
            ),
            blendMode = BlendMode.SrcIn,
        )
    }

private fun gradientTones(palette: List<Color>, phase: Float): List<Color> {
    val position = phase * palette.size
    val shift = position.toInt()
    val fraction = position - shift
    return List(palette.size) { index ->
        lerp(
            palette[(index + shift) % palette.size],
            palette[(index + shift + 1) % palette.size],
            fraction,
        )
    }
}
