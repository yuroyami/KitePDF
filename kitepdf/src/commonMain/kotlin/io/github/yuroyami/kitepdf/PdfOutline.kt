package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfString
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * One node in the document outline (a.k.a. bookmarks) tree — ISO 32000-1
 * §12.3.3.
 *
 * Each node has a [title] (UTF-16BE or PDFDocEncoding text), zero or more
 * [children], and an optional destination ([dest]). The destination is
 * stored unresolved so the caller can choose whether to resolve it (cost:
 * one map lookup + page-ref translation).
 *
 * The flag bits ([italic], [bold]) and node colour come from the spec's
 * /F and /C entries; readers may render bookmarks accordingly.
 */
data class PdfOutline(
    val title: String,
    val children: List<PdfOutline>,
    /** Raw destination value as found in /Dest or /A/D, or `null`. Use [PdfDocument.resolveDestination]. */
    val rawDestination: PdfObject?,
    /** Parsed /A action (typed). `null` when the outline has no /A entry. */
    val action: PdfAction?,
    /** /Count from the spec — sum of visible descendants. Sign indicates open (>0) or closed (<0). 0 = leaf. */
    val count: Int,
    val italic: Boolean,
    val bold: Boolean,
    val color: RgbColor?,
) {

    /** True if this node is open by default. Closed nodes report a negative [count]. */
    val isOpen: Boolean get() = count >= 0

    companion object {

        /**
         * Parse the catalog's `/Outlines` root into a list of top-level
         * outline items. Returns an empty list if the document has no
         * outline.
         */
        internal fun buildTree(catalog: PdfDictionary, refs: IndirectResolver): List<PdfOutline> {
            val root = catalog.getDict("Outlines", refs) ?: return emptyList()
            val first = root["First"] ?: return emptyList()
            return walkSiblings(first, refs, depth = 0, visited = HashSet())
        }

        /** Walk a sibling chain starting at [start] following `/Next` until exhaustion. */
        private fun walkSiblings(
            start: PdfObject,
            refs: IndirectResolver,
            depth: Int,
            visited: MutableSet<Long>,
        ): List<PdfOutline> {
            if (depth > MAX_DEPTH) return emptyList()
            val acc = mutableListOf<PdfOutline>()
            var cur: PdfObject? = start
            var hops = 0
            while (cur != null && hops < MAX_SIBLINGS) {
                val (dict, refNum) = unwrap(cur, refs) ?: break
                if (refNum != null && !visited.add(refNum)) break  // cycle guard
                acc += parseNode(dict, refs, depth, visited)
                cur = dict["Next"]
                hops++
            }
            return acc
        }

        private fun parseNode(
            dict: PdfDictionary,
            refs: IndirectResolver,
            depth: Int,
            visited: MutableSet<Long>,
        ): PdfOutline {
            val title = (dict["Title"] as? PdfString)?.asText() ?: ""
            val children = dict["First"]?.let {
                walkSiblings(it, refs, depth + 1, visited)
            } ?: emptyList()
            val rawDest = dict["Dest"]
            val action = PdfAction.parse(dict.getDict("A", refs), refs)
            // GoTo actions hold the destination in /A /D; promote it so callers
            // can use rawDestination uniformly. Other action types (URI, Launch)
            // leave rawDestination null and surface via [action].
            val actionDest = if (rawDest == null) (action as? PdfAction.GoTo)?.destination else null
            val count = dict.getInt("Count")?.toInt() ?: 0
            val flags = dict.getInt("F")?.toInt() ?: 0
            val color = (dict.getArray("C", refs))?.let { parseRgb(it) }
            return PdfOutline(
                title = title,
                children = children,
                rawDestination = rawDest ?: actionDest,
                action = action,
                count = count,
                italic = (flags and 1) != 0,
                bold = (flags and 2) != 0,
                color = color,
            )
        }

        private fun unwrap(obj: PdfObject, refs: IndirectResolver): Pair<PdfDictionary, Long?>? =
            when (obj) {
                is PdfReference -> (refs.resolve(obj) as? PdfDictionary)?.let { it to obj.objectNumber }
                is PdfDictionary -> obj to null
                else -> null
            }

        private fun parseRgb(arr: PdfArray): RgbColor? {
            fun n(i: Int): Double = when (val v = arr.getOrNull(i)) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return if (arr.size >= 3) RgbColor(n(0), n(1), n(2)) else null
        }

        private const val MAX_DEPTH = 64
        private const val MAX_SIBLINGS = 8192
    }
}
