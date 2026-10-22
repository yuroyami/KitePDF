package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation

// Registering the same implementation again is allowed, so a window opened later is no conflict.
@OptIn(InternalComposeUiApi::class)
private val composeBackend: Unit = registerSkikoComposeImplementation()

internal actual fun ensureComposeBackend() = composeBackend
