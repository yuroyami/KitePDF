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
) {
    val itemsById: Map<String, OpfItem> = items.associateBy { it.id }
}

/**
 * Publication metadata surfaced to a reader UI: Dublin Core fields plus the cover
 * image and reading direction. Paths are zip-absolute.
 */
class EpubMetadata internal constructor(
    val title: String?,
    val creators: List<String>,
    val language: String?,
    val identifier: String?,
    val coverImagePath: String?,
    /** True for `page-progression-direction="rtl"` books (Arabic/Hebrew/vertical CJK). */
    val rightToLeft: Boolean,
) {
    companion object {
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
                    "itemref" -> t.attrs["idref"]?.let { spine.add(it) }
                    "spine" -> { direction = t.attrs["page-progression-direction"]; tocNcx = t.attrs["toc"] }
                    "meta" -> if (t.attrs["name"] == "cover") metaCover = t.attrs["content"]
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
                else -> {}
            }
            is XmlToken.Close -> capture = null
        }

        return OpfPackage(
            baseDir, items, spine, direction, tocNcx,
            uniqueId ?: identifiers.firstOrNull(),
            title, creators, language, identifiers, metaCover,
        )
    }
}
