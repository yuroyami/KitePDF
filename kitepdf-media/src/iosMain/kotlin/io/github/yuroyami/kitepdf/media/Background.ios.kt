package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.session.BackgroundPolicy
import io.github.yuroyami.kiteplayer.session.attachBackgroundHandling

@Composable
internal actual fun BackgroundHandling(player: KitePlayer, policy: BackgroundPolicy) {
    DisposableEffect(player, policy) {
        val handle = KitePlayerPlatform.attachBackgroundHandling(player, policy)
        onDispose { handle.close() }
    }
}
