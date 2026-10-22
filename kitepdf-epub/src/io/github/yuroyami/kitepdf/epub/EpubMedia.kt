package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle

/** What a media element plays. */
public enum class EpubMediaKind { VIDEO, AUDIO }

/**
 * One source of a media element, in the order the element lists them. [href] is a zip path, as
 * [EpubDocument.resource] reads it, or a URL with a scheme. [type] is the media type that the
 * element or the manifest gives, or null.
 */
public class EpubMediaSource internal constructor(public val href: String, public val type: String?)

/** What a text track of a media element holds, as the `kind` of its `<track>` element says (HTML, the track element). */
public enum class EpubTrackKind { SUBTITLES, CAPTIONS, DESCRIPTIONS, CHAPTERS, METADATA }

/**
 * One `<track>` element of a media element: a WebVTT file of timed text (#483).
 *
 * @property href a zip path, as [EpubDocument.resource] reads it, or a URL with a scheme.
 * @property kind what the track holds. A track without a `kind` holds subtitles, and one with a
 *   `kind` that HTML does not know holds metadata.
 * @property language the `srclang` of the track, or null.
 * @property label the `label` of the track, for a menu, or null.
 * @property isDefault whether the element asks to show this track when nothing else is chosen.
 */
public class EpubMediaTrack internal constructor(
    public val href: String,
    public val kind: EpubTrackKind,
    public val language: String?,
    public val label: String?,
    public val isDefault: Boolean,
)

/**
 * A `<video>` or an `<audio>` element on an [EpubPage]: where it sits, what it plays and how. The
 * page paints the poster there, or a plain grey box, so an app can place its own player over
 * [rect] and read the bytes with [EpubDocument.resource]. The `kitepdf-media` artifact is such a
 * player, and draws the play control (#31).
 *
 * @property rect the box in display space, y down, with the smaller y in [KiteRectangle.bottom],
 *   as [EpubLink.rect] is.
 * @property poster the zip path of the poster image, or null.
 * @property id the element's own `id`, or null.
 * @property tracks the `<track>` elements of the element, in document order.
 */
public class EpubMedia internal constructor(
    public val rect: KiteRectangle,
    public val kind: EpubMediaKind,
    public val sources: List<EpubMediaSource>,
    public val poster: String?,
    public val controls: Boolean,
    public val autoplay: Boolean,
    public val loop: Boolean,
    public val muted: Boolean,
    public val id: String?,
    public val tracks: List<EpubMediaTrack>,
)

/** What an [ImageBox] of a media element keeps of the element. */
internal class MediaInfo(
    val kind: EpubMediaKind,
    val sources: List<EpubMediaSource>,
    val poster: String?,
    val controls: Boolean,
    val autoplay: Boolean,
    val loop: Boolean,
    val muted: Boolean,
    val id: String?,
    val tracks: List<EpubMediaTrack>,
)
