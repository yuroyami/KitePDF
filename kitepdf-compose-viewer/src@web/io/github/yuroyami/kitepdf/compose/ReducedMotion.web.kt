package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable

/** The `prefers-reduced-motion` media query of the browser. */
@Composable
internal actual fun platformPrefersReducedMotion(): Boolean = prefersReducedMotionQuery()

private fun prefersReducedMotionQuery(): Boolean =
    js("typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches")
