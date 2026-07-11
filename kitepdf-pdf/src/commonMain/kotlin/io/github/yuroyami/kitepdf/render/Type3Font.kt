package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * The renderer-side view of a /Subtype /Type3 font (ISO 32000-1 §9.6.5,
 * T-42): glyphs are content streams ([charProcs]) drawn in glyph space and
 * mapped by [fontMatrix]; widths live in GLYPH space (unlike every other
 * font's 1/1000 em). Text extraction still runs through the ordinary
 * [io.github.yuroyami.kitepdf.font.PdfFont] pipeline (its /Encoding
 * /Differences handling is shared); this class only feeds the paint path.
 */
internal class Type3Data(
    val charProcs: Map<String, PdfStream>,
    val fontMatrix: Matrix,
    val resources: PdfDictionary?,
    /** Glyph name per byte code, from /Encoding /Differences. */
    val nameForCode: Array<String?>,
    private val widths: DoubleArray,
    private val hasWidth: BooleanArray,
) {

    /** Glyph-space width for [code]; 0 when /Widths doesn't define one. */
    fun widthFor(code: Int): Double = if (code in 0..255 && hasWidth[code]) widths[code] else 0.0

    companion object {

        fun parse(dict: PdfDictionary, refs: IndirectResolver): Type3Data? {
            val cpDict = dict.getDict("CharProcs", refs) ?: return null
            val procs = cpDict.map.mapNotNull { (name, raw) ->
                (raw.resolve(refs) as? PdfStream)?.let { name to it }
            }.toMap()
            if (procs.isEmpty()) return null

            val fm = dict.getArray("FontMatrix")?.let { arr ->
                if (arr.size >= 6) Matrix(arr.num(0), arr.num(1), arr.num(2), arr.num(3), arr.num(4), arr.num(5))
                else null
            } ?: Matrix(0.001, 0.0, 0.0, 0.001, 0.0, 0.0)

            // /Encoding /Differences: integers set the next code, names assign.
            val names = arrayOfNulls<String>(256)
            val encoding = dict["Encoding"]?.resolve(refs) as? PdfDictionary
            val differences = encoding?.getArray("Differences", refs)
            if (differences != null) {
                var code = 0
                for (item in differences) {
                    when (item) {
                        is PdfInt -> code = item.value.toInt()
                        is PdfName -> if (code in 0..255) names[code++] = item.value
                        else -> Unit
                    }
                }
            }

            val widths = DoubleArray(256)
            val hasWidth = BooleanArray(256)
            val firstChar = dict.getInt("FirstChar")?.toInt() ?: 0
            dict.getArray("Widths", refs)?.let { arr ->
                for (i in 0 until arr.size) {
                    val code = firstChar + i
                    if (code in 0..255) {
                        widths[code] = arr.num(i)
                        hasWidth[code] = true
                    }
                }
            }
            return Type3Data(procs, fm, dict.getDict("Resources", refs), names, widths, hasWidth)
        }

        private fun PdfArray.num(i: Int): Double = when (val v = this[i]) {
            is PdfReal -> v.value
            is PdfInt -> v.value.toDouble()
            else -> 0.0
        }
    }
}
