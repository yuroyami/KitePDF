package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.text.StructElement
import io.github.yuroyami.kitepdf.text.StructKid

/**
 * One element of the tag tree of a tagged PDF (ISO 32000-1, 14.7.2), as [PdfDocument.structureTree]
 * gives it (#640).
 *
 * @property type the standard structure type that the role map leads to (14.7.3, 14.8.4), such as
 *   `P`, `H1`, `Table`, `TR`, `TD` or `Figure`.
 * @property ownType the type that the element gives itself in `/S`, before the role map.
 * @property alt the alternate description, `/Alt` (14.9.3), as a figure carries it, or null.
 * @property actualText the replacement text, `/ActualText` (14.9.4), or null.
 * @property language the language of the element, `/Lang` (14.9.2), or null.
 * @property kids the element's kids in logical order: other elements and marked content.
 * @property bbox the box that the element's layout attributes give it (14.8.5.4), in the default
 *   user space of its page, or null.
 */
public class PdfStructElement internal constructor(
    public val type: String,
    public val ownType: String,
    public val alt: String?,
    public val actualText: String?,
    public val language: String?,
    public val kids: List<PdfStructKid>,
    public val bbox: KiteRectangle?,
) {
    /** The elements among [kids], in order. */
    public val children: List<PdfStructElement> get() = kids.mapNotNull { (it as? PdfStructKid.Element)?.element }

    override fun toString(): String = "PdfStructElement($type, ${kids.size} kids)"

    internal companion object {
        fun of(element: StructElement, pageIndices: Map<Long, Int>): PdfStructElement = PdfStructElement(
            element.type, element.ownType, element.alt, element.actualText, element.lang,
            element.kids.map { kid ->
                when (kid) {
                    is StructKid.Content -> PdfStructKid.Content(kid.page?.let(pageIndices::get), kid.mcid)
                    is StructKid.Element -> PdfStructKid.Element(of(kid.element, pageIndices))
                }
            },
            element.bbox,
        )
    }
}

/** A kid of a [PdfStructElement]: a marked-content sequence on a page, or another element (14.7.2, Table 323, `/K`). */
public sealed class PdfStructKid {
    /**
     * The marked-content sequence with the id [mcid] (14.6.4) in the content stream of the page at
     * [pageIndex], or null when the file names no page that the document has.
     */
    public class Content internal constructor(public val pageIndex: Int?, public val mcid: Int) : PdfStructKid() {
        override fun toString(): String = "Content(page $pageIndex, mcid $mcid)"
    }

    /** A structure element. */
    public class Element internal constructor(public val element: PdfStructElement) : PdfStructKid() {
        override fun toString(): String = "Element(${element.type})"
    }
}
