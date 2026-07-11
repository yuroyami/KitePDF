package io.github.yuroyami.kitepdf.render

import kotlin.math.max
import kotlin.math.min

/**
 * A pure-Kotlin JPEG 2000 decoder (ITU-T T.800) for `/JPXDecode` images: JP2
 * container boxes or a raw codestream, part 1 baseline. Produces an 8-bpc
 * Gray/RGB raster (plus an optional alpha plane from a `cdef` opacity
 * channel) that [ImageXObject] packs into a RAW image.
 *
 * Scope: SIZ/COD/QCD (+ COC/QCC overrides), multiple tiles and tile-parts,
 * LRCP/RLCP/RPCL/PCRL/CPRL progressions, general precincts, tag trees,
 * multi-layer tier-2 packet headers (with SOP/EPH), EBCOT tier-1 (baseline
 * code-block style), reversible 5/3 and irreversible 9/7 inverse DWT,
 * RCT/ICT multiple-component transforms, DC level shift, component
 * subsampling (nearest upsample) and bit depths up to 16. NOT handled
 * (returns null, the image falls back to the placeholder): RGN regions of
 * interest, POC progression changes, PPM/PPT packed headers, non-baseline
 * code-block styles (bypass/reset/termall/vsc/segsym).
 */
internal object JpxDecoder {

    class Result(
        val width: Int,
        val height: Int,
        val colorSpace: String,
        /** Interleaved 8-bpc samples: 1 (gray) or 3 (RGB) per pixel. */
        val pixelBytes: ByteArray,
        /** 8-bit opacity plane ([width]x[height]) from a cdef channel, or null. */
        val alpha: ByteArray?,
    )

    fun decode(data: ByteArray): Result? = runCatching { decodeOrThrow(data) }.getOrNull()

    /** True when [data] looks like a JP2 container or a raw J2K codestream. */
    fun isJpx(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (data[0].toInt() == 0xFF && (data[1].toInt() and 0xFF) == 0x4F) return true // SOC
        return data.size >= 12 && u32(data, 0) == 12L && u32(data, 4) == 0x6A502020L
    }

    private fun u32(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
            ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)

    // ---- JP2 container ------------------------------------------------------

    private class Jp2Info(
        val codestream: ByteArray,
        /** Channel definitions: component index -> channel type (0 colour, 1 opacity). */
        val channelTypes: Map<Int, Int>,
    )

    private fun parseContainer(data: ByteArray): Jp2Info {
        // Raw codestream: starts with the SOC marker.
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0x4F) {
            return Jp2Info(data, emptyMap())
        }
        val pos = 0
        var codestream: ByteArray? = null
        val channelTypes = HashMap<Int, Int>()

