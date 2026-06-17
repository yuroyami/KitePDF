package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream
import io.github.yuroyami.kitepdf.render.PdfPath

/**
 * A resolved PDF font.
 *
 * The font knows how to:
 *   - [decode] show-text bytes into unicode (text extraction).
 *   - [layoutBytes] walk show-text bytes into a sequence of [TextGlyph]s —
 *     each tagged with its outline (if any), advance, and decoded text.
 *
 * Simple fonts (Type 1, TrueType, Type1C, Type 3) consume one byte per glyph.
 * Type 0 composite fonts (CIDFontType0/2 under a Type 0 parent) consume one
 * code unit per glyph — typically 2 bytes via the Identity-H CMap, sometimes
 * variable. [layoutBytes] is the unified iterator everything downstream uses.
 *
 * Embedded outlines are pulled from whichever of these is present:
 *   - `/FontFile`     → Type 1 (PostScript)
 *   - `/FontFile2`    → TrueType
 *   - `/FontFile3`    → CFF (Type1C or CIDFontType0C)
 *
 * Order tried matches what real fonts actually carry; falls back to the host
 * system font (via [baseFont]) when no outlines are available.
 */
class PdfFont private constructor(
    val baseFont: String,
    val subtype: String,
    /** /Differences-aware glyph-name table for byte codes 0..255 (simple fonts). */
    private val glyphNameForByte: Array<String?>,
    /** Decoded unicode for each byte slot (0 when unknown). */
    private val unicodeForByte: IntArray,
    /** /ToUnicode CMap on this font dict, if any. */
    private val toUnicode: CMap?,
    /** /Widths-derived per-byte widths (simple fonts), 1/1000 em. */
    private val simpleWidths: IntArray,
    private val defaultWidth: Int,
    /** Embedded TrueType (/FontFile2) — for simple TrueType subtypes. */
    private val embeddedTtf: TrueTypeFont?,
    /** Embedded CFF (/FontFile3) — for simple Type1C subtypes. */
    private val embeddedCff: CffFont?,
    /** Embedded Type 1 (/FontFile) — for simple Type1 subtypes. */
    private val embeddedType1: Type1Font?,
    /** Type 0 composite descendant chain (CIDFontType0/2 under a Type0 parent). */
    private val composite: CompositeFont?,
) {

    val isComposite: Boolean get() = composite != null

    val hasEmbeddedOutlines: Boolean
        get() = embeddedTtf != null || embeddedCff != null || embeddedType1 != null ||
            composite?.let { it.ttf != null || it.cff != null } == true

    /** Units-per-em: TTF reports its own; CFF + Type 1 default to 1000. */
    val unitsPerEm: Int?
        get() = embeddedTtf?.unitsPerEm
            ?: composite?.ttf?.unitsPerEm
            ?: (embeddedCff ?: embeddedType1 ?: composite?.cff)?.let { 1000 }

    /* ─── Decoding + layout ──────────────────────────────────────────────── */

    /** Decode show-text bytes (from a `Tj` / `TJ` operand) to unicode text. */
    fun decode(bytes: ByteArray): String {
        composite?.let { return it.decode(bytes) }
        toUnicode?.let { return it.decodeAll(bytes) }
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val cp = unicodeForByte[b.toInt() and 0xFF]
            when {
                cp == 0 -> sb.append((b.toInt() and 0xFF).toChar())
                cp < 0x10000 -> sb.append(cp.toChar())
                else -> {
                    val s = cp - 0x10000
                    sb.append((0xD800 + (s ushr 10)).toChar())
                    sb.append((0xDC00 + (s and 0x3FF)).toChar())
                }
            }
        }
        return sb.toString()
    }

    /**
     * Walk [bytes] one glyph at a time, dispatching to the right code-unit
     * width (1 for simple fonts, usually 2 for Identity-H composites).
     */
    fun layoutBytes(bytes: ByteArray, resolveOutlines: Boolean = true): List<TextGlyph> {
        composite?.let { return layoutComposite(it, bytes, resolveOutlines) }
        return layoutSimple(bytes, resolveOutlines)
    }

    private fun layoutSimple(bytes: ByteArray, resolveOutlines: Boolean): List<TextGlyph> {
        val out = ArrayList<TextGlyph>(bytes.size)
        for ((offset, byteVal) in bytes.withIndex()) {
            val code = byteVal.toInt() and 0xFF
            val gid = simpleGid(code)
            val outline = if (resolveOutlines) simpleOutline(code) else null
            val width = simpleWidth(code, gid).toDouble()
            val text = decodeSingle(code)
            out.add(TextGlyph(
                byteOffset = offset, byteCount = 1, gid = gid,
                text = text, advanceWidth = width, outline = outline,
                isWordSpace = code == 0x20,
            ))
        }
        return out
    }

    private fun layoutComposite(c: CompositeFont, bytes: ByteArray, resolveOutlines: Boolean): List<TextGlyph> {
        val out = ArrayList<TextGlyph>(bytes.size / 2 + 1)
        for (unit in c.codeUnits(bytes)) {
            val outline = if (resolveOutlines) c.outline(unit.cid) else null
            val gid = c.gidFor(unit.cid)
            val width = c.widthOf(unit.cid)
            // Text per glyph — slice the bytes for that code unit and decode.
            val slice = bytes.copyOfRange(unit.byteOffset, unit.byteOffset + unit.byteCount)
            val text = c.toUnicode?.decodeAll(slice) ?: ""
            out.add(TextGlyph(
                byteOffset = unit.byteOffset, byteCount = unit.byteCount, gid = gid,
                text = text, advanceWidth = width, outline = outline,
                isWordSpace = unit.cid == 0x20 || (unit.byteCount == 1 && bytes[unit.byteOffset].toInt() == 0x20),
            ))
        }
        return out
    }

    /**
     * Invoke [action] once per glyph with its advance width (1/1000 em) and
     * word-space flag, WITHOUT resolving any glyph outline or allocating a
     * [TextGlyph] list. The fast path for advance/measurement (text positioning,
     * width sums) where glyph shapes aren't needed — used by the renderer's
     * advance calc, text extraction, and redaction layout.
     */
    fun forEachGlyphAdvance(bytes: ByteArray, action: (advanceWidth: Double, isWordSpace: Boolean) -> Unit) {
        composite?.let { c ->
            var offset = 0
            while (offset < bytes.size) {
                val unit = c.codeReader.next(bytes, offset) ?: break
                val cid = unit.first
                val consumed = unit.second
                val isWordSpace = cid == 0x20 || (consumed == 1 && bytes[offset].toInt() == 0x20)
                action(c.widthOf(cid), isWordSpace)
                offset += consumed
            }
            return
        }
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            action(simpleWidth(code, simpleGid(code)).toDouble(), code == 0x20)
        }
    }

    /* ─── Width-only fast paths (kept for callers that already had byte codes) ─── */

    /** Width (1/1000 em) for a single byte code in a simple font. Composite use [layoutBytes]. */
    fun widthOf(byteCode: Int): Int {
        val w = simpleWidths[byteCode and 0xFF]
        return if (w > 0) w else defaultWidth
    }

    /** True iff the underlying embedded font has its own advance metrics. */
    fun embeddedAdvanceWidth(byteCode: Int): Int? =
        embeddedTtf?.advanceWidth(simpleGid(byteCode))

    /**
     * Per-byte outline lookup. Kept for backward compat with callers that
     * pre-date the [layoutBytes] iterator; [layoutBytes] is the right path
     * for any new code so composite Type 0 fonts work correctly.
     */
    fun outlineForByte(code: Int): PdfPath? = simpleOutline(code)

    /**
     * Glyph id for a single byte code in a simple font. Returns 0 for
     * composite fonts (their glyph IDs come from CIDs, not byte codes).
     */
    fun glyphIdForByte(byteCode: Int): Int = simpleGid(byteCode)

    /* ─── Simple-font helpers (Type 1 / TrueType / Type1C subtypes) ──────── */

    private val gidCache = HashMap<Int, Int>()

    private fun simpleGid(code: Int): Int {
        if (composite != null) return 0
        gidCache[code]?.let { return it }
        val gid = when {
            embeddedTtf != null -> {
                val ttf = embeddedTtf
                // PDF §9.6.6.4 simple-TrueType glyph selection, with subset
                // fallbacks (matches MuPDF): 1) code→name→Unicode via cmap
                // (non-symbolic); 2) raw code (symbolic (1,0)/(3,1)); 3) (3,0)
                // symbol range 0xF000+code; 4) last resort — treat the code AS
                // the glyph index, which subset fonts (e.g. Nitro/MS Office) rely on.
                var g = ttf.glyphIdForCodePoint(resolveByteToUnicode(code))
                if (g == 0) g = ttf.glyphIdForCodePoint(code and 0xFF)
                if (g == 0) g = ttf.glyphIdForCodePoint(0xF000 or (code and 0xFF))
                if (g == 0 && (code and 0xFF) in 1 until ttf.numGlyphs) g = code and 0xFF
                g
            }
            embeddedCff != null -> {
                val gn = glyphNameForByte[code and 0xFF]
                val byName = gn?.let { embeddedCff.glyphIdForName(it) } ?: -1
                if (byName >= 0) byName else embeddedCff.glyphIdForCodePoint(resolveByteToUnicode(code)).coerceAtLeast(0)
            }
            else -> 0
        }
        gidCache[code] = gid
        return gid
    }

    private fun simpleOutline(code: Int): PdfPath? {
        if (composite != null) return null
        embeddedTtf?.let { ttf -> return ttf.outlinePath(simpleGid(code)) }
        embeddedCff?.let { cff -> return cff.outline(simpleGid(code)) }
        embeddedType1?.let { t1 ->
            val gn = glyphNameForByte[code and 0xFF] ?: return null
            return t1.outlineForGlyphName(gn) ?: t1.outlineForByte(code)
        }
        return null
    }

    private fun simpleWidth(code: Int, gid: Int): Int {
        embeddedTtf?.let { ttf ->
            val w = ttf.advanceWidth(gid)
            if (w > 0) return scaleToEm(w, ttf.unitsPerEm)
        }
        return widthOf(code)
    }

    private fun scaleToEm(advance: Int, upm: Int): Int {
        if (upm == 1000) return advance
        return (advance.toDouble() * 1000.0 / upm).toInt()
    }

    private fun resolveByteToUnicode(code: Int): Int {
        val glyphName = glyphNameForByte[code and 0xFF]
        return when {
            glyphName != null -> {
                GlyphList.unicodeFor(glyphName)
                    ?: parseUniName(glyphName)
                    ?: unicodeForByte[code and 0xFF].takeIf { it != 0 }
                    ?: code and 0xFF
            }
            else -> unicodeForByte[code and 0xFF].takeIf { it != 0 } ?: (code and 0xFF)
        }
    }

    private fun decodeSingle(code: Int): String {
        toUnicode?.decode(byteArrayOf(code.toByte()), 0)?.let { return it.first }
        val cp = unicodeForByte[code and 0xFF]
        return when {
            cp == 0 -> (code and 0xFF).toChar().toString()
            cp < 0x10000 -> cp.toChar().toString()
            else -> {
                val s = cp - 0x10000
                charArrayOf((0xD800 + (s ushr 10)).toChar(), (0xDC00 + (s and 0x3FF)).toChar()).concatToString()
            }
        }
    }

    private fun parseUniName(name: String): Int? = when {
        name.startsWith("uni") && name.length >= 7 -> name.substring(3, 7).toIntOrNull(16)
        name.startsWith("u") && name.length in 5..7 -> name.substring(1).toIntOrNull(16)
        else -> null
    }

    companion object {

        /**
         * Build a [PdfFont] from one resource entry. [refs] resolves indirect
         * references inside font / encoding / ToUnicode / descendant dicts.
         */
        fun from(fontObj: Any, refs: IndirectResolver): PdfFont {
            val dict: PdfDictionary = when (fontObj) {
                is PdfDictionary -> fontObj
                is PdfReference -> refs.resolve(fontObj) as? PdfDictionary ?: return fallback()
                else -> return fallback()
            }

            val subtype = dict.getName("Subtype") ?: "Type1"
            val rawName = dict.getName("BaseFont") ?: dict.getName("Name") ?: "Helvetica"
            val baseFont = stripSubsetTag(rawName)

            // Type 0 composite fonts get their own pipeline (Identity-H + descendants).
            if (subtype == "Type0") {
                val comp = CompositeFont.from(dict, refs) ?: return fallback()
                return PdfFont(
                    baseFont, subtype,
                    glyphNameForByte = Array(256) { null },
                    unicodeForByte = IntArray(256),
                    toUnicode = null,
                    simpleWidths = IntArray(256),
                    defaultWidth = 1000,
                    embeddedTtf = null, embeddedCff = null, embeddedType1 = null,
                    composite = comp,
                )
            }

            // Simple-font pipeline (Type 1 / TrueType / Type1C / Type 3).
            val nameTable = resolveEncoding(dict, baseFont, refs)
            val unicodeTable = IntArray(256) { i ->
                val gn = nameTable[i] ?: return@IntArray 0
                GlyphList.unicodeFor(gn) ?: 0
            }
            val toUnicode = loadToUnicode(dict, refs)
            val (widths, defaultWidth) = resolveWidths(dict, baseFont, nameTable, refs)

            val descriptor = dict["FontDescriptor"]?.resolve(refs) as? PdfDictionary
            val embeddedTtf = descriptor?.let { loadEmbeddedTtf(it, refs) }
            val embeddedCff = if (embeddedTtf == null) descriptor?.let { loadEmbeddedCff(it, refs) } else null
            val embeddedType1 = if (embeddedTtf == null && embeddedCff == null)
                descriptor?.let { loadEmbeddedType1(it, refs) } else null

            return PdfFont(
                baseFont, subtype, nameTable, unicodeTable, toUnicode, widths, defaultWidth,
                embeddedTtf, embeddedCff, embeddedType1, composite = null,
            )
        }

        /* ─── Embedded outline loaders ───────────────────────────────────── */

        private fun loadEmbeddedTtf(descriptor: PdfDictionary, refs: IndirectResolver): TrueTypeFont? {
            val stream = (descriptor["FontFile2"]?.resolve(refs) as? PdfStream) ?: return null
            return runCatching { TrueTypeFont.parse(FilterChain.decode(stream)) }.getOrNull()
        }

        private fun loadEmbeddedCff(descriptor: PdfDictionary, refs: IndirectResolver): CffFont? {
            val stream = (descriptor["FontFile3"]?.resolve(refs) as? PdfStream) ?: return null
            return runCatching { CffFont.parse(FilterChain.decode(stream)) }.getOrNull()
        }

        private fun loadEmbeddedType1(descriptor: PdfDictionary, refs: IndirectResolver): Type1Font? {
            val stream = (descriptor["FontFile"]?.resolve(refs) as? PdfStream) ?: return null
            val length1 = stream.dict.getInt("Length1")?.toInt() ?: 0
            val length2 = stream.dict.getInt("Length2")?.toInt() ?: 0
            if (length1 <= 0 || length2 <= 0) return null
            return runCatching {
                Type1Font.parse(FilterChain.decode(stream), length1, length2)
            }.getOrNull()
        }

        /* ─── /Encoding + /Differences ───────────────────────────────────── */

        private fun resolveEncoding(
            dict: PdfDictionary,
            baseFont: String,
            refs: IndirectResolver,
        ): Array<String?> {
            val raw = dict["Encoding"]?.resolve(refs)
            return when {
                raw is PdfName -> namedEncoding(raw.value) ?: defaultEncodingFor(baseFont)
                raw is PdfDictionary -> {
                    val baseName = raw.getName("BaseEncoding")
                    val baseArr = baseName?.let(::namedEncoding) ?: defaultEncodingFor(baseFont)
                    applyDifferences(baseArr.copyOf(), raw.getArray("Differences"))
                }
                else -> defaultEncodingFor(baseFont)
            }
        }

        private fun namedEncoding(name: String): Array<String?>? = when (name) {
            "WinAnsiEncoding" -> Encodings.winAnsiEncoding
            "MacRomanEncoding" -> Encodings.macRomanEncoding
            "StandardEncoding" -> Encodings.standardEncoding
            "MacExpertEncoding" -> Encodings.standardEncoding
            else -> null
        }

        private fun defaultEncodingFor(baseFont: String): Array<String?> = when (baseFont) {
            "Symbol", "ZapfDingbats" -> Array(256) { null }
            else -> Encodings.standardEncoding
        }

        private fun applyDifferences(table: Array<String?>, diffs: PdfArray?): Array<String?> {
            if (diffs == null) return table
            var code = 0
            for (item in diffs) {
                when (item) {
                    is PdfInt -> code = item.value.toInt()
                    is PdfName -> {
                        if (code in 0..255) table[code] = item.value
                        code++
                    }
                    else -> { /* ignore */ }
                }
            }
            return table
        }

        private fun loadToUnicode(dict: PdfDictionary, refs: IndirectResolver): CMap? {
            val ref = dict["ToUnicode"] ?: return null
            val resolved = ref.resolve(refs) as? PdfStream ?: return null
            return runCatching { CMap.parse(FilterChain.decode(resolved)) }.getOrNull()
        }

        private fun resolveWidths(
            dict: PdfDictionary,
            baseFont: String,
            nameTable: Array<String?>,
            refs: IndirectResolver,
        ): Pair<IntArray, Int> {
            val widths = IntArray(256)
            for (i in 0..255) {
                val gn = nameTable[i] ?: continue
                widths[i] = Standard14Widths.widthOf(baseFont, gn) ?: 0
            }
            val firstChar = dict.getInt("FirstChar")?.toInt() ?: -1
            val arr = dict.getArray("Widths", refs) // /Widths is often an indirect reference
            if (firstChar in 0..255 && arr != null) {
                for ((idx, w) in arr.withIndex()) {
                    val code = firstChar + idx
                    if (code !in 0..255) break
                    widths[code] = when (w) {
                        is PdfInt -> w.value.toInt()
                        is PdfReal -> w.value.toInt()
                        else -> widths[code]
                    }
                }
            }
            val defaultWidth = dict.getInt("MissingWidth")?.toInt() ?: 500
            return widths to defaultWidth
        }

        private fun stripSubsetTag(name: String): String {
            val plus = name.indexOf('+')
            return if (plus == 6) name.substring(plus + 1) else name
        }

        private fun fallback(): PdfFont = PdfFont(
            baseFont = "Helvetica",
            subtype = "Type1",
            glyphNameForByte = Encodings.standardEncoding,
            unicodeForByte = IntArray(256) { i -> if (i in 32..126) i else 0 },
            toUnicode = null,
            simpleWidths = IntArray(256),
            defaultWidth = 500,
            embeddedTtf = null, embeddedCff = null, embeddedType1 = null,
            composite = null,
        )
    }
}
