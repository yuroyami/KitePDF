package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    scrimColor = Color.Black,
)

@Composable
internal actual fun FullScreenWindow(landscape: Boolean) = Unit
