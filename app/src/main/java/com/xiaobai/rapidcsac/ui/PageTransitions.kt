package com.xiaobai.rapidcsac.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnterTransition(
    reducedMotion: Boolean
): EnterTransition =
    if (reducedMotion) fadeIn(tween(100))
    else slideIntoContainer(SlideDirection.Start, RapidMotion.navigationSpec())

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopExitTransition(
    reducedMotion: Boolean
): ExitTransition =
    if (reducedMotion) fadeOut(tween(100))
    else slideOutOfContainer(SlideDirection.End, RapidMotion.navigationSpec())
