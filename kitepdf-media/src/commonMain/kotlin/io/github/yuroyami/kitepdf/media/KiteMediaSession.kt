package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.session.BackgroundPolicy
import kotlin.time.Duration

/**
 * What the media elements of the books an app shows share (#481):
 *
 * - The place where each element stopped, while its book is open. When the page of an element
 *   comes back, the element shows that place, paused, with the sound and the speed it had.
 * - One element plays at a time. Starting one pauses any other that plays.
 * - What playback does when the app leaves the screen. A video pauses, and an audio element
 *   follows [audioInBackground].
 *
 * [KiteMediaOverlay] uses [Default] unless it gets a session of its own. Call its members on
 * the main thread.
 *
 * @param audioInBackground what an audio element does when the app leaves the screen, on Android
 *   and iOS. [BackgroundPolicy.PauseAll], the default, pauses it and plays it again on return.
 *   [BackgroundPolicy.ContinueAudio] keeps the sound, which on Android also needs the app's own
 *   media notification to keep the process alive. A desktop window keeps playing when it is
 *   hidden, as desktop players do.
 */
public class KiteMediaSession(
    public val audioInBackground: BackgroundPolicy = BackgroundPolicy.PauseAll,
) {
    /** Where an element stopped, and the sound and speed it had then. */
    internal class Place(val position: Duration, val muted: Boolean, val speed: Double)

    /** The places, least recently kept first, so the oldest go when there are too many. */
    private val places = LinkedHashMap<String, Place>()

    private val players = LinkedHashSet<KitePlayer>()

    internal fun placeOf(key: String): Place? = places[key]

    internal val placeCount: Int get() = places.size

    internal fun keep(key: String, place: Place?) {
        places.remove(key)
        if (place == null) return
        places[key] = place
        while (places.size > MAX_PLACES) places.remove(places.keys.first())
    }

    internal fun register(player: KitePlayer) {
        players += player
    }

    internal fun unregister(player: KitePlayer) {
        players -= player
    }

    /** [player] began to play, so every other player of the session pauses. */
    internal fun started(player: KitePlayer) {
        for (other in players) {
            if (other !== player && other.state.value.status.isActive) {
                try {
                    other.pause()
                } catch (failure: IllegalStateException) {
                    // Closed meanwhile.
                }
            }
        }
    }

    public companion object {
        /** The session of every overlay that gets none of its own. */
        public val Default: KiteMediaSession = KiteMediaSession()

        /** How many places a session keeps, so a long reading does not grow without end. */
        private const val MAX_PLACES = 256
    }
}

/**
 * Gives [player] a media session while it is in the composition. The session applies [policy] when
 * the app leaves the screen, and pauses for a call or when the headphones come out.
 */
@Composable
internal expect fun BackgroundHandling(player: KitePlayer, policy: BackgroundPolicy)
