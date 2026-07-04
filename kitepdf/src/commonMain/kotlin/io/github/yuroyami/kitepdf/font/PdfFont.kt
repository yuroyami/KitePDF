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
    /**
     * Presence flag paralleling [simpleWidths]: true when a width is DEFINED for
     * this code (from /Widths or standard-14 metrics), even if that width is 0.
     * Distinguishes a real 0 (combining marks) from "absent → use fallback".
     */
    private val hasSimpleWidth: BooleanArray,
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
                // ISO 32000-1 §9.3.3: word spacing (Tw) applies ONLY to a
                // SINGLE-byte code equal to 32 — never to a 2-byte CID 0x20.
                isWordSpace = unit.byteCount == 1 && (bytes[unit.byteOffset].toInt() and 0xFF) == 0x20,
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
                val unit = c.nextCodeUnit(bytes, offset) ?: break
                val cid = unit.first
                val consumed = unit.second
                // Tw applies ONLY to single-byte code 32 (ISO 32000-1 §9.3.3).
                val isWordSpace = consumed == 1 && (bytes[offset].toInt() and 0xFF) == 0x20
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
        val code = byteCode and 0xFF
        // A DEFINED width wins even when it is exactly 0 (e.g. combining marks);
        // only a truly absent entry falls back to /MissingWidth (defaultWidth).
        return if (hasSimpleWidth[code]) simpleWidths[code] else defaultWidth
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
        // ISO 32000-1 §9.6.2.1 / MuPDF: the font dictionary's /Widths array is
        // AUTHORITATIVE — a width defined there (or by standard-14 metrics) wins
        // over the embedded font's own advance. The embedded hmtx/CFF advance is
        // consulted ONLY as a fallback for codes with no dictionary width.
        val c = code and 0xFF
        if (hasSimpleWidth[c]) return simpleWidths[c]
        embeddedTtf?.let { ttf ->
            val w = ttf.advanceWidth(gid)
            if (w > 0) return scaleToEm(w, ttf.unitsPerEm)
        }
        return defaultWidth
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
                    hasSimpleWidth = BooleanArray(256),
                    defaultWidth = 1000,
                    embeddedTtf = null, embeddedCff = null, embeddedType1 = null,
                    composite = comp,
                )
            }

            // Simple-font pipeline (Type 1 / TrueType / Type1C / Type 3).
            val descriptor = dict["FontDescriptor"]?.resolve(refs) as? PdfDictionary
            val flags = descriptor?.getInt("Flags")?.toInt() ?: 0
            val nameTable = resolveEncoding(dict, baseFont, subtype, flags, refs)
            val unicodeTable = IntArray(256) { i ->
                val gn = nameTable[i] ?: return@IntArray 0
                GlyphList.unicodeFor(gn) ?: 0
            }
            val toUnicode = loadToUnicode(dict, refs)
            val wt = resolveWidths(dict, baseFont, nameTable, refs)

            val embeddedTtf = descriptor?.let { loadEmbeddedTtf(it, refs) }
            val embeddedCff = if (embeddedTtf == null) descriptor?.let { loadEmbeddedCff(it, refs) } else null
            val embeddedType1 = if (embeddedTtf == null && embeddedCff == null)
                descriptor?.let { loadEmbeddedType1(it, refs) } else null

            return PdfFont(
                baseFont, subtype, nameTable, unicodeTable, toUnicode,
                wt.widths, wt.present, wt.missingWidth,
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

        /** /Flags bit 3 (value 4) — symbolic font (ISO 32000-1 Table 121). */
        private const val FLAG_SYMBOLIC = 1 shl 2
        /** /Flags bit 6 (value 32) — non-symbolic. */
        private const val FLAG_NONSYMBOLIC = 1 shl 5

        private fun resolveEncoding(
            dict: PdfDictionary,
            baseFont: String,
            subtype: String,
            flags: Int,
            refs: IndirectResolver,
        ): Array<String?> {
            val raw = dict["Encoding"]?.resolve(refs)
            return when {
                raw is PdfName -> namedEncoding(raw.value) ?: defaultEncodingFor(baseFont, subtype, flags)
                raw is PdfDictionary -> {
                    val baseName = raw.getName("BaseEncoding")
                    val baseArr = baseName?.let(::namedEncoding) ?: defaultEncodingFor(baseFont, subtype, flags)
                    applyDifferences(baseArr.copyOf(), raw.getArray("Differences"))
                }
                else -> defaultEncodingFor(baseFont, subtype, flags)
            }
        }

        private fun namedEncoding(name: String): Array<String?>? = when (name) {
            "WinAnsiEncoding" -> Encodings.winAnsiEncoding
            "MacRomanEncoding" -> Encodings.macRomanEncoding
            "StandardEncoding" -> Encodings.standardEncoding
            "MacExpertEncoding" -> macExpertEncoding
            else -> null
        }

        /**
         * Default byte→glyph-name table when /Encoding is absent or unnamed.
         *
         * - Symbol / ZapfDingbats: their built-in Adobe encoding vectors (so the
         *   standard-14 metrics and glyph names resolve instead of collapsing to
         *   500-wide, name-less glyphs).
         * - Symbolic TrueType (ISO 32000-1 §9.6.6.4): NO standard encoding is
         *   imposed — the embedded font's own cmap ((3,0)/(1,0)) drives glyph
         *   selection, so we return an all-null table and let [simpleGid] fall
         *   through to the raw-code / 0xF000 cmap paths.
         * - Everything else: StandardEncoding.
         */
        private fun defaultEncodingFor(baseFont: String, subtype: String, flags: Int): Array<String?> = when {
            baseFont == "Symbol" -> symbolEncoding
            baseFont == "ZapfDingbats" -> zapfDingbatsEncoding
            subtype == "TrueType" &&
                (flags and FLAG_SYMBOLIC) != 0 && (flags and FLAG_NONSYMBOLIC) == 0 ->
                Array(256) { null }
            else -> Encodings.standardEncoding
        }

        /**
         * Build a code→glyph-name table from a whitespace-separated
         * "code=name" spec (octal-free, decimal codes). Codes not listed are null.
         */
        private fun encodingFromSpec(spec: String): Array<String?> {
            val out = arrayOfNulls<String>(256)
            for (tok in spec.trim().split(Regex("\\s+"))) {
                if (tok.isEmpty()) continue
                val eq = tok.indexOf('=')
                if (eq <= 0) continue
                val code = tok.substring(0, eq).toIntOrNull() ?: continue
                if (code in 0..255) out[code] = tok.substring(eq + 1)
            }
            return out
        }

        /** Adobe Symbol built-in encoding (AFM StartCharMetrics ordering). */
        private val symbolEncoding: Array<String?> by lazy {
            encodingFromSpec(
                "32=space 33=exclam 34=universal 35=numbersign 36=existential 37=percent " +
                "38=ampersand 39=suchthat 40=parenleft 41=parenright 42=asteriskmath 43=plus " +
                "44=comma 45=minus 46=period 47=slash 48=zero 49=one 50=two 51=three 52=four " +
                "53=five 54=six 55=seven 56=eight 57=nine 58=colon 59=semicolon 60=less 61=equal " +
                "62=greater 63=question 64=congruent 65=Alpha 66=Beta 67=Chi 68=Delta 69=Epsilon " +
                "70=Phi 71=Gamma 72=Eta 73=Iota 74=theta1 75=Kappa 76=Lambda 77=Mu 78=Nu 79=Omicron " +
                "80=Pi 81=Theta 82=Rho 83=Sigma 84=Tau 85=Upsilon 86=sigma1 87=Omega 88=Xi 89=Psi " +
                "90=Zeta 91=bracketleft 92=therefore 93=bracketright 94=perpendicular 95=underscore " +
                "96=radicalex 97=alpha 98=beta 99=chi 100=delta 101=epsilon 102=phi 103=gamma 104=eta " +
                "105=iota 106=phi1 107=kappa 108=lambda 109=mu 110=nu 111=omicron 112=pi 113=theta " +
                "114=rho 115=sigma 116=tau 117=upsilon 118=omega1 119=omega 120=xi 121=psi 122=zeta " +
                "123=braceleft 124=bar 125=braceright 126=similar 160=Euro 161=Upsilon1 162=minute " +
                "163=lessequal 164=fraction 165=infinity 166=florin 167=club 168=diamond 169=heart " +
                "170=spade 171=arrowboth 172=arrowleft 173=arrowup 174=arrowright 175=arrowdown " +
                "176=degree 177=plusminus 178=second 179=greaterequal 180=multiply 181=proportional " +
                "182=partialdiff 183=bullet 184=divide 185=notequal 186=equivalence 187=approxequal " +
                "188=ellipsis 189=arrowvertex 190=arrowhorizex 191=carriagereturn 192=aleph " +
                "193=Ifraktur 194=Rfraktur 195=weierstrass 196=circlemultiply 197=circleplus " +
                "198=emptyset 199=intersection 200=union 201=propersuperset 202=reflexsuperset " +
                "203=notsubset 204=propersubset 205=reflexsubset 206=element 207=notelement 208=angle " +
                "209=gradient 210=registerserif 211=copyrightserif 212=trademarkserif 213=product " +
                "214=radical 215=dotmath 216=logicalnot 217=logicaland 218=logicalor 219=arrowdblboth " +
                "220=arrowdblleft 221=arrowdblup 222=arrowdblright 223=arrowdbldown 224=lozenge " +
                "225=angleleft 226=registersans 227=copyrightsans 228=trademarksans 229=summation " +
                "230=parenlefttp 231=parenleftex 232=parenleftbt 233=bracketlefttp 234=bracketleftex " +
                "235=bracketleftbt 236=bracelefttp 237=braceleftmid 238=braceleftbt 239=braceex " +
                "241=angleright 242=integral 243=integraltp 244=integralex 245=integralbt " +
                "246=parenrighttp 247=parenrightex 248=parenrightbt 249=bracketrighttp " +
                "250=bracketrightex 251=bracketrightbt 252=bracerighttp 253=bracerightmid " +
                "254=bracerightbt",
            )
        }

        /** Adobe ZapfDingbats built-in encoding. */
        private val zapfDingbatsEncoding: Array<String?> by lazy {
            val sb = StringBuilder("32=space ")
            // Codes 33..126 map to a1..a94 with a couple of gaps folded into the
            // AFM ordering; use the canonical ZapfDingbats vector.
            val low = intArrayOf(
                1, 2, 202, 3, 4, 5, 119, 118, 117, 11, 12, 13, 14, 15, 16, 105, 17, 18, 19, 20, 21, 22,
                23, 24, 25, 26, 27, 28, 6, 7, 8, 9, 10, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62,
                63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 203, 75, 204, 76, 77, 78, 79, 81, 82, 83,
                84, 97, 98, 99, 100,
            )
            for ((i, n) in low.withIndex()) sb.append("${33 + i}=a$n ")
            // 128..255 range (a89.. etc.)
            val high = intArrayOf(
                89, 90, 93, 94, 91, 92, 205, 85, 206, 86, 87, 88, 95, 96, 101, 102, 103, 104, 106, 107,
                108, 112, 111, 110, 109, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132,
                133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150,
                151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 163, 164, 196, 165, 192, 166, 167,
                168, 169, 170, 171, 172, 173, 162, 174, 175, 176, 177, 178, 179, 193, 180, 199, 181, 200,
                182, 201, 183, 184, 197, 185, 194, 198, 186, 195, 187, 188, 189, 190, 191,
            )
            // High half begins at code 161 (0xA1) in the ZapfDingbats vector.
            for ((i, n) in high.withIndex()) sb.append("${161 + i}=a$n ")
            encodingFromSpec(sb.toString())
        }

        /**
         * MacExpertEncoding. We do NOT ship the full expert vector (rarely used
         * old-style-figure / small-cap fonts); this is a deliberately minimal
         * table so the name is not silently ALIASED to StandardEncoding (which
         * produced wrong glyphs). Unlisted codes stay null → font cmap/notdef.
         * TODO: full MacExpertEncoding vector if an expert-set font shows up.
         */
        private val macExpertEncoding: Array<String?> by lazy {
            encodingFromSpec("32=space 33=exclamsmall 44=comma 46=period 47=fraction 48=zerooldstyle")
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
        ): WidthTable {
            val widths = IntArray(256)
            val present = BooleanArray(256)
            // Standard-14 metrics seed the table (a real, defined width) for the
            // built-in fonts; these count as "present" so we don't fall back.
            for (i in 0..255) {
                val gn = nameTable[i] ?: continue
                val w = Standard14Widths.widthOf(baseFont, gn) ?: continue
                widths[i] = w
                present[i] = true
            }
            val firstChar = dict.getInt("FirstChar")?.toInt() ?: -1
            val arr = dict.getArray("Widths", refs) // /Widths is often an indirect reference
            if (firstChar in 0..255 && arr != null) {
                for ((idx, w) in arr.withIndex()) {
                    val code = firstChar + idx
                    if (code !in 0..255) break
                    // A width present in /Widths is authoritative — even a 0 (which
                    // is a real advance for combining marks), so mark it present.
                    when (w) {
                        is PdfInt -> { widths[code] = w.value.toInt(); present[code] = true }
                        is PdfReal -> { widths[code] = w.value.toInt(); present[code] = true }
                        else -> { /* keep any standard-14 seed */ }
                    }
                }
            }
            // /MissingWidth defaults to 0 per ISO 32000-1 Table 122; but for a
            // standard-14 font with NO /Widths at all we keep a sane 500 so text
            // doesn't collapse (we can't know the real metric for unseeded codes).
            val hasWidthsArray = firstChar in 0..255 && arr != null
            val fallback = dict.getInt("MissingWidth")?.toInt()
                ?: if (hasWidthsArray) 0 else 500
            return WidthTable(widths, present, fallback)
        }

        private class WidthTable(
            val widths: IntArray,
            val present: BooleanArray,
            val missingWidth: Int,
        )

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
            hasSimpleWidth = BooleanArray(256),
            defaultWidth = 500,
            embeddedTtf = null, embeddedCff = null, embeddedType1 = null,
            composite = null,
        )
    }
}
