package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlToken

import io.github.yuroyami.kitepdf.core.zip.ZipReader

/**
 * One `<manifest>` entry. [fallback] is the id of the item to use when this one cannot be rendered.
 * [mediaOverlay] is the id of the item that narrates this one (#36).
 */
internal class OpfItem(
    val id: String,
    val href: String,
    val mediaType: String?,
    val properties: String?,
    val fallback: String? = null,
    val mediaOverlay: String? = null,
) {
    fun hasProperty(p: String): Boolean = properties?.split(' ', '\t', '\n')?.any { it == p } == true
}

/** Structured view of the OPF package document (parsed in a single pass). */
internal class OpfPackage(
    val baseDir: String,
    val items: List<OpfItem>,
    val spineIdrefs: List<String>,
    val direction: String?,
    val tocNcxId: String?,
    val uniqueId: String?,
    val title: String?,
    val creators: List<String>,
    val language: String?,
    val identifiers: List<String>,
    val metaCoverId: String?,
    /** The book's rendition properties from the package metadata, by name without the `rendition:` prefix. */
    val renditionValues: Map<String, String> = emptyMap(),
    /** `properties` of each spine `<itemref>`, parallel to [spineIdrefs]. */
    val spineProperties: List<String?> = emptyList(),
    /** `<meta property="primary-writing-mode">` ("vertical-rl" and friends), or null. */
    val primaryWritingMode: String? = null,
    /** The media overlay metadata of the whole book (#36). */
    val narration: EpubNarration = EpubNarration.NONE,
    /** The `media:duration` of each media overlay item, by item id, in seconds (#36). */
    val overlayDurations: Map<String, Double> = emptyMap(),
) {
    val itemsById: Map<String, OpfItem> = items.associateBy { it.id }

    /**
     * The item [id] and the items its `fallback` chain names after it, in order (EPUB 3.3, 3.5.2):
     * at most [MAX_FALLBACK_HOPS] hops, and a chain that comes back to an item ends there.
     */
    fun fallbackChain(id: String): List<OpfItem> {
        val out = ArrayList<OpfItem>()
        val seen = HashSet<String>()
        var next: String? = id
        while (next != null && out.size <= MAX_FALLBACK_HOPS && seen.add(next)) {
            val item = itemsById[next] ?: break
            out += item
            next = item.fallback
        }
        return out
    }

    /**
     * The document that the spine item [id] renders: the item itself when it is XHTML or SVG,
     * else the first item of its fallback chain that is (#27). A chain without one keeps the
     * item, which renders what it can.
     */
    fun contentDocument(id: String): OpfItem? {
        val chain = fallbackChain(id)
        return chain.firstOrNull { it.mediaType?.lowercase() in CONTENT_TYPES } ?: chain.firstOrNull()
    }

    /** How the whole book asks to be shown (#37). */
    val rendition: EpubRendition = EpubRendition.ofBook(renditionValues)

    /** How the spine item at [index] asks to be shown: its own properties, else the book's (#37). */
    fun renditionAt(index: Int): EpubRendition = EpubRendition.ofChapter(rendition, spineProperties.getOrNull(index))
}

/**
 * Publication metadata surfaced to a reader UI: Dublin Core fields plus the cover
 * image and reading direction. Paths are zip-absolute.
 */
public class EpubMetadata internal constructor(
    public val title: String?,
    public val creators: List<String>,
    public val language: String?,
    public val identifier: String?,
    public val coverImagePath: String?,
    /** True for `page-progression-direction="rtl"` books (Arabic/Hebrew/vertical CJK). */
    public val rightToLeft: Boolean,
    /** How the whole book asks to be shown. [EpubDocument.renditionOf] gives the values of one chapter (#37). */
    public val rendition: EpubRendition = EpubRendition.DEFAULT,
    /**
     * The zip paths of the pronunciation lexicons (PLS documents, `application/pls+xml`) that the
     * manifest lists, for a speech engine to read with [EpubDocument.resource] (#39).
     */
    public val pronunciationLexicons: List<String> = emptyList(),
    /** The media overlay metadata of the book: duration, narrators and active classes (#36). */
    public val narration: EpubNarration = EpubNarration.NONE,
) {
    public companion object {
        internal val EMPTY = EpubMetadata(null, emptyList(), null, null, null, false)
    }
}

/** Parses the OPF package document into an [OpfPackage]. */
internal object Opf {

