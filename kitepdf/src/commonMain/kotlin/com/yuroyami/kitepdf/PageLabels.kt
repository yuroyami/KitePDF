package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfString

/**
 * One `/PageLabels` range entry (ISO 32000-1 §12.4.2 Table 159).
 *
 * - [style] — `null` means "prefix only, no number"
 * - [prefix] — concatenated in front of the formatted number
 * - [start] — first numeric value to use for the first page in this range
 *
 * The range applies to the slice of pages from this entry's key index (in the
 * number tree) up to but not including the next entry's key.
 */
internal data class PageLabelRange(
    val firstPageIndex: Int,
    val style: NumberStyle?,
    val prefix: String,
    val start: Int,
) {
    enum class NumberStyle { Decimal, UppercaseRoman, LowercaseRoman, UppercaseLetters, LowercaseLetters }

    fun labelFor(pageIndex: Int): String {
        val offset = pageIndex - firstPageIndex
        val n = start + offset
        val numberPart = when (style) {
            null -> ""
            NumberStyle.Decimal -> n.toString()
            NumberStyle.UppercaseRoman -> toRoman(n)
            NumberStyle.LowercaseRoman -> toRoman(n).lowercase()
            NumberStyle.UppercaseLetters -> toLetters(n, base = 'A')
            NumberStyle.LowercaseLetters -> toLetters(n, base = 'a')
        }
        return prefix + numberPart
    }

    companion object {
        fun parse(firstPageIndex: Int, dict: PdfDictionary): PageLabelRange {
            val style = when (dict.getName("S")) {
                "D" -> NumberStyle.Decimal
                "R" -> NumberStyle.UppercaseRoman
                "r" -> NumberStyle.LowercaseRoman
                "A" -> NumberStyle.UppercaseLetters
                "a" -> NumberStyle.LowercaseLetters
                else -> null
            }
            val prefix = (dict["P"] as? PdfString)?.asText() ?: ""
            val start = dict.getInt("St")?.toInt() ?: 1
            return PageLabelRange(firstPageIndex, style, prefix, start)
        }
    }
}

/**
 * Resolved page-label tree for a document. Indexed by zero-based page number.
 *
 * The PDF format stores labels as a "number tree" (§7.9.7) that compactly
 * encodes piecewise-defined labels: e.g. pages 0–3 get lowercase Roman, 4+
 * get decimal arabic, etc. We flatten that into a sorted list of ranges and
 * binary-search at lookup time.
 */
internal class PageLabelTree(private val ranges: List<PageLabelRange>) {

    fun labelOf(pageIndex: Int): String? {
        if (ranges.isEmpty()) return null
        // Find the last range whose firstPageIndex <= pageIndex.
        var lo = 0
        var hi = ranges.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ranges[mid].firstPageIndex <= pageIndex) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        return ranges[found].labelFor(pageIndex)
    }

    companion object {
        /** Empty tree (used when /PageLabels is missing). */
        val EMPTY = PageLabelTree(emptyList())

        fun parse(root: PdfDictionary, refs: IndirectResolver): PageLabelTree {
            val acc = mutableListOf<PageLabelRange>()
            walk(root, refs, acc)
            acc.sortBy { it.firstPageIndex }
            return PageLabelTree(acc)
        }

        private fun walk(
            node: PdfDictionary,
            refs: IndirectResolver,
            out: MutableList<PageLabelRange>,
        ) {
            val nums = node.getArray("Nums", refs)
            if (nums != null) {
                var i = 0
                while (i + 1 < nums.size) {
                    val key = (nums[i] as? PdfInt)?.value?.toInt()
                    val valueDict = when (val v = nums[i + 1]) {
                        is PdfReference -> refs.resolve(v) as? PdfDictionary
                        is PdfDictionary -> v
                        else -> null
                    }
                    if (key != null && valueDict != null) {
                        out += PageLabelRange.parse(key, valueDict)
                    }
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
                walk(kidDict, refs, out)
            }
        }
    }
}

/** Convert 1..3999 to uppercase Roman. Values outside that range are formatted as decimal. */
private fun toRoman(n: Int): String {
    if (n !in 1..3999) return n.toString()
    val pairs = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
        100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
        10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )
    var v = n
    return buildString {
        for ((value, sym) in pairs) {
            while (v >= value) { append(sym); v -= value }
        }
    }
}

/**
 * Convert a positive integer to alphabetic labels: 1→A, 26→Z, 27→AA, 52→ZZ,
 * 53→AAA, etc. This is the PDF spec's "spreadsheet column" scheme: the
 * letter repeats `ceil(n/26)` times.
 */
private fun toLetters(n: Int, base: Char): String {
    if (n <= 0) return n.toString()
    val groupSize = 26
    val repeats = (n - 1) / groupSize + 1
    val letterIndex = (n - 1) % groupSize
    val ch = (base.code + letterIndex).toChar()
    return ch.toString().repeat(repeats)
}
