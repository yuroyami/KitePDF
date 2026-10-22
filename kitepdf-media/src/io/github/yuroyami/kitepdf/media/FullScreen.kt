package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * The full-screen layer of a video (#482): the whole window, with no scrim, platform width or inset,
 * closed by a back gesture or Escape and never by a click outside it, since nothing is outside it.
 */
internal expect fun fullScreenDialogProperties(): DialogProperties

/**
 * What the platform does while a video is full screen, called inside the layer. Android hides the
 * system bars, and Android and iOS turn a [landscape] video to landscape and back. The desktop does
 * nothing: its layer already fills the window.
 */
@Composable
internal expect fun FullScreenWindow(landscape: Boolean)
