package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable

/** A desktop JVM has no setting for reduced motion that Java can read. */
@Composable
internal actual fun platformPrefersReducedMotion(): Boolean = false
