package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import platform.AppKit.NSWorkspace

/** The Reduce motion setting of macOS, under Accessibility, Display. */
@Composable
internal actual fun platformPrefersReducedMotion(): Boolean = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion
