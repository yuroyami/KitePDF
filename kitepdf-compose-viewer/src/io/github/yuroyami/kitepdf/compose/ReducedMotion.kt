package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable

/**
 * Whether the platform asks apps for less motion (#939): Android's animator duration scale set to
 * off, the Reduce Motion setting of iOS and macOS, or the `prefers-reduced-motion` media query of
 * a browser. False where the platform has no such setting.
 */
@Composable
internal expect fun platformPrefersReducedMotion(): Boolean
