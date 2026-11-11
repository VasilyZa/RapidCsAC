package com.xiaobai.rapidcsac.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

val LocalSharedMediaScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalMediaVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun rememberMediaRequest(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(url)
            .placeholderMemoryCacheKey(url)
            .build()
    }
}

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
            boundsTransform = { _, _ -> RapidMotion.navigationSpec() },
        )
    }
}
