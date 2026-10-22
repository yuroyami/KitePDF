package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.DialogProperties
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationUnknown
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    scrimColor = Color.Black,
)

/**
 * Asks the window scene for landscape while a [landscape] video is full screen, and for the
 * orientation it had when full screen ends. The request needs iOS 16, and the app's supported
 * orientations must include landscape; otherwise the scene refuses it and nothing turns.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun FullScreenWindow(landscape: Boolean) {
    val controller = LocalUIViewController.current
    DisposableEffect(controller, landscape) {
        val scene = controller.view.window?.windowScene
        if (!landscape || scene == null || !scene.respondsToSelector(NSSelectorFromString(GEOMETRY_REQUEST))) {
            return@DisposableEffect onDispose {}
        }
        val before = scene.interfaceOrientation
        scene.requestOrientations(UIInterfaceOrientationMaskLandscape)
        onDispose {
            if (before != UIInterfaceOrientationUnknown) scene.requestOrientations(maskOf(before))
        }
    }
}

private fun UIWindowScene.requestOrientations(mask: ULong) {
    requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask), errorHandler = null)
}

/** UIKit defines each orientation's mask as one shifted left by the orientation's value. */
private fun maskOf(orientation: UIInterfaceOrientation): ULong = 1uL shl orientation.toInt()

/** The scene's geometry request, which iOS 16 added. */
private const val GEOMETRY_REQUEST = "requestGeometryUpdateWithPreferences:errorHandler:"
