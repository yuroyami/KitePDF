package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.session.BackgroundPolicy

/** A desktop window keeps playing when it is hidden, as desktop players do. */
@Composable
internal actual fun BackgroundHandling(player: KitePlayer, policy: BackgroundPolicy) = Unit
