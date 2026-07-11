package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * A resolved page destination (ISO 32000-1 §12.3.2). Holds the destination's
 * target page (when we can resolve it) and the view-fit parameters.
 *
 * Construct via [PdfDocument.resolveDestination] — it handles all four forms:
 *
 * 1. An explicit array `[page /XYZ left top zoom]`
 * 2. A name-string referring into the catalog `/Dests` dict (PDF 1.1)
 * 3. A name-string referring into the `/Names /Dests` name tree (PDF 1.2+)
 * 4. A dictionary with `/D` pointing at any of the above
 */
public data class PdfDestination(
    val view: ViewFit,
    /** Zero-based page index, or `null` if the page reference didn't resolve. */
    val pageIndex: Int?,
    /** Page-space coordinate args used by the fit mode. Empty for /Fit. */
    val args: DoubleArray,
) {
    public sealed class ViewFit {
        public object Fit : ViewFit()
        public object FitB : ViewFit()
        public data class XYZ(val left: Double?, val top: Double?, val zoom: Double?) : ViewFit()
        public data class FitH(val top: Double?) : ViewFit()
        public data class FitBH(val top: Double?) : ViewFit()
        public data class FitV(val left: Double?) : ViewFit()
        public data class FitBV(val left: Double?) : ViewFit()
        public data class FitR(val left: Double, val bottom: Double, val right: Double, val top: Double) : ViewFit()
    }

    override fun equals(other: Any?): Boolean =
        other is PdfDestination && view == other.view && pageIndex == other.pageIndex &&
            args.contentEquals(other.args)

    override fun hashCode(): Int =
        31 * (31 * view.hashCode() + (pageIndex ?: -1)) + args.contentHashCode()
}

/**
 * Catalog-level destination lookup tables: `/Dests` dict (PDF 1.1) and
 * `/Names /Dests` name tree (PDF 1.2+). Lazily built and cached on the
 * [PdfDocument].
 */
internal class DestinationCatalog(
    private val byNameLegacy: Map<String, PdfObject>,
    private val byNameTree: Map<String, PdfObject>,
) {

    /** Resolve a /Dest name into the raw destination object, or `null`. */
    fun lookup(name: String): PdfObject? =
        byNameTree[name] ?: byNameLegacy[name]

    companion object {
        val EMPTY = DestinationCatalog(emptyMap(), emptyMap())

        fun build(catalog: PdfDictionary, refs: IndirectResolver): DestinationCatalog {
            val legacy = (catalog.getDict("Dests", refs))?.map ?: emptyMap()
            val names = catalog.getDict("Names", refs)
            val destsTree = names?.getDict("Dests", refs)
            val tree = LinkedHashMap<String, PdfObject>()
            if (destsTree != null) walkNameTree(destsTree, refs, tree)
            return DestinationCatalog(legacy, tree)
        }

        private fun walkNameTree(
            node: PdfDictionary,
            refs: IndirectResolver,
            out: MutableMap<String, PdfObject>,
        ) {
            val names = node.getArray("Names", refs)
            if (names != null) {
                var i = 0
                while (i + 1 < names.size) {
                    val key = (names[i] as? PdfString)?.asText()
                    if (key != null) out[key] = names[i + 1]
                    i += 2
                }
            }
            val kids = node.getArray("Kids", refs) ?: return
            for (kid in kids) {
                val kidDict = when (kid) {
                    is PdfReference -> refs.resolve(kid) as? PdfDictionary
                    is PdfDictionary -> kid
                    else -> null
                } ?: continue
                walkNameTree(kidDict, refs, out)
            }
        }
    }
}

internal object DestinationParser {

    /**
     * Resolve a `/Dest` value to a [PdfDestination]. Handles all forms; the
     * `pageIndex` slot is populated by mapping the resolved page object's
     * reference back through [pageIndexByRef].
     */
    fun resolve(
        raw: PdfObject?,
        catalog: DestinationCatalog,
        refs: IndirectResolver,
        pageIndexByRef: Map<Long, Int>,
    ): PdfDestination? {
        val expanded = expand(raw, catalog, refs) ?: return null
        return when (expanded) {
            is PdfArray -> fromExplicit(expanded, refs, pageIndexByRef)
            else -> null
        }
    }

    /**
     * Unwrap layers of indirection: name-string → catalog lookup → dict-with-/D
     * → finally an explicit destination array.
     */
    private fun expand(
        raw: PdfObject?,
        catalog: DestinationCatalog,
        refs: IndirectResolver,
    ): PdfObject? {
        var cur: PdfObject = raw ?: return null
        var hops = 0
        while (hops++ < 8) {
            when (cur) {
                is PdfReference -> cur = refs.resolve(cur) ?: return null
                is PdfDictionary -> {
                    val d = cur["D"] ?: return null
                    cur = d
                }
                is PdfName -> {
                    cur = catalog.lookup(cur.value) ?: return null
                }
                is PdfString -> {
                    cur = catalog.lookup(cur.asText()) ?: return null
                }
                is PdfArray -> return cur
                else -> return null
            }
        }
        return null
    }

    private fun fromExplicit(
        arr: PdfArray,
        refs: IndirectResolver,
        pageIndexByRef: Map<Long, Int>,
    ): PdfDestination? {
        if (arr.isEmpty()) return null
        val pageEntry = arr[0]
        val pageIndex: Int? = when (pageEntry) {
            is PdfReference -> pageIndexByRef[pageEntry.objectNumber]
            is PdfInt -> pageEntry.value.toInt() // remote-go-to: page number
            else -> null
        }
        val mode = (arr.getOrNull(1) as? PdfName)?.value ?: "XYZ"
        fun num(i: Int): Double? = when (val v = arr.getOrNull(i)) {
            is PdfReal -> v.value
            is PdfInt -> v.value.toDouble()
            is PdfName -> if (v.value == "null") null else null
            else -> null
        }
        val view: PdfDestination.ViewFit = when (mode) {
            "Fit" -> PdfDestination.ViewFit.Fit
            "FitB" -> PdfDestination.ViewFit.FitB
            "FitH" -> PdfDestination.ViewFit.FitH(num(2))
            "FitBH" -> PdfDestination.ViewFit.FitBH(num(2))
            "FitV" -> PdfDestination.ViewFit.FitV(num(2))
            "FitBV" -> PdfDestination.ViewFit.FitBV(num(2))
            "FitR" -> PdfDestination.ViewFit.FitR(
                num(2) ?: 0.0, num(3) ?: 0.0, num(4) ?: 0.0, num(5) ?: 0.0,
            )
            else -> PdfDestination.ViewFit.XYZ(num(2), num(3), num(4))
        }
        val args = (2 until arr.size).mapNotNull { num(it) }.toDoubleArray()
        return PdfDestination(view, pageIndex, args)
    }
}
