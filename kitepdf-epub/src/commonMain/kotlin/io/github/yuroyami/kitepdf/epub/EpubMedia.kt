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
)