        fun walk(start: Int, end: Int) {
            var p = start
            while (p + 8 <= end) {
                var len = u32(data, p)
                val type = u32(data, p + 4)
                var body = p + 8
                if (len == 1L) { // 64-bit extended length
                    if (p + 16 > end) return
                    len = (u32(data, p + 8) shl 32) or u32(data, p + 12)
                    body = p + 16
                }
                val boxEnd = if (len == 0L) end else (p + len).toInt().coerceAtMost(end)
                if (boxEnd < body) return
                when (type) {
                    0x6A703263L -> if (codestream == null) codestream = data.copyOfRange(body, boxEnd) // jp2c
                    0x6A703268L -> walk(body, boxEnd) // jp2h superbox
                    0x63646566L -> { // cdef
                        if (body + 2 <= boxEnd) {
                            val n = ((data[body].toInt() and 0xFF) shl 8) or (data[body + 1].toInt() and 0xFF)
                            var q = body + 2
                            repeat(n) {
                                if (q + 6 <= boxEnd) {
                                    val cn = ((data[q].toInt() and 0xFF) shl 8) or (data[q + 1].toInt() and 0xFF)
                                    val typ = ((data[q + 2].toInt() and 0xFF) shl 8) or (data[q + 3].toInt() and 0xFF)
                                    channelTypes[cn] = typ
                                }
                                q += 6
                            }
                        }
                    }
                    else -> {}
                }
                if (len == 0L) break
                p = boxEnd
            }
        }
        walk(pos, data.size)
        val cs = codestream ?: throw IllegalStateException("no jp2c codestream box")
        return Jp2Info(cs, channelTypes)
    }

    // ---- codestream headers --------------------------------------------------

    private class R(val d: ByteArray, var pos: Int) {
        fun u8(): Int = d[pos++].toInt() and 0xFF
        fun u16(): Int { val v = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF); pos += 2; return v }
        fun u32i(): Int { val v = u32(d, pos); pos += 4; return v.toInt() }
    }

    private class Siz(
        val xsiz: Int, val ysiz: Int, val xosiz: Int, val yosiz: Int,
        val xtsiz: Int, val ytsiz: Int, val xtosiz: Int, val ytosiz: Int,
        val comps: Int, val prec: IntArray, val signed: BooleanArray,
        val dx: IntArray, val dy: IntArray,
    ) {
        val tilesW: Int get() = ceilDiv(xsiz - xtosiz, xtsiz)
        val tilesH: Int get() = ceilDiv(ysiz - ytosiz, ytsiz)
    }

    /** Coding style for one component (COD/COC). */
    private class Cod(
        val progression: Int, val layers: Int, val mct: Int,
        val decompositions: Int, val cbW: Int, val cbH: Int, val cbStyle: Int,
        val reversible: Boolean,
        /** Per-resolution precinct exponents (PPx, PPy); size decompositions+1. */
        val ppx: IntArray, val ppy: IntArray,
        val sop: Boolean, val eph: Boolean,
    )

    /** Quantization for one component (QCD/QCC). */
    private class Quant(val style: Int, val guardBits: Int, val exps: IntArray, val mants: IntArray)

    // ---- geometry helpers -----------------------------------------------------

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
    private fun ceilShift(a: Int, s: Int): Int = (a + (1 shl s) - 1) shr s

    // ---- tag tree -------------------------------------------------------------

    private class TagTree(val w: Int, val h: Int) {
        private val levels: Int
        private val low: IntArray
        private val known: BooleanArray
        private val offs: IntArray
        private val widths: IntArray

        init {
            var lw = w; var lh = h
            val ws = ArrayList<Int>(); val hs = ArrayList<Int>()
            while (true) {
                ws.add(lw); hs.add(lh)
                if (lw == 1 && lh == 1) break
                lw = ceilDiv(lw, 2); lh = ceilDiv(lh, 2)
            }
            levels = ws.size
            widths = ws.toIntArray()
            offs = IntArray(levels)
            var total = 0
            for (l in 0 until levels) { offs[l] = total; total += widths[l] * hs[l] }
            low = IntArray(total)
            known = BooleanArray(total)
        }

        private fun idx(l: Int, x: Int, y: Int) = offs[l] + y * widths[l] + x

        /**
         * The standard tag-tree query (B.10.2): decode bits until it is known
         * whether value(x,y) < [threshold]; true when it is. Node values are
         * lower bounds refined by 0-bits and pinned by a 1-bit.
         */
        fun decode(bio: Bio, x: Int, y: Int, threshold: Int): Boolean {
            var bound = 0
            for (l in levels - 1 downTo 0) {
                val i = idx(l, x shr l, y shr l)
                if (low[i] < bound) low[i] = bound
                while (!known[i] && low[i] < threshold) {
                    if (bio.bit() == 1) known[i] = true else low[i]++
                }
                if (!known[i]) return false // value >= threshold
                bound = low[i]
            }
            return true // leaf value = low[leaf] < threshold
        }

        /** Decode until the leaf's exact value is known (zero-bitplane trees). */
        fun decodeValue(bio: Bio, x: Int, y: Int): Int {
            var t = 1
            while (!decode(bio, x, y, t)) t++
            return low[idx(0, x, y)]
        }
    }

    // ---- packet-header bit reader (B.10.1 bit-stuffing) -----------------------

    private class Bio(val d: ByteArray, var pos: Int, val end: Int) {
        private var buf = 0
        private var ct = 0
        private var lastFF = false

        fun bit(): Int {
            if (ct == 0) {
                if (pos >= end) { buf = 0; ct = if (lastFF) 7 else 8; lastFF = false }
                else {
                    buf = d[pos++].toInt() and 0xFF
                    ct = if (lastFF) 7 else 8
                    lastFF = buf == 0xFF
                }
            }
            ct--
            return (buf shr ct) and 1
        }

        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }

        /** Byte-align at the end of a packet header (consume the stuffed bit). */
        fun align() {
            ct = 0
            if (lastFF) { if (pos < end) pos++; lastFF = false }
        }
    }

    // ---- code-block / precinct / band model -----------------------------------

    private class CodeBlock(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        var included = false
        var zeroBitplanes = 0
        var lBlock = 3
        var passes = 0
        val data = ArrayList<ByteArray>()
        var newPasses = 0
    }

    private class Precinct(val cbW: Int, val cbH: Int, val blocks: List<CodeBlock?>) {
        val inclTree = TagTree(max(1, cbW), max(1, cbH))
        val zeroTree = TagTree(max(1, cbW), max(1, cbH))
    }

    private class Band(
        val orient: Int, // 0=LL 1=HL 2=LH 3=HH
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val precincts: List<Precinct>,
        val stepExp: Int, val stepMant: Int, val guardBits: Int,
    ) {
        val coeffs = IntArray(max(0, (x1 - x0)) * max(0, (y1 - y0)))
    }

    private class Resolution(
        val r: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val numPw: Int, val numPh: Int,
        val bands: List<Band>,
    )

    private class TileComp(
        val comp: Int,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val cod: Cod,
        val resolutions: List<Resolution>,
    )

    // ---- main decode -----------------------------------------------------------

    private fun decodeOrThrow(data: ByteArray): Result? {
        val jp2 = parseContainer(data)
        val cs = jp2.codestream
        val r = R(cs, 0)
        if (r.u16() != 0xFF4F) return null // SOC

        var siz: Siz? = null
        var mainCod: Cod? = null
        val mainCoc = HashMap<Int, Cod>()
        var mainQcd: Quant? = null
        val mainQcc = HashMap<Int, Quant>()
        // Tile bodies: tile index -> concatenated bitstream of its tile-parts.
        val tileBodies = HashMap<Int, ArrayList<ByteArray>>()
        val tileCod = HashMap<Int, Cod>()
        val tileCoc = HashMap<Int, HashMap<Int, Cod>>()
        val tileQcd = HashMap<Int, Quant>()
        val tileQcc = HashMap<Int, HashMap<Int, Quant>>()

        var inTile = -1
        var tileEnd = 0

        while (r.pos + 2 <= cs.size) {
            val marker = r.u16()
            if (marker == 0xFFD9) break // EOC
            when (marker) {
                0xFF51 -> { // SIZ
                    val len = r.u16(); val start = r.pos
                    r.u16() // Rsiz (capabilities; be lenient)
                    val xsiz = r.u32i(); val ysiz = r.u32i()
                    val xo = r.u32i(); val yo = r.u32i()
                    val xt = r.u32i(); val yt = r.u32i()
                    val xto = r.u32i(); val yto = r.u32i()
                    val nc = r.u16()
                    if (nc <= 0 || nc > 16) return null
                    val prec = IntArray(nc); val signed = BooleanArray(nc)
                    val dx = IntArray(nc); val dy = IntArray(nc)
                    for (c in 0 until nc) {
                        val s = r.u8()
                        prec[c] = (s and 0x7F) + 1
                        signed[c] = (s and 0x80) != 0
                        dx[c] = r.u8(); dy[c] = r.u8()
                        if (prec[c] > 16 || dx[c] <= 0 || dy[c] <= 0) return null
                    }
                    siz = Siz(xsiz, ysiz, xo, yo, xt, yt, xto, yto, nc, prec, signed, dx, dy)
                    r.pos = start + len - 2
                }
                0xFF52 -> { // COD
                    val len = r.u16(); val start = r.pos
                    val cod = readCod(r) ?: return null
                    if (inTile >= 0) tileCod[inTile] = cod else mainCod = cod
                    r.pos = start + len - 2
                }
                0xFF53 -> { // COC
                    val len = r.u16(); val start = r.pos
                    val nComps = siz?.comps ?: return null
                    val c = if (nComps < 257) r.u8() else r.u16()
                    val base = (if (inTile >= 0) tileCod[inTile] else null) ?: mainCod ?: return null
                    val coc = readCoc(r, base) ?: return null
                    if (inTile >= 0) tileCoc.getOrPut(inTile) { HashMap() }[c] = coc else mainCoc[c] = coc
                    r.pos = start + len - 2
                }
                0xFF5C -> { // QCD
                    val len = r.u16(); val start = r.pos
                    val q = readQuant(r, start + len - 2) ?: return null
                    if (inTile >= 0) tileQcd[inTile] = q else mainQcd = q
                    r.pos = start + len - 2
                }
                0xFF5D -> { // QCC
                    val len = r.u16(); val start = r.pos
                    val nComps = siz?.comps ?: return null
                    val c = if (nComps < 257) r.u8() else r.u16()
                    val q = readQuant(r, start + len - 2) ?: return null
                    if (inTile >= 0) tileQcc.getOrPut(inTile) { HashMap() }[c] = q else mainQcc[c] = q
                    r.pos = start + len - 2
                }
                0xFF90 -> { // SOT
                    r.u16() // Lsot
                    val isot = r.u16()
                    val psot = r.u32i()
                    r.u8() // TPsot
                    r.u8() // TNsot
                    inTile = isot
                    tileEnd = if (psot == 0) cs.size else (r.pos - 12 + psot)
                }
                0xFF93 -> { // SOD: tile-part body runs to tileEnd
                    val bodyEnd = tileEnd.coerceAtMost(cs.size)
                    tileBodies.getOrPut(inTile) { ArrayList() }.add(cs.copyOfRange(r.pos, bodyEnd))
                    r.pos = bodyEnd
                    inTile = -1
                }
                0xFF64, 0xFF55, 0xFF57, 0xFF58, 0xFF63 -> {
                    // COM, TLM, PLM, PLT, CRG: informational, skip by length.
                    val len = r.u16(); r.pos += len - 2
                }
                0xFF5E -> return null // RGN: region of interest, unsupported
                0xFF5F -> return null // POC: progression order changes, unsupported
                0xFF60, 0xFF61 -> return null // PPM/PPT packed packet headers, unsupported
                else -> {
                    if (marker < 0xFF30 || marker > 0xFF3F) {
                        // Unknown segment with a length field; try to skip it.
                        if (r.pos + 2 > cs.size) return null
                        val len = r.u16(); r.pos += len - 2
                    }
                }
            }
            if (r.pos < 0 || r.pos > cs.size) return null
        }

        val s = siz ?: return null
        val cod0 = mainCod ?: return null
        val qcd0 = mainQcd ?: return null

        val imgW = s.xsiz - s.xosiz
        val imgH = s.ysiz - s.yosiz
        if (imgW <= 0 || imgH <= 0 || imgW.toLong() * imgH > 64L shl 20) return null

        // Component output planes at full component resolution.
        val planeW = IntArray(s.comps) { ceilDiv(s.xsiz, s.dx[it]) - ceilDiv(s.xosiz, s.dx[it]) }
        val planeH = IntArray(s.comps) { ceilDiv(s.ysiz, s.dy[it]) - ceilDiv(s.yosiz, s.dy[it]) }
        val planes = Array(s.comps) { IntArray(planeW[it] * planeH[it]) }

        for (t in 0 until s.tilesW * s.tilesH) {
            val body = tileBodies[t]?.let { parts ->
                if (parts.size == 1) parts[0]
                else ByteArray(parts.sumOf { it.size }).also { out ->
                    var o = 0
                    for (p in parts) { p.copyInto(out, o); o += p.size }
                }
            } ?: continue

            val cod = tileCod[t] ?: cod0
            val qcd = tileQcd[t] ?: qcd0
            // Precedence (A.6.1): tile COC > tile COD > main COC > main COD.
            fun codFor(c: Int): Cod = tileCoc[t]?.get(c) ?: tileCod[t] ?: mainCoc[c] ?: cod0
            fun quantFor(c: Int): Quant = tileQcc[t]?.get(c) ?: tileQcd[t] ?: mainQcc[c] ?: qcd0

            decodeTile(s, t, body, ::codFor, ::quantFor, cod, planes, planeW, planeH)
        }

        // Assemble output: gray or RGB, plus optional cdef opacity channel.
        return assemble(s, jp2, cod0.mct == 1, planes, planeW, planeH, imgW, imgH)
    }

    private fun readCod(r: R): Cod? {
        val scod = r.u8()
        val prog = r.u8()
        val layers = r.u16()
        val mct = r.u8()
        val decomp = r.u8()
        if (decomp > 32 || layers <= 0 || layers > 1000) return null
        val cbW = (r.u8() and 0x0F) + 2
        val cbH = (r.u8() and 0x0F) + 2
        val cbStyle = r.u8()
        val transform = r.u8()
        if (cbW + cbH > 12) return null
        val ppx = IntArray(decomp + 1) { 15 }
        val ppy = IntArray(decomp + 1) { 15 }
        if (scod and 1 != 0) {
            for (i in 0..decomp) {
                val p = r.u8()
                ppx[i] = p and 0x0F
                ppy[i] = (p shr 4) and 0x0F
            }
        }
        if (cbStyle != 0) return null // non-baseline code-block styles unsupported
        return Cod(
            prog, layers, mct, decomp, cbW, cbH, cbStyle, reversible = transform == 1,
            ppx = ppx, ppy = ppy, sop = scod and 2 != 0, eph = scod and 4 != 0,
        )
    }

    private fun readCoc(r: R, base: Cod): Cod? {
        val scoc = r.u8()
        val decomp = r.u8()
        if (decomp > 32) return null
        val cbW = (r.u8() and 0x0F) + 2
        val cbH = (r.u8() and 0x0F) + 2
        val cbStyle = r.u8()
        val transform = r.u8()
        if (cbStyle != 0 || cbW + cbH > 12) return null
        val ppx = IntArray(decomp + 1) { 15 }
        val ppy = IntArray(decomp + 1) { 15 }
        if (scoc and 1 != 0) {
            for (i in 0..decomp) {
                val p = r.u8()
                ppx[i] = p and 0x0F
                ppy[i] = (p shr 4) and 0x0F
            }
        }
        return Cod(
            base.progression, base.layers, base.mct, decomp, cbW, cbH, cbStyle,
            reversible = transform == 1, ppx = ppx, ppy = ppy, sop = base.sop, eph = base.eph,
        )
    }

    private fun readQuant(r: R, end: Int): Quant? {
        val sq = r.u8()
        val style = sq and 0x1F
        val guard = (sq shr 5) and 7
        val exps = ArrayList<Int>()
        val mants = ArrayList<Int>()
        when (style) {
            0 -> while (r.pos < end) { val v = r.u8(); exps.add(v shr 3); mants.add(0) }
            1 -> { val v = r.u16(); exps.add(v shr 11); mants.add(v and 0x7FF) } // scalar derived
            2 -> while (r.pos + 1 < end) { val v = r.u16(); exps.add(v shr 11); mants.add(v and 0x7FF) }
            else -> return null
        }
        return Quant(style, guard, exps.toIntArray(), mants.toIntArray())
    }

    // ---- tile decode -----------------------------------------------------------

    private fun decodeTile(
        s: Siz, t: Int, body: ByteArray,
        codFor: (Int) -> Cod, quantFor: (Int) -> Quant, tileCod: Cod,
        planes: Array<IntArray>, planeW: IntArray, planeH: IntArray,
    ) {
        val ti = t % s.tilesW
        val tj = t / s.tilesW
        val tx0 = max(s.xtosiz + ti * s.xtsiz, s.xosiz)
        val ty0 = max(s.ytosiz + tj * s.ytsiz, s.yosiz)
        val tx1 = min(s.xtosiz + (ti + 1) * s.xtsiz, s.xsiz)
        val ty1 = min(s.ytosiz + (tj + 1) * s.ytsiz, s.ysiz)
        if (tx1 <= tx0 || ty1 <= ty0) return

        // Build the component/resolution/band/precinct/code-block model.
        val comps = ArrayList<TileComp>(s.comps)
        for (c in 0 until s.comps) {
            val cod = codFor(c)
            val q = quantFor(c)
            val cx0 = ceilDiv(tx0, s.dx[c]); val cy0 = ceilDiv(ty0, s.dy[c])
            val cx1 = ceilDiv(tx1, s.dx[c]); val cy1 = ceilDiv(ty1, s.dy[c])
            val res = ArrayList<Resolution>(cod.decompositions + 1)
            for (rr in 0..cod.decompositions) {
                val lev = cod.decompositions - rr
                val rx0 = ceilShift(cx0, lev); val ry0 = ceilShift(cy0, lev)
                val rx1 = ceilShift(cx1, lev); val ry1 = ceilShift(cy1, lev)
                val ppx = cod.ppx[rr]; val ppy = cod.ppy[rr]
                val numPw = if (rx1 > rx0) ceilDiv(rx1, 1 shl ppx) - (rx0 shr ppx) else 0
                val numPh = if (ry1 > ry0) ceilDiv(ry1, 1 shl ppy) - (ry0 shr ppy) else 0
                val bands = ArrayList<Band>()
                // Code-block partition exponents within a precinct.
                val cbw = min(cod.cbW, if (rr == 0) ppx else ppx - 1)
                val cbh = min(cod.cbH, if (rr == 0) ppy else ppy - 1)

                fun bandFor(orient: Int, qIndex: Int): Band {
                    val xo = orient and 1
                    val yo = (orient shr 1) and 1
                    val bx0: Int; val by0: Int; val bx1: Int; val by1: Int
                    if (rr == 0) {
                        bx0 = rx0; by0 = ry0; bx1 = rx1; by1 = ry1
                    } else {
                        val l2 = lev + 1
                        bx0 = ceilDiv(cx0 - (1 shl (l2 - 1)) * xo, 1 shl l2)
                        by0 = ceilDiv(cy0 - (1 shl (l2 - 1)) * yo, 1 shl l2)
                        bx1 = ceilDiv(cx1 - (1 shl (l2 - 1)) * xo, 1 shl l2)
                        by1 = ceilDiv(cy1 - (1 shl (l2 - 1)) * yo, 1 shl l2)
                    }
                    val (se, sm) = stepFor(q, qIndex, lev, cod)
                    // Precincts of this band: derived from the resolution grid.
                    val precincts = ArrayList<Precinct>(max(0, numPw * numPh))
                    val shift = if (rr == 0) 0 else 1
                    for (pj in 0 until numPh) for (pi in 0 until numPw) {
                        // Precinct rect on the resolution grid...
                        val prx0 = max(rx0, ((rx0 shr ppx) + pi) shl ppx)
                        val pry0 = max(ry0, ((ry0 shr ppy) + pj) shl ppy)
                        val prx1 = min(rx1, ((rx0 shr ppx) + pi + 1) shl ppx)
                        val pry1 = min(ry1, ((ry0 shr ppy) + pj + 1) shl ppy)
                        // ...mapped into band coordinates.
                        val pbx0 = if (rr == 0) prx0 else ceilDiv(prx0, 2)
                        val pby0 = if (rr == 0) pry0 else ceilDiv(pry0, 2)
                        val pbx1 = if (rr == 0) prx1 else ceilDiv(prx1, 2)
                        val pby1 = if (rr == 0) pry1 else ceilDiv(pry1, 2)
                        val ibx0 = max(pbx0, bx0); val iby0 = max(pby0, by0)
                        val ibx1 = min(pbx1, bx1); val iby1 = min(pby1, by1)
                        if (ibx1 <= ibx0 || iby1 <= iby0) {
                            precincts.add(Precinct(0, 0, emptyList()))
                            continue
                        }
                        val gw = ceilDiv(ibx1, 1 shl cbw) - (ibx0 shr cbw)
                        val gh = ceilDiv(iby1, 1 shl cbh) - (iby0 shr cbh)
                        val blocks = ArrayList<CodeBlock?>(gw * gh)
                        for (gy in 0 until gh) for (gx in 0 until gw) {
                            val bx = max(ibx0, ((ibx0 shr cbw) + gx) shl cbw)
                            val by = max(iby0, ((iby0 shr cbh) + gy) shl cbh)
                            val ex = min(ibx1, ((ibx0 shr cbw) + gx + 1) shl cbw)
                            val ey = min(iby1, ((iby0 shr cbh) + gy + 1) shl cbh)
                            blocks.add(if (ex > bx && ey > by) CodeBlock(bx, by, ex, ey) else null)
                        }
                        precincts.add(Precinct(gw, gh, blocks))
                    }
                    return Band(orient, bx0, by0, bx1, by1, precincts, se, sm, q.guardBits)
                }

                if (rr == 0) {
                    bands.add(bandFor(0, 0))
                } else {
                    bands.add(bandFor(1, 3 * (rr - 1) + 1)) // HL
                    bands.add(bandFor(2, 3 * (rr - 1) + 2)) // LH
                    bands.add(bandFor(3, 3 * (rr - 1) + 3)) // HH
                }
                res.add(Resolution(rr, rx0, ry0, rx1, ry1, numPw, numPh, bands))
            }
            comps.add(TileComp(c, cx0, cy0, cx1, cy1, cod, res))
        }

        // Tier-2: walk packets in progression order, filling code-block data.
        readPackets(body, comps, tileCod)

        // Tier-1 + dequant + IDWT per component; write RAW signed samples.
        for (tc in comps) {
            decodeTileComp(s, tc, planes[tc.comp], planeW[tc.comp], planeH[tc.comp], ceilDiv(s.xosiz, s.dx[tc.comp]), ceilDiv(s.yosiz, s.dy[tc.comp]))
        }

        // Multiple-component transform on the tile area, then shift + clamp.
        if (tileCod.mct == 1 && s.comps >= 3) {
            applyInverseMct(s, comps, tileCod.reversible, planes, planeW, planeH)
        }
        for (tc in comps) {
            finalizeTileComp(s, tc, planes[tc.comp], planeW[tc.comp], planeH[tc.comp], ceilDiv(s.xosiz, s.dx[tc.comp]), ceilDiv(s.yosiz, s.dy[tc.comp]))
        }
    }

    private fun stepFor(q: Quant, qIndex: Int, lev: Int, cod: Cod): Pair<Int, Int> = when (q.style) {
        0 -> Pair(q.exps.getOrElse(qIndex) { q.exps.lastOrNull() ?: 8 }, 0)
        1 -> Pair((q.exps[0] - lev).coerceAtLeast(0), q.mants[0]) // scalar derived from LL
        else -> Pair(
            q.exps.getOrElse(qIndex) { q.exps.lastOrNull() ?: 8 },
            q.mants.getOrElse(qIndex) { q.mants.lastOrNull() ?: 0 },
        )
    }

    // ---- tier-2 packet reading ---------------------------------------------------

    private fun readPackets(body: ByteArray, comps: List<TileComp>, cod: Cod) {
        val bio = PacketReader(body, cod.sop, cod.eph)
        val maxRes = comps.maxOf { it.resolutions.size }
        val layers = cod.layers

        fun packet(l: Int, rr: Int, c: Int, p: Int) {
            val tc = comps.getOrNull(c) ?: return
            val res = tc.resolutions.getOrNull(rr) ?: return
            if (p >= res.numPw * res.numPh) return
            bio.readPacket(res, p, l)
        }

        when (cod.progression) {
            0 -> for (l in 0 until layers) for (rr in 0 until maxRes) for (c in comps.indices) {
                val res = comps[c].resolutions.getOrNull(rr) ?: continue
                for (p in 0 until res.numPw * res.numPh) packet(l, rr, c, p)
            }
            1 -> for (rr in 0 until maxRes) for (l in 0 until layers) for (c in comps.indices) {
                val res = comps[c].resolutions.getOrNull(rr) ?: continue
                for (p in 0 until res.numPw * res.numPh) packet(l, rr, c, p)
            }
            2 -> for (rr in 0 until maxRes) {
                val maxP = comps.maxOf { it.resolutions.getOrNull(rr)?.let { rz -> rz.numPw * rz.numPh } ?: 0 }
                for (p in 0 until maxP) for (c in comps.indices) for (l in 0 until layers) packet(l, rr, c, p)
            }
            3 -> { // PCRL
                val maxP = comps.maxOf { c -> c.resolutions.maxOfOrNull { it.numPw * it.numPh } ?: 0 }
                for (p in 0 until maxP) for (c in comps.indices) for (rr in 0 until maxRes) for (l in 0 until layers) packet(l, rr, c, p)
            }
            4 -> { // CPRL
                val maxP = comps.maxOf { c -> c.resolutions.maxOfOrNull { it.numPw * it.numPh } ?: 0 }
                for (c in comps.indices) for (p in 0 until maxP) for (rr in 0 until maxRes) for (l in 0 until layers) packet(l, rr, c, p)
            }
            else -> {}
        }
    }

    /** Reads packet headers + bodies sequentially from a tile bitstream. */
    private class PacketReader(val d: ByteArray, val sop: Boolean, val eph: Boolean) {
        var pos = 0

        fun readPacket(res: Resolution, p: Int, layer: Int) {
            if (pos >= d.size) return
            if (sop && pos + 6 <= d.size &&
                (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xFF) == 0x91
            ) {
                pos += 6
            }
            val bio = Bio(d, pos, d.size)
            val included = ArrayList<CodeBlock>()
            val newPassCounts = ArrayList<Int>()
            val segLens = ArrayList<Int>()

            if (bio.bit() == 0) {
                // Empty packet.
                bio.align()
                pos = bio.pos
                if (eph && pos + 2 <= d.size && (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xFF) == 0x92) pos += 2
                return
            }

            for (band in res.bands) {
                val precinct = band.precincts.getOrNull(p) ?: continue
                for (i in precinct.blocks.indices) {
                    val cb = precinct.blocks[i] ?: continue
                    val gx = i % max(1, precinct.cbW)
                    val gy = i / max(1, precinct.cbW)
                    var incl: Boolean
                    if (!cb.included) {
                        incl = precinct.inclTree.decode(bio, gx, gy, layer + 1)
                        if (incl) {
                            cb.zeroBitplanes = precinct.zeroTree.decodeValue(bio, gx, gy)
                            cb.lBlock = 3
                        }
                    } else {
                        incl = bio.bit() == 1
                    }
                    if (!incl) continue
                    cb.included = true
                    // Number of new coding passes (B.10.6).
                    val np = when {
                        bio.bit() == 0 -> 1
                        bio.bit() == 0 -> 2
                        else -> {
                            val v = bio.bits(2)
                            if (v < 3) 3 + v
                            else {
                                val v2 = bio.bits(5)
                                if (v2 < 31) 6 + v2 else 37 + bio.bits(7)
                            }
                        }
                    }
                    // Lblock update (unary), then the single segment length.
                    while (bio.bit() == 1) cb.lBlock++
                    var bits = cb.lBlock
                    var passes = np
                    while (passes > 1) { bits++; passes = passes shr 1 }
                    val segLen = bio.bits(bits)
                    included.add(cb)
                    newPassCounts.add(np)
                    segLens.add(segLen)
                }
            }
            bio.align()
            pos = bio.pos
            if (eph && pos + 2 <= d.size && (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xFF) == 0x92) pos += 2

            for (k in included.indices) {
                val cb = included[k]
                val len = segLens[k]
                val end = (pos + len).coerceAtMost(d.size)
                cb.data.add(d.copyOfRange(pos, end))
                cb.passes += newPassCounts[k]
                pos = end
            }
        }
    }

    // ---- tier-1 EBCOT ---------------------------------------------------------

    private const val CTX_UNI = 18
    private const val CTX_RLC = 17

    /**
     * ZC context lookup, indexed [orientGroup][h*12 + v*4 + min(d,3)]: h and v
     * clamp to 2, but d must reach 3 because the HH table distinguishes d >= 3
     * (context 8) from d == 2.
     */
    private val ZC_LL = buildZcTable(0)
    private val ZC_HL = buildZcTable(1)
    private val ZC_HH = buildZcTable(2)

    private fun buildZcTable(group: Int): IntArray {
        val t = IntArray(36)
        for (h in 0..2) for (v in 0..2) for (dd in 0..3) {
            val ctx = when (group) {
                0 -> when { // LL and LH
                    h == 2 -> 8
                    h == 1 && v >= 1 -> 7
                    h == 1 && v == 0 && dd >= 1 -> 6
                    h == 1 -> 5
                    v == 2 -> 4
                    v == 1 -> 3
                    dd >= 2 -> 2
                    dd == 1 -> 1
                    else -> 0
                }
                1 -> when { // HL: transpose of LL
                    v == 2 -> 8
                    v == 1 && h >= 1 -> 7
                    v == 1 && h == 0 && dd >= 1 -> 6
                    v == 1 -> 5
                    h == 2 -> 4
                    h == 1 -> 3
                    dd >= 2 -> 2
                    dd == 1 -> 1
                    else -> 0
                }
                else -> when { // HH
                    dd >= 2 && h + v >= 1 -> if (dd >= 3) 8 else 7
                    dd >= 3 -> 8
                    dd == 2 -> 6
                    dd == 1 && h + v >= 2 -> 5
                    dd == 1 && h + v == 1 -> 4
                    dd == 1 -> 3
                    h + v >= 2 -> 2
                    h + v == 1 -> 1
                    else -> 0
                }
            }
            t[h * 12 + v * 4 + dd] = ctx
        }
        return t
    }

    private class T1(val w: Int, val h: Int) {
        val sig = IntArray(w * h)      // 1 = significant
        val visited = IntArray(w * h)  // pass-local flag
        val refined = IntArray(w * h)  // has had a refinement pass
        val sign = IntArray(w * h)     // 1 = negative
        val mag = IntArray(w * h)
        /** Bitplane of the last pass that touched the coefficient (for the r=0.5 bias). */
        val lastPlane = IntArray(w * h)
    }

    private fun decodeCodeBlock(cb: CodeBlock, band: Band, cod: Cod, mb: Int) {
        if (cb.data.isEmpty() || cb.passes <= 0) return
        val w = cb.x1 - cb.x0
        val h = cb.y1 - cb.y0
        if (w <= 0 || h <= 0) return
        val total = ByteArray(cb.data.sumOf { it.size })
        var o = 0
        for (seg in cb.data) { seg.copyInto(total, o); o += seg.size }

        val mq = MqDecoder(total, 0, total.size)
        val cx = IntArray(19)
        cx[0] = 4 shl 1
        cx[CTX_RLC] = 3 shl 1
        cx[CTX_UNI] = 46 shl 1

        val t1 = T1(w, h)
        val zc = when (band.orient) {
            1 -> ZC_HL
            3 -> ZC_HH
            else -> ZC_LL
        }

        var bp = mb - 1 - cb.zeroBitplanes
        var passNo = 0
        var passType = 2 // start with cleanup
        var passes = cb.passes
        while (passes > 0 && bp >= 0) {
            when (passType) {
                0 -> sigPropPass(t1, mq, cx, zc, bp)
                1 -> magRefPass(t1, mq, cx, bp)
                2 -> cleanupPass(t1, mq, cx, zc, bp)
            }
            passes--
            passNo++
            if (passType == 2) { passType = 0; bp-- } else passType++
        }
        // Reconstruction bias for the lowest decoded plane (E.1.1's r = 0.5).
        // Reversible full decodes must stay exact-integer; the irreversible
        // path stores DOUBLED magnitudes carrying the half step, and the
        // dequantizer divides the scale by two.
        val lowest = bp + 1
        if (cod.reversible) {
            if (lowest > 0) {
                for (i in t1.mag.indices) {
                    if (t1.mag[i] != 0) t1.mag[i] = t1.mag[i] or (1 shl (lowest - 1))
                }
            }
        } else {
            for (i in t1.mag.indices) {
                if (t1.mag[i] != 0) t1.mag[i] = (t1.mag[i] shl 1) or (1 shl t1.lastPlane[i])
            }
        }

        // Store signed magnitudes into the band's coefficient array.
        val bw = band.x1 - band.x0
        for (y in 0 until h) for (x in 0 until w) {
            val m = t1.mag[y * w + x]
            if (m == 0) continue
            val v = if (t1.sign[y * w + x] == 1) -m else m
            band.coeffs[(cb.y0 - band.y0 + y) * bw + (cb.x0 - band.x0 + x)] = v
        }
    }

    private fun T1.neighborSums(x: Int, y: Int): Triple<Int, Int, Int> {
        fun s(px: Int, py: Int) = if (px in 0 until w && py in 0 until h) sig[py * w + px] else 0
        val hh = s(x - 1, y) + s(x + 1, y)
        val vv = s(x, y - 1) + s(x, y + 1)
        val dd = s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)
        return Triple(min(hh, 2), min(vv, 2), min(dd, 3))
    }

    /** Sign context (ctx 9..13) + XOR bit from the H/V neighbour signs. */
    private fun T1.signContext(x: Int, y: Int): Pair<Int, Int> {
        fun c(px: Int, py: Int): Int {
            if (px !in 0 until w || py !in 0 until h) return 0
            if (sig[py * w + px] == 0) return 0
            return if (sign[py * w + px] == 1) -1 else 1
        }
        val hc = (c(x - 1, y) + c(x + 1, y)).coerceIn(-1, 1)
        val vc = (c(x, y - 1) + c(x, y + 1)).coerceIn(-1, 1)
        return when {
            hc == 1 && vc == 1 -> 13 to 0
            hc == 1 && vc == 0 -> 12 to 0
            hc == 1 && vc == -1 -> 11 to 0
            hc == 0 && vc == 1 -> 10 to 0
            hc == 0 && vc == 0 -> 9 to 0
            hc == 0 && vc == -1 -> 10 to 1
            hc == -1 && vc == 1 -> 11 to 1
            hc == -1 && vc == 0 -> 12 to 1
            else -> 13 to 1
        }
    }

    private fun sigPropPass(t1: T1, mq: MqDecoder, cx: IntArray, zc: IntArray, bp: Int) {
        val w = t1.w; val h = t1.h
        var y0 = 0
        while (y0 < h) {
            for (x in 0 until w) {
                for (y in y0 until min(y0 + 4, h)) {
                    val i = y * w + x
                    t1.visited[i] = 0
                    if (t1.sig[i] != 0) continue
                    val (hh, vv, dd) = t1.neighborSums(x, y)
                    val ctx = zc[hh * 12 + vv * 4 + dd]
                    if (ctx == 0) continue
                    t1.visited[i] = 1
                    if (mq.bit(cx, ctx) == 1) {
                        val (sctx, xor) = t1.signContext(x, y)
                        val sbit = mq.bit(cx, sctx) xor xor
                        t1.sig[i] = 1
                        t1.sign[i] = sbit
                        t1.mag[i] = 1 shl bp
                        t1.lastPlane[i] = bp
                    }
                }
            }
            y0 += 4
        }
    }

    private fun magRefPass(t1: T1, mq: MqDecoder, cx: IntArray, bp: Int) {
        val w = t1.w; val h = t1.h
        var y0 = 0
        while (y0 < h) {
            for (x in 0 until w) {
                for (y in y0 until min(y0 + 4, h)) {
                    val i = y * w + x
                    // Refine only coefficients significant BEFORE this plane's SPP
                    // (the visited flag marks everything SPP coded this plane).
                    if (t1.sig[i] == 0 || t1.visited[i] == 1) continue
                    val ctx = when {
                        t1.refined[i] != 0 -> 16
                        else -> {
                            val (hh, vv, dd) = t1.neighborSums(x, y)
                            if (hh + vv + dd > 0) 15 else 14
                        }
                    }
                    val bit = mq.bit(cx, ctx)
                    t1.refined[i] = 1
                    t1.lastPlane[i] = bp
                    if (bit == 1) t1.mag[i] = t1.mag[i] or (1 shl bp)
                }
            }
            y0 += 4
        }
    }

    private fun cleanupPass(t1: T1, mq: MqDecoder, cx: IntArray, zc: IntArray, bp: Int) {
        val w = t1.w; val h = t1.h
        var y0 = 0
        while (y0 < h) {
            for (x in 0 until w) {
                var y = y0
                val stripeH = min(4, h - y0)
                // Run-length mode: full stripe of 4, nothing significant or visited,
                // and every ZC context zero.
                var runMode = false
                if (stripeH == 4) {
                    runMode = true
                    for (yy in y0 until y0 + 4) {
                        val i = yy * w + x
                        if (t1.sig[i] != 0 || t1.visited[i] != 0) { runMode = false; break }
                        val (hh, vv, dd) = t1.neighborSums(x, yy)
                        if (zc[hh * 12 + vv * 4 + dd] != 0) { runMode = false; break }
                    }
                }
                if (runMode) {
                    if (mq.bit(cx, CTX_RLC) == 0) continue // whole column stays insignificant
                    val pos = (mq.bit(cx, CTX_UNI) shl 1) or mq.bit(cx, CTX_UNI)
                    y = y0 + pos
                    // The first significant coefficient: sign only (the 1 is implied).
                    val i = y * w + x
                    val (sctx, xor) = t1.signContext(x, y)
                    val sbit = mq.bit(cx, sctx) xor xor
                    t1.sig[i] = 1
                    t1.sign[i] = sbit
                    t1.mag[i] = 1 shl bp
                    t1.lastPlane[i] = bp
                    y++
                }
                while (y < y0 + stripeH) {
                    val i = y * w + x
                    if (t1.sig[i] == 0 && t1.visited[i] == 0) {
                        val (hh, vv, dd) = t1.neighborSums(x, y)
                        val ctx = zc[hh * 12 + vv * 4 + dd]
                        if (mq.bit(cx, ctx) == 1) {
                            val (sctx, xor) = t1.signContext(x, y)
                            val sbit = mq.bit(cx, sctx) xor xor
                            t1.sig[i] = 1
                            t1.sign[i] = sbit
                            t1.mag[i] = 1 shl bp
                            t1.lastPlane[i] = bp
                        }
                    }
                    t1.visited[i] = 0
                    y++
                }
            }
            y0 += 4
        }
        // Clear visited for the next bitplane's SPP.
        for (i in t1.visited.indices) t1.visited[i] = 0
    }

    // ---- component reconstruction ----------------------------------------------

    private fun decodeTileComp(
        s: Siz, tc: TileComp, plane: IntArray, pw: Int, ph: Int, px0: Int, py0: Int,
    ) {
        val cod = tc.cod
        // Tier-1 on every code-block.
        for (res in tc.resolutions) {
            for (band in res.bands) {
                val gain = when (band.orient) { 0 -> 0; 3 -> 2; else -> 1 }
                val prec = s.prec[tc.comp]
                val mb = band.guardBits + band.stepExp - 1
                for (precinct in band.precincts) {
                    for (cb in precinct.blocks) {
                        if (cb != null) decodeCodeBlock(cb, band, cod, mb)
                    }
                }
                // Dequantization for the irreversible path (reversible has unit step).
                if (!cod.reversible) {
                    val rb = prec + gain
                    // E.1.1: step size = 2^(Rb - eps_b) * (1 + mu_b / 2^11).
                    // Magnitudes arrive doubled (carrying the half-step bias),
                    // hence the /2 folded into the scale.
                    val delta = (1.0 + band.stepMant / 2048.0) * pow2((rb - band.stepExp).toDouble()) / 2.0
                    // Store scaled values as fixed-point via Float bits in the Int array:
                    // keep it simple and materialize into a FloatArray at DWT time via
                    // the same integer array scaled by 2^13.
                    val scale = delta * (1 shl FRACT)
                    for (i in band.coeffs.indices) {
                        band.coeffs[i] = (band.coeffs[i] * scale).toInt()
                    }
                }
            }
        }

        // Inverse DWT: successively synthesize resolutions.
        var current = SubbandGrid(
            tc.resolutions[0].bands[0].x0, tc.resolutions[0].bands[0].y0,
            tc.resolutions[0].bands[0].x1, tc.resolutions[0].bands[0].y1,
            tc.resolutions[0].bands[0].coeffs,
        )
        for (rr in 1 until tc.resolutions.size) {
            val res = tc.resolutions[rr]
            current = synthesize(current, res, cod.reversible)
        }

        // Store RAW signed samples: the DC shift and clamp happen after the
        // inverse MCT (clamping chroma differences here would corrupt colour).
        val x0 = current.x0; val y0 = current.y0
        val cw = current.x1 - current.x0
        for (y in current.y0 until current.y1) {
            for (x in current.x0 until current.x1) {
                var v = current.data[(y - y0) * cw + (x - x0)]
                if (!cod.reversible) v = v shr FRACT
                val ppx = x - px0
                val ppy = y - py0
                if (ppx in 0 until pw && ppy in 0 until ph) plane[ppy * pw + ppx] = v
            }
        }
    }

    /** DC level shift + clamp over one tile-component's rect, after any MCT. */
    private fun finalizeTileComp(
        s: Siz, tc: TileComp, plane: IntArray, pw: Int, ph: Int, px0: Int, py0: Int,
    ) {
        val prec = s.prec[tc.comp]
        val shiftVal = if (s.signed[tc.comp]) 0 else 1 shl (prec - 1)
        val maxV = (1 shl prec) - 1
        for (y in tc.y0 until tc.y1) for (x in tc.x0 until tc.x1) {
            val ppx = x - px0
            val ppy = y - py0
            if (ppx !in 0 until pw || ppy !in 0 until ph) continue
            val i = ppy * pw + ppx
            var v = plane[i] + shiftVal
            if (v < 0) v = 0
            if (v > maxV) v = maxV
            plane[i] = v
        }
    }

    private const val FRACT = 13

    private fun pow2(e: Double): Double {
        var v = 1.0
        var n = e.toInt()
        while (n > 0) { v *= 2; n-- }
        while (n < 0) { v /= 2; n++ }
        return v
    }

    private class SubbandGrid(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val data: IntArray)

    /** One 2D synthesis level: LL = [ll] + res.bands (HL/LH/HH) -> next LL. */
    private fun synthesize(ll: SubbandGrid, res: Resolution, reversible: Boolean): SubbandGrid {
        val x0 = res.x0; val y0 = res.y0; val x1 = res.x1; val y1 = res.y1
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return SubbandGrid(x0, y0, x1, y1, IntArray(0))
        val a = IntArray(w * h)

        fun scatter(bx0: Int, by0: Int, bx1: Int, by1: Int, data: IntArray, xo: Int, yo: Int) {
            val bw = bx1 - bx0
            for (v in by0 until by1) for (u in bx0 until bx1) {
                val gx = 2 * u + xo
                val gy = 2 * v + yo
                if (gx in x0 until x1 && gy in y0 until y1) {
                    a[(gy - y0) * w + (gx - x0)] = data[(v - by0) * bw + (u - bx0)]
                }
            }
        }
        scatter(ll.x0, ll.y0, ll.x1, ll.y1, ll.data, 0, 0)
        for (band in res.bands) {
            val xo = band.orient and 1
            val yo = (band.orient shr 1) and 1
            scatter(band.x0, band.y0, band.x1, band.y1, band.coeffs, xo, yo)
        }

        // Horizontal pass on every row, then vertical on every column (T.800 F.3.4:
        // the order is interchangeable for these filters).
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = a[y * w + x]
            lift1d(row, x0, x0 + w, reversible)
            for (x in 0 until w) a[y * w + x] = row[x]
        }
        val col = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = a[y * w + x]
            lift1d(col, y0, y0 + h, reversible)
            for (y in 0 until h) a[y * w + x] = col[y]
        }
        return SubbandGrid(x0, y0, x1, y1, a)
    }

    /**
     * In-place 1D synthesis over samples with GLOBAL indices [i0, i1);
     * X[k] holds global index i0+k. Interleaved layout: even global indices
     * are low-pass, odd are high-pass.
     */
    private fun lift1d(x: IntArray, i0: Int, i1: Int, reversible: Boolean) {
        val n = i1 - i0
        if (n <= 0) return
        if (n == 1) {
            // Single-sample special case (F.3.7): an odd (high-pass only)
            // sample halves; an even sample passes through.
            if (i0 % 2 != 0 && reversible) x[0] = x[0] shr 1
            return
        }
        fun get(i: Int): Int {
            // Symmetric extension around the boundaries.
            var k = i
            val last = i1 - 1
            while (k < i0 || k > last) {
                k = if (k < i0) 2 * i0 - k else 2 * last - k
            }
            return x[k - i0]
        }
        fun set(i: Int, v: Int) { if (i in i0 until i1) x[i - i0] = v }

        if (reversible) {
            // 5/3: even then odd (T.800 F.3.8.2.1).
            var i = if (i0 % 2 == 0) i0 else i0 + 1
            while (i < i1) {
                set(i, get(i) - ((get(i - 1) + get(i + 1) + 2) shr 2))
                i += 2
            }
            i = if (i0 % 2 == 0) i0 + 1 else i0
            while (i < i1) {
                set(i, get(i) + ((get(i - 1) + get(i + 1)) shr 1))
                i += 2
            }
        } else {
            // 9/7 on FRACT fixed-point values.
            fun stepScale(parity: Int, factorNum: Long) {
                var i = if (i0 % 2 == parity) i0 else i0 + 1
                while (i < i1) {
                    set(i, ((get(i).toLong() * factorNum) shr FIX_SHIFT).toInt())
                    i += 2
                }
            }
            fun stepLift(parity: Int, coefNum: Long) {
                var i = if (i0 % 2 == parity) i0 else i0 + 1
                while (i < i1) {
                    val nsum = get(i - 1).toLong() + get(i + 1).toLong()
                    set(i, (get(i).toLong() + ((nsum * coefNum) shr FIX_SHIFT)).toInt())
                    i += 2
                }
            }
            stepScale(0, K_FIX)         // even *= K
            stepScale(1, INV_K_FIX)     // odd  *= 1/K
            stepLift(0, NEG_DELTA_FIX)  // even -= delta * (odd neighbours)
            stepLift(1, NEG_GAMMA_FIX)
            stepLift(0, NEG_BETA_FIX)
            stepLift(1, NEG_ALPHA_FIX)
        }
    }

    private const val FIX_SHIFT = 16
    private val K_FIX = (1.230174104914001 * (1 shl FIX_SHIFT)).toLong()
    private val INV_K_FIX = ((1.0 / 1.230174104914001) * (1 shl FIX_SHIFT)).toLong()
    private val NEG_ALPHA_FIX = (1.586134342059924 * (1 shl FIX_SHIFT)).toLong()
    private val NEG_BETA_FIX = (0.052980118572961 * (1 shl FIX_SHIFT)).toLong()
    private val NEG_GAMMA_FIX = (-0.882911075530934 * (1 shl FIX_SHIFT)).toLong()
    private val NEG_DELTA_FIX = (-0.443506852043971 * (1 shl FIX_SHIFT)).toLong()

    // ---- inverse MCT + assembly ---------------------------------------------------

    private fun applyInverseMct(
        s: Siz, comps: List<TileComp>, reversible: Boolean,
        planes: Array<IntArray>, planeW: IntArray, planeH: IntArray,
    ) {
        // The three colour components must share geometry for the MCT.
        val c0 = comps[0]
        if (planeW[0] != planeW[1] || planeW[0] != planeW[2] || planeH[0] != planeH[1] || planeH[0] != planeH[2]) return
        val px0 = ceilDiv(s.xosiz, s.dx[0]); val py0 = ceilDiv(s.yosiz, s.dy[0])
        val w = planeW[0]
        for (ty in c0.y0 until c0.y1) {
            for (tx in c0.x0 until c0.x1) {
                val x = tx - px0; val y = ty - py0
                if (x !in 0 until w || y !in 0 until planeH[0]) continue
                val i = y * w + x
                // Planes hold raw signed samples here; finalizeTileComp shifts
                // and clamps afterwards.
                val y0v = planes[0][i]
                val u = planes[1][i]
                val v = planes[2][i]
                if (reversible) { // RCT (G.2)
                    val g = y0v - ((u + v) shr 2)
                    planes[0][i] = v + g
                    planes[1][i] = g
                    planes[2][i] = u + g
                } else { // ICT (G.3)
                    planes[0][i] = (y0v + 1.402 * v).toInt()
                    planes[1][i] = (y0v - 0.34413 * u - 0.71414 * v).toInt()
                    planes[2][i] = (y0v + 1.772 * u).toInt()
                }
            }
        }
    }

    private fun assemble(
        s: Siz, jp2: Jp2Info, mct: Boolean,
        planes: Array<IntArray>, planeW: IntArray, planeH: IntArray,
        imgW: Int, imgH: Int,
    ): Result {
        // Colour channel count: 1 = gray, >= 3 = RGB. A cdef opacity channel
        // (type 1) beyond the colour channels becomes the alpha plane.
        var alphaComp = -1
        for ((cn, typ) in jp2.channelTypes) {
            if (typ == 1 && cn < s.comps) alphaComp = cn
        }
        val colorComps = when {
            s.comps >= 3 && (alphaComp == -1 || alphaComp >= 3) -> 3
            else -> 1
        }

        fun sample(c: Int, x: Int, y: Int): Int {
            val sx = x / s.dx[c]
            val sy = y / s.dy[c]
            val cw = planeW[c]; val ch = planeH[c]
            val cx = sx.coerceIn(0, cw - 1)
            val cy = sy.coerceIn(0, ch - 1)
            return planes[c][cy * cw + cx]
        }

        fun to8(v: Int, prec: Int): Int = when {
            prec == 8 -> v
            prec > 8 -> v shr (prec - 8)
            else -> v * 255 / ((1 shl prec) - 1)
        }.coerceIn(0, 255)

        val out = ByteArray(imgW * imgH * colorComps)
        for (y in 0 until imgH) for (x in 0 until imgW) {
            for (c in 0 until colorComps) {
                val v = to8(sample(c, x, y), s.prec[c])
                out[(y * imgW + x) * colorComps + c] = v.toByte()
            }
        }
        val alpha = if (alphaComp >= 0) {
            ByteArray(imgW * imgH).also { ab ->
                for (y in 0 until imgH) for (x in 0 until imgW) {
                    ab[y * imgW + x] = to8(sample(alphaComp, x, y), s.prec[alphaComp]).toByte()
                }
            }
        } else null
        return Result(
            imgW, imgH,
            if (colorComps == 3) "DeviceRGB" else "DeviceGray",
            out, alpha,
        )
    }
}
