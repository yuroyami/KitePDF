package io.github.yuroyami.kitepdf.epub

/** One `<manifest>` entry. */
internal class OpfItem(val id: String, val href: String, val mediaType: String?, val properties: String?) {
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
    /** Global `rendition:layout` ("pre-paginated" / "reflowable"), or null. */
    val renditionLayout: String? = null,
    /** `properties` of each spine `<itemref>`, parallel to [spineIdrefs]. */
    val spineProperties: List<String?> = emptyList(),
) {
    val itemsById: Map<String, OpfItem> = items.associateBy { it.id }

    /** Whether the spine item at [index] is fixed-layout (per-item override, else global). */
    fun fixedLayoutAt(index: Int): Boolean {
        val props = spineProperties.getOrNull(index)
        if (props != null) {
            if (props.contains("rendition:layout-pre-paginated")) return true
            if (props.contains("rendition:layout-reflowable")) return false
        }
        return renditionLayout == "pre-paginated"
    }
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
        var renditionLayout: String? = null
        val spineProps = ArrayList<String?>()

        var capture: String? = null
        var captureIdIsUnique = false

        for (t in MiniXml.tokenize(xml)) when (t) {
            is XmlToken.Open -> {
                capture = null
                when (t.name) {
                    "package" -> uidRef = t.attrs["unique-identifier"]
                    "item" -> {
                        val id = t.attrs["id"]; val href = t.attrs["href"]
                        if (id != null && href != null) items.add(OpfItem(id, href, t.attrs["media-type"], t.attrs["properties"]))
                    }
                    "itemref" -> t.attrs["idref"]?.let { spine.add(it); spineProps.add(t.attrs["properties"]) }
                    "spine" -> { direction = t.attrs["page-progression-direction"]; tocNcx = t.attrs["toc"] }
                    "meta" -> {
                        if (t.attrs["name"] == "cover") metaCover = t.attrs["content"]
                        // rendition:layout via EPUB3 property (value in text) or legacy name/content.
                        if (t.attrs["property"] == "rendition:layout") capture = "renditionLayout"
                        if (t.attrs["name"] == "rendition:layout") renditionLayout = t.attrs["content"]?.trim()
                        if (t.attrs["name"] == "fixed-layout" && t.attrs["content"]?.equals("true", true) == true) renditionLayout = "pre-paginated"
                    }
                    "title" -> capture = "title"
                    "creator" -> capture = "creator"
                    "language" -> capture = "language"
                    "identifier" -> { capture = "identifier"; captureIdIsUnique = t.attrs["id"] == uidRef }
                }
            }
            is XmlToken.Text -> when (capture) {
                "title" -> if (title == null) title = t.text.trim()
                "creator" -> t.text.trim().takeIf { it.isNotEmpty() }?.let { creators.add(it) }
                "language" -> if (language == null) language = t.text.trim()
                "identifier" -> t.text.trim().takeIf { it.isNotEmpty() }?.let {
                    identifiers.add(it); if (captureIdIsUnique && uniqueId == null) uniqueId = it
                }
                "renditionLayout" -> if (renditionLayout == null) renditionLayout = t.text.trim()
                else -> {}
            }
            is XmlToken.Close -> capture = null
        }

        return OpfPackage(
            baseDir, items, spine, direction, tocNcx,
            uniqueId ?: identifiers.firstOrNull(),
            title, creators, language, identifiers, metaCover,
            renditionLayout, spineProps,
        )
    }
}
