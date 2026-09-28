package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * One element of a tagged PDF's logical structure (ISO 32000-1, 14.7.2): its type, the
 * text that replaces or describes it, and its kids in logical order (#208).
 *
 * @property type the standard structure type that the role map leads to, such as `P` or `H1`.
 * @property ownType the type the element gives itself, before the role map.
 */
internal class StructElement(
    val type: String,
    val ownType: String,
    val alt: String?,
    val actualText: String?,
    val lang: String?,
    val kids: List<StructKid>,
)

/** A kid of a [StructElement]: a marked-content sequence on a page, or another element. */
internal sealed class StructKid {
    /** The sequence with the marked-content id [mcid] on the page whose object number is [page]. */
    class Content(val page: Long?, val mcid: Int) : StructKid()

    class Element(val element: StructElement) : StructKid()
}

/** Reads the structure tree of a tagged PDF (ISO 32000-1, 14.7). */
internal object StructureTree {

    /** The root elements of [document]'s structure tree, in logical order, or null when it has none. */
    fun parse(document: PdfDocument): List<StructElement>? {
        val root = runCatching { document.catalog.getDict("StructTreeRoot", document) }.getOrNull() ?: return null
        val roleMap = runCatching { root.getDict("RoleMap", document) }.getOrNull()
        val visited = HashSet<Long>()
        var budget = MAX_ELEMENTS

        fun resolve(o: PdfObject?): PdfObject? =
            if (o is PdfReference) runCatching { document.resolve(o) }.getOrNull() else o

        fun text(dict: PdfDictionary, key: String): String? = (resolve(dict[key]) as? PdfString)?.asText()

        fun pageOf(dict: PdfDictionary): Long? = (dict["Pg"] as? PdfReference)?.objectNumber

        // A custom type maps to a standard one through the role map, perhaps in a few steps.
        fun standard(type: String): String {
            var t = type
            repeat(MAX_ROLE_STEPS) {
                if (t in STANDARD_TYPES) return t
                val next = (resolve(roleMap?.get(t)) as? PdfName)?.value ?: return t
                if (next == t) return t
                t = next
            }
            return t
        }

        fun kidsOf(raw: PdfObject?, page: Long?, depth: Int, into: MutableList<StructKid>, element: (PdfReference?, PdfDictionary, Long?, Int) -> StructElement?) {
            fun one(o: PdfObject?) {
                when (val v = resolve(o)) {
                    is PdfInt -> if (v.value in 0..Int.MAX_VALUE) into += StructKid.Content(page, v.value.toInt())
                    is PdfDictionary -> when (v.getName("Type")) {
                        // A reference into a form's stream names that stream's content, not the page's.
                        "MCR" -> if (v["Stm"] == null) {
                            val id = (resolve(v["MCID"]) as? PdfInt)?.value
                            if (id != null && id in 0..Int.MAX_VALUE) into += StructKid.Content(pageOf(v) ?: page, id.toInt())
                        }
                        "OBJR" -> Unit
                        else -> element(o as? PdfReference, v, page, depth + 1)?.let { into += StructKid.Element(it) }
                    }
                    else -> Unit
                }
            }
            when (val k = resolve(raw)) {
                is PdfArray -> for (item in k) one(item)
                null -> Unit
                else -> one(raw)
            }
        }

        fun element(ref: PdfReference?, dict: PdfDictionary, page: Long?, depth: Int): StructElement? {
            if (depth > MAX_DEPTH || budget <= 0) return null
            // An element met twice, in a loop or a shared subtree, is read once.
            if (ref != null && !visited.add(ref.objectNumber)) return null
            budget--
            val own = (resolve(dict["S"]) as? PdfName)?.value ?: return null
            val pg = pageOf(dict) ?: page
            val kids = ArrayList<StructKid>()
            kidsOf(dict["K"], pg, depth, kids, ::element)
            return StructElement(standard(own), own, text(dict, "Alt"), text(dict, "ActualText"), text(dict, "Lang"), kids)
        }

        val roots = ArrayList<StructKid>()
        kidsOf(root["K"], null, 0, roots, ::element)
        return roots.mapNotNull { (it as? StructKid.Element)?.element }
    }

    /** The standard structure types of ISO 32000-1, 14.8.4, and those PDF 2.0 adds. */
    val STANDARD_TYPES = setOf(
        "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index",
        "NonStruct", "Private", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody",
        "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Span", "Quote", "Note", "Reference",
        "BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP",
        "Figure", "Formula", "Form", "DocumentFragment", "Aside", "Title", "FENote", "Sub", "Em",
        "Strong", "Artifact",
    )

    /** The elements read at most, so a huge or hostile tree stays bounded. */
    private const val MAX_ELEMENTS = 200_000

    /** The nesting read at most. */
    private const val MAX_DEPTH = 256

    /** The role map steps followed at most, so a loop in the map ends. */
    private const val MAX_ROLE_STEPS = 8
}
