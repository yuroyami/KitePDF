package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.session.BackgroundPolicy
import io.github.yuroyami.kiteplayer.session.attachBackgroundHandling

@Composable
internal actual fun BackgroundHandling(player: KitePlayer, policy: BackgroundPolicy) {
    val context = LocalContext.current
    DisposableEffect(player, policy, context) {
        // A context outside an application, such as a preview, has no screen to leave.
        val handle = try {
            KitePlayerPlatform.attachBackgroundHandling(player, context, policy)
        } catch (failure: IllegalArgumentException) {
            null
        }
        onDispose { handle?.close() }
    }
}
