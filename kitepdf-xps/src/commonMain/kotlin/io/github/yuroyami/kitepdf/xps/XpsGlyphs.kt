package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.font.CffFont
import io.github.yuroyami.kitepdf.core.font.Encodings
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.font.TrueTypeFont
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

internal class XpsFont(val ttf: TrueTypeFont, private val cff: CffFont?) {
    fun outline(gid: Int): KitePath? = if (cff == null) ttf.outlinePath(gid) else cff.outline(gid)

    companion object {
        fun parse(bytes: ByteArray, face: Int): XpsFont? {
            val sfnt = collectionFace(bytes, face) ?: return null
            val ttf = TrueTypeFont.parse(sfnt)
            val cff = ttf.rawTable("CFF ")?.let { CffFont.parse(it) }
            return XpsFont(ttf, cff)
        }

        // TTC table offsets address the collection, not the individual face.
        // Prepend the selected SFNT directory and relocate its table offsets;
        // the original collection stays intact behind it. ECMA-388 §9.1.7.
        private fun collectionFace(data: ByteArray, face: Int): ByteArray? {
            fun u16(at: Int): Int = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
            fun u32(at: Int): Long {
                var value = 0L
                for (i in at until at + 4) value = (value shl 8) or (data[i].toLong() and 255)
                return value
            }
            if (data.size < 12) return null
            if (data.decodeToString(0, 4) != "ttcf") return data.takeIf { face == 0 }
            val count = u32(8)
            if (face.toLong() >= count || 12L + count * 4 > data.size) return null
            val offset = u32(12 + face * 4)
            if (offset + 12 > data.size) return null
            val at = offset.toInt()
            val tables = u16(at + 4)
            val header = 12 + tables * 16
            if (offset + header > data.size || data.size.toLong() + header > Int.MAX_VALUE) return null
            val result = ByteArray(data.size + header)
            data.copyInto(result, 0, at, at + header)
            data.copyInto(result, header)
            for (i in 0 until tables) {
                val source = at + 12 + i * 16
                val tableOffset = u32(source + 8)
                val length = u32(source + 12)
                if (tableOffset + length > data.size) return null
                val relocated = tableOffset + header
                val dest = 12 + i * 16 + 8
                for (j in 0..3) result[dest + j] = (relocated ushr (24 - j * 8)).toByte()
            }
            return result
        }
    }
}

internal data class XpsPositionedGlyph(val glyph: TextGlyph, val transform: KiteMatrix, val advance: Double, val penX: Double, val penY: Double)
internal data class XpsGlyphRun(
    val glyphs: List<XpsPositionedGlyph>,
    val fontSize: Double,
    val unitsPerEm: Int,
    val embedded: Boolean,
    val spec: FontSpec,
)

/** Explicit glyph IDs and cluster/metric overrides, ECMA-388 §§12.1.2-6. */
internal fun layoutGlyphs(
    el: KiteXmlNode.Element,
    font: XpsFont?,
    outlines: Boolean,
    maxGlyphs: Int = 100_000,
): XpsGlyphRun {
    val size = el.number("fontrenderingemsize", 12.0).coerceAtLeast(0.0)
    val unicode = el.attrs["unicodestring"].orEmpty().removePrefix("{}")
    val entries = el.attrs["indices"]?.split(';').orEmpty()
    val rtl = (el.attrs["bidilevel"]?.toIntOrNull() ?: 0) and 1 != 0
    val sideways = el.attrs["issideways"] == "true"
    val style = el.attrs["stylesimulations"].orEmpty()
    val spec = FontSpec.SansSerif.copy(bold = "Bold" in style, italic = "Italic" in style)
    val em = font?.ttf?.unitsPerEm ?: 1000
    var x = el.number("originx", 0.0)
    val y = el.number("originy", 0.0)
    var charAt = 0
    var entryAt = 0
    val result = ArrayList<XpsPositionedGlyph>()
    while ((charAt < unicode.length || entryAt < entries.size) && result.size < maxGlyphs) {
        var entry = entries.getOrNull(entryAt).orEmpty().trim()
        var charCount = if (charAt + 1 < unicode.length && unicode[charAt].isHighSurrogate() &&
            unicode[charAt + 1].isLowSurrogate()) 2 else 1
        var glyphCount = 1
        if (entry.startsWith('(')) {
            val close = entry.indexOf(')')
            if (close < 0) { entryAt++; continue }
            val counts = entry.substring(1, close).split(':')
            charCount = counts[0].toIntOrNull()?.coerceIn(1, 65536) ?: 1
            glyphCount = counts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 65536) ?: 1
            entry = entry.substring(close + 1)
        }
        val from = charAt
        val to = minOf(unicode.length, charAt + charCount)
        val text = if (from < to) unicode.substring(from, to) else ""
        charAt = to
        repeat(minOf(glyphCount, maxGlyphs - result.size)) { clusterIndex ->
            if (clusterIndex > 0) entry = entries.getOrNull(entryAt).orEmpty()
            val fields = entry.split(',')
            val codePoint = if (text.length >= 2 && text[0].isHighSurrogate() && text[1].isLowSurrogate()) {
                0x10000 + ((text[0].code - 0xD800) shl 10) + text[1].code - 0xDC00
            } else text.firstOrNull()?.code ?: 0
            val gid = fields[0].trim().toIntOrNull()?.takeIf { it >= 0 }
                ?: font?.ttf?.glyphIdForCodePoint(codePoint) ?: -1
            val natural = font?.ttf?.advanceWidth(gid)?.toDouble()?.div(em)
                ?: (Encodings.winAnsiEncoding.getOrNull(codePoint)?.let { Standard14Widths.widthOf("Helvetica", it) }
                    ?: 500) / 1000.0
            val defaultAdvance = if (sideways) {
                font?.ttf?.advanceHeight(gid)?.toDouble()?.div(em) ?: 1.0
            } else natural + if (spec.bold) 0.02 else 0.0
            val advance = (fields.getOrNull(1)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?.div(100.0) ?: defaultAdvance) * size
            val u = fields.getOrNull(2)?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)?.times(size / 100.0) ?: 0.0
            val v = fields.getOrNull(3)?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)?.times(size / 100.0) ?: 0.0
            val dx = if (rtl) -natural * size - u else u
            val transform = if (sideways) {
                val ascent = (font?.ttf?.hhea?.ascent?.toDouble()?.div(em) ?: 0.8) * size
                KiteMatrix(0.0, -1.0, -1.0, 0.0, x + u + ascent, y - v + natural * size / 2.0)
            } else {
                KiteMatrix(1.0, 0.0, if (spec.italic) 0.36397 else 0.0, -1.0, x + dx, y - v)
            }
            val glyph = TextGlyph(
                byteOffset = from, byteCount = to - from, gid = gid,
                text = if (clusterIndex == 0) text else "", advanceWidth = natural * 1000,
                outline = if (outlines) runCatching { font?.outline(gid) }.getOrNull() else null,
                isWordSpace = text == " ",
            )
            result.add(XpsPositionedGlyph(glyph, transform, if (rtl) -advance else advance, x, y))
            x += if (rtl) -advance else advance
            entryAt++
        }
    }
    return XpsGlyphRun(result, size, em, font != null, spec)
}
