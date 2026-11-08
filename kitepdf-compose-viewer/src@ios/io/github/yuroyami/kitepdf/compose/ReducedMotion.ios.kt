package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** The Reduce Motion setting of iOS, under Accessibility, Motion. */
@Composable
internal actual fun platformPrefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