    fun parse(zip: ZipReader, opfPath: String): OpfPackage? {
        val xml = zip.readText(opfPath) ?: return null
        val baseDir = opfPath.substringBeforeLast('/', "")
        val items = ArrayList<OpfItem>()
        val spine = ArrayList<String>()
        var direction: String? = null
        var tocNcx: String? = null
        var uidRef: String? = null
        var uniqueId: String? = null
        var title: String? = null
        val creators = ArrayList<String>()
        var language: String? = null
        val identifiers = ArrayList<String>()
        var metaCover: String? = null
        val renditionValues = HashMap<String, String>()
        var primaryWritingMode: String? = null
        val spineProps = ArrayList<String?>()

        var capture: String? = null
        var captureIdIsUnique = false
        // Media overlay metadata: the whole book's, and the durations that refine one item (#36).
        var captureRefines: String? = null
        val media = HashMap<String, MutableList<String>>()
        val overlayDurations = HashMap<String, Double>()
        // The text of the element being captured, which a comment or a CDATA section can split.
        val captured = StringBuilder()

        fun commit() {
            val name = capture ?: return
            capture = null
            val value = metadataValue(captured)
            captured.clear()
            if (value.isEmpty()) return
            when (name) {
                "title" -> if (title == null) title = value
                "creator" -> creators.add(value)
                "language" -> if (language == null) language = value
                "identifier" -> { identifiers.add(value); if (captureIdIsUnique && uniqueId == null) uniqueId = value }
                "primaryWritingMode" -> if (primaryWritingMode == null) primaryWritingMode = value
                else -> {
                    if (name.startsWith("rendition:")) renditionValues.getOrPut(name.removePrefix("rendition:")) { value }
                    if (name.startsWith("media:")) {
                        val refines = captureRefines
                        when {
                            refines == null -> media.getOrPut(name) { ArrayList() }.add(value)
                            name == "media:duration" -> SmilClock.seconds(value)?.let { overlayDurations.getOrPut(refines) { it } }
                        }
                    }
                }
            }
        }

        for (t in KiteXml.tokenize(xml)) when (t) {
            is KiteXmlToken.Open -> {
                commit()
                when (t.name) {
                    "package" -> uidRef = t.attrs["unique-identifier"]
                    "item" -> {
                        val id = t.attrs["id"]; val href = t.attrs["href"]
                        if (id != null && href != null) {
                            items.add(OpfItem(id, href, t.attrs["media-type"], t.attrs["properties"], t.attrs["fallback"], t.attrs["media-overlay"]))
                        }
                    }
                    "itemref" -> t.attrs["idref"]?.let { spine.add(it); spineProps.add(t.attrs["properties"]) }
                    "spine" -> { direction = t.attrs["page-progression-direction"]; tocNcx = t.attrs["toc"] }
                    "meta" -> {
                        if (t.attrs["name"] == "cover") metaCover = t.attrs["content"]
                        // Rendition properties as an EPUB 3 property with the value in the text, or as a
                        // legacy name and content. The first value of each wins.
                        val property = t.attrs["property"]
                        if (property != null && property.startsWith("rendition:")) capture = property
                        t.attrs["name"]?.takeIf { it.startsWith("rendition:") }?.let { name ->
                            t.attrs["content"]?.let { renditionValues.getOrPut(name.removePrefix("rendition:")) { metadataValue(it) } }
                        }
                        if (t.attrs["name"] == "fixed-layout" && t.attrs["content"]?.let(::metadataValue).equals("true", true)) {
                            renditionValues.getOrPut("layout") { "pre-paginated" }
                        }
                        if (property != null && property.startsWith("media:")) {
                            capture = property
                            captureRefines = t.attrs["refines"]?.let(::metadataValue)?.removePrefix("#")
                        }
                        if (t.attrs["property"] == "primary-writing-mode") capture = "primaryWritingMode"
                        if (t.attrs["name"] == "primary-writing-mode") primaryWritingMode = t.attrs["content"]?.let(::metadataValue)
                    }
                    "title" -> capture = "title"
                    "creator" -> capture = "creator"
                    "language" -> capture = "language"
                    "identifier" -> { capture = "identifier"; captureIdIsUnique = t.attrs["id"] == uidRef }
                }
                // An element that closes itself has no text to capture.
                if (t.selfClose) capture = null
            }
            is KiteXmlToken.Text -> if (capture != null) captured.append(t.text)
            is KiteXmlToken.Close -> commit()
            is KiteXmlToken.Comment -> {}
        }

        return OpfPackage(
            baseDir, items, spine, direction, tocNcx,
            uniqueId ?: identifiers.firstOrNull(),
            title, creators, language, identifiers, metaCover,
            renditionValues, spineProps, primaryWritingMode,
            narration = EpubNarration(
                duration = media["media:duration"]?.firstNotNullOfOrNull { SmilClock.seconds(it) },
                narrators = media["media:narrator"].orEmpty(),
                activeClass = media["media:active-class"]?.firstOrNull(),
                playbackActiveClass = media["media:playback-active-class"]?.firstOrNull(),
            ),
            overlayDurations = overlayDurations,
        )
    }
}

/**
 * A package metadata value with the ASCII white space at its ends stripped and each run inside
 * collapsed to one space, as EPUB 3.3 asks a reading system to read every metadata value (#515).
 */
internal fun metadataValue(raw: CharSequence): String =
    raw.split(' ', '\t', '\n', '\u000C', '\r').filter { it.isNotEmpty() }.joinToString(" ")

/** The longest `fallback` chain followed; a longer one is a mistake or a trap. */
internal const val MAX_FALLBACK_HOPS = 16

/** The media types of the documents a spine item may render: XHTML, HTML from EPUB 2 converters, and SVG. */
private val CONTENT_TYPES = setOf("application/xhtml+xml", "text/html", "image/svg+xml")
