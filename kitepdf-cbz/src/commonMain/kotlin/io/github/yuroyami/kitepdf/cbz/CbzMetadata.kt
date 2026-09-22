package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.text.TextEncoding
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * ComicInfo.xml metadata, following the Anansi Project ComicInfo 2.0 schema.
 * [fields] retains every declared scalar field under its lowercase XML name,
 * including fields a newer schema adds. Missing values remain absent.
 * https://anansi-project.github.io/docs/comicinfo/schemas/v2.0
 */
public class CbzMetadata internal constructor(
    public val fields: Map<String, String>,
    public val pages: List<CbzPageMetadata>,
) {
    public val title: String? get() = fields["title"]
    public val series: String? get() = fields["series"]
    /** Issue numbers are strings: values such as "0.5" and "1a" are valid. */
    public val number: String? get() = fields["number"]
    public val summary: String? get() = fields["summary"]
    public val publisher: String? get() = fields["publisher"]
    public val language: String? get() = fields["languageiso"]
    public val writers: List<String> get() = fields["writer"]
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    public val rightToLeft: Boolean get() = fields["manga"].equals("YesAndRightToLeft", ignoreCase = true)
}

/**
 * A ComicInfo Pages/Page record. [image] indexes the natural filename order,
 * starting at zero; [attributes] retains the declared lowercase XML attributes.
 */
public class CbzPageMetadata internal constructor(
    public val image: Int,
    public val attributes: Map<String, String>,
) {
    public val bookmark: String? get() = attributes["bookmark"]?.takeIf { it.isNotBlank() }
    public val type: String? get() = attributes["type"]
    public val doublePage: Boolean get() = attributes["doublepage"] == "1" ||
        attributes["doublepage"].equals("true", ignoreCase = true)
}

internal fun parseComicInfo(bytes: ByteArray, pageCount: Int): CbzMetadata? {
    val root = KiteXml.parse(TextEncoding.decode(bytes)).children.filterIsInstance<KiteXmlNode.Element>()
        .firstOrNull { it.tag == "comicinfo" } ?: return null
    val fields = LinkedHashMap<String, String>()
    val pages = ArrayList<CbzPageMetadata>()
    for (child in root.children.filterIsInstance<KiteXmlNode.Element>()) {
        if (child.tag == "pages") {
            for (page in child.children.filterIsInstance<KiteXmlNode.Element>()) {
                if (page.tag != "page") continue
                val index = page.attrs["image"]?.toIntOrNull() ?: continue
                if (index !in 0 until pageCount) continue
                pages.add(CbzPageMetadata(index, page.attrs.toMap()))
            }
        } else {
            val text = child.children.filterIsInstance<KiteXmlNode.Text>()
                .joinToString("") { it.text }.trim()
            if (text.isNotEmpty()) fields[child.tag] = text
        }
    }
    return CbzMetadata(fields.toMap(), pages.toList())
}
