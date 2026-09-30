package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * The media overlay metadata of a book, EPUB 3.3 Media Overlays: how long the narration runs,
 * who reads it, and the classes a reading system gives the element being read (#36). A reader
 * that follows the text shows [activeClass] as a highlight colour, not as a new style, because a
 * new style would lay the page out again.
 *
 * @property duration the `media:duration` of the whole book, in seconds, or null.
 * @property narrators the `media:narrator` entries, in package order.
 * @property activeClass the `media:active-class`, or null.
 * @property playbackActiveClass the `media:playback-active-class`, or null.
 */
public class EpubNarration internal constructor(
    public val duration: Double?,
    public val narrators: List<String>,
    public val activeClass: String?,
    public val playbackActiveClass: String?,
) {
    internal companion object {
        val NONE = EpubNarration(null, emptyList(), null, null)
    }
}

/**
 * One clip of a media overlay: a piece of the text, and the part of an audio file that reads it.
 *
 * @property textHref the zip path and the fragment of the text, as [EpubDocument.locateFragment] takes it.
 * @property audioHref the zip path of the audio, as [EpubDocument.resource] reads it, or null for a
 *   clip without audio.
 * @property clipBegin where the clip starts in the audio, in seconds.
 * @property clipEnd where the clip ends in the audio, in seconds, or null for the end of the file.
 * @property epubType the `epub:type` of the clip, or of the nearest sequence around it, such as `footnote`.
 * @property id the `id` of the clip's `par` element, or null.
 */
public class EpubOverlayClip internal constructor(
    public val textHref: String,
    public val audioHref: String?,
    public val clipBegin: Double,
    public val clipEnd: Double?,
    public val epubType: String?,
    public val id: String?,
) {
    override fun toString(): String = "EpubOverlayClip($textHref, $audioHref, $clipBegin..$clipEnd)"
}

/**
 * The media overlay of one chapter: its clips in document order, with nested sequences flattened.
 *
 * @property duration the overlay's own `media:duration`, in seconds, or null.
 */
public class EpubMediaOverlay internal constructor(
    public val clips: List<EpubOverlayClip>,
    public val duration: Double?,
)

/**
 * Where an element is on a page (#36).
 *
 * @property location the page that shows the element's first line.
 * @property rects one rectangle per line of the element on that page, in display space, y down,
 *   with the smaller y in [KiteRectangle.bottom], as [EpubLink.rect] is. Empty when the element
 *   holds no text, such as an image with an `id`.
 */
public class EpubFragmentBox internal constructor(
    public val location: KiteLocation,
    public val rects: List<KiteRectangle>,
)

/** SMIL 3.0 clock values (section 13): a full or a partial clock, or a count with a unit. */
internal object SmilClock {

    /** [value] in seconds, or null when it is not a clock value. */
    fun seconds(value: String): Double? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (':' in v) {
            val parts = v.split(':')
            if (parts.size !in 2..3) return null
            val numbers = parts.map { it.toDoubleOrNull() ?: return null }
            if (numbers.any { it < 0.0 || !it.isFinite() }) return null
            return numbers.fold(0.0) { total, n -> total * 60 + n }
        }
        val unit = UNITS.firstOrNull { v.endsWith(it.first) && v.length > it.first.length }
        val number = (unit?.let { v.dropLast(it.first.length) } ?: v).trim().toDoubleOrNull() ?: return null
        if (number < 0.0 || !number.isFinite()) return null
        return number * (unit?.second ?: 1.0)
    }

    /** The count units, longest first so `ms` and `min` win over `s` and `m`. */
    private val UNITS = listOf("min" to 60.0, "ms" to 0.001, "h" to 3600.0, "s" to 1.0)
}

/** Reads a media overlay document into its clips (EPUB 3.3 Media Overlays, section 3). */
internal object SmilParser {

    /**
     * The clips of the SMIL document [xml] at the zip path [path], in document order. A clip's
     * text and audio resolve against the document's folder.
     */
    fun clips(xml: String, path: String): List<EpubOverlayClip> {
        val dir = path.substringBeforeLast('/', "")
        val root = runCatching { KiteXml.parse(xml) }.getOrNull() ?: return emptyList()
        val body = find(root, "body") ?: return emptyList()
        val out = ArrayList<EpubOverlayClip>()
        // A list of elements to visit, and no recursion: sequences can nest deeper than a stack can follow (#450).
        val pending = arrayListOf<Pair<KiteXmlNode.Element, String?>>(body to null)
        while (pending.isNotEmpty()) {
            val (el, type) = pending.removeAt(pending.lastIndex)
            // The parser keeps local names, so epub:type reads as type.
            val own = el.attrs["type"]?.trim()?.takeIf { it.isNotEmpty() } ?: type
            if (el.tag == "par") {
                clip(el, own, dir)?.let(out::add)
                continue
            }
            for (i in el.children.indices.reversed()) (el.children[i] as? KiteXmlNode.Element)?.let { pending.add(it to own) }
        }
        return out
    }

    private fun clip(par: KiteXmlNode.Element, type: String?, dir: String): EpubOverlayClip? {
        val elements = par.children.filterIsInstance<KiteXmlNode.Element>()
        val text = elements.firstOrNull { it.tag == "text" }?.attrs?.get("src")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val audio = elements.firstOrNull { it.tag == "audio" }
        val audioSrc = audio?.attrs?.get("src")?.trim()?.takeIf { it.isNotEmpty() }
        return EpubOverlayClip(
            textHref = resolveWithFragment(dir, text),
            audioHref = audioSrc?.let { EpubDocument.resolvePath(dir, it) },
            clipBegin = audio?.attrs?.get("clipbegin")?.let(SmilClock::seconds) ?: 0.0,
            clipEnd = audio?.attrs?.get("clipend")?.let(SmilClock::seconds),
            epubType = type,
            id = par.attrs["id"],
        )
    }

    private fun resolveWithFragment(dir: String, href: String): String {
        val fragment = href.substringAfter('#', "")
        val path = EpubDocument.resolvePath(dir, href)
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }

    /** The first element named [tag] at or under [root], in document order. */
    private fun find(root: KiteXmlNode.Element, tag: String): KiteXmlNode.Element? {
        val pending = arrayListOf(root)
        while (pending.isNotEmpty()) {
            val el = pending.removeAt(pending.lastIndex)
            if (el.tag == tag) return el
            for (i in el.children.indices.reversed()) (el.children[i] as? KiteXmlNode.Element)?.let(pending::add)
        }
        return null
    }
}
