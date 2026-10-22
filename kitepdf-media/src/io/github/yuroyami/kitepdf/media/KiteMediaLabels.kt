package io.github.yuroyami.kitepdf.media

/**
 * The words of the media controls that [KiteMediaOverlay] draws, which a screen reader says
 * (#484). English by default. Pass your own to translate them:
 *
 * ```kotlin
 * KiteMediaOverlay(labels = KiteMediaLabels(play = "Lire", pause = "Pause", mute = "Couper le son"))
 * ```
 *
 * @property play the button that starts or goes on playing.
 * @property pause the button that pauses.
 * @property mute the button that turns the sound off.
 * @property unmute the button that turns the sound on again.
 * @property seek the line of how far playback has gone, which seeks.
 * @property speed the button that opens the menu of playback speeds.
 * @property fullScreen the button that shows a video over the whole window.
 * @property exitFullScreen the button that brings the video back to its page.
 * @property subtitles the button that opens the menu of subtitle and caption tracks.
 * @property subtitlesOff the entry of that menu that shows no track.
 */
public class KiteMediaLabels(
    public val play: String = "Play",
    public val pause: String = "Pause",
    public val mute: String = "Mute",
    public val unmute: String = "Unmute",
    public val seek: String = "Seek",
    public val speed: String = "Playback speed",
    public val fullScreen: String = "Full screen",
    public val exitFullScreen: String = "Exit full screen",
    public val subtitles: String = "Subtitles",
    public val subtitlesOff: String = "Off",
)
