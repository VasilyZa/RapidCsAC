package com.xiaobai.rapidcsac.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

val LocalSharedMediaScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalMediaVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun Modifier.sharedMedia(key: String): Modifier {
    val shared = LocalSharedMediaScope.current
    val visibility = LocalMediaVisibilityScope.current
    if (key.isEmpty() || shared == null || visibility == null || LocalReducedMotion.current)
        return this
    return with(shared) {
        this@sharedMedia.sharedElement(
            rememberSharedContentState(key),
            visibility,
            boundsTransform = { _, _ ->
                tween(RapidMotion.navigationMillis, easing = RapidMotion.easeOut)
            },
        )
    }
}
