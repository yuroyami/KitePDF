package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.render.KitePath

/**
 * Type 1 charstring → [KitePath] interpreter (Adobe Type 1 Font Format §6.4).
 *
 * The bytecode is conceptually simpler than Type 2: no flex sub-program in
 * the language proper (it's done via callothersubr), no hintmasks, no
 * vsindex / blend (those are for variable fonts). About 30 operators total.
 *
 * Subroutines (`callsubr`) call into the font's Subrs INDEX with the
 * `4 16 callothersubr` "OtherSubrs" tradition handled by Adobe-specific
 * subrs 0–4. We support enough of OtherSubr 0–3 to render flex sequences;
 * unknown OtherSubrs become no-ops.
 *
 * Stack: per spec it's small (limit 24 for Type 1). We don't enforce a hard
 * limit because some defective fonts overflow it and viewers tolerate.
 */
internal class Type1CharstringInterpreter(
    private val charstring: ByteArray,
    private val subrs: List<ByteArray>,
    /**
     * Resolver for `seac` composite glyphs: given a StandardEncoding code, return
     * the *decrypted* charstring bytes for that glyph (base or accent), or null if
     * unavailable. When null, seac is skipped (previous behaviour). This is wired
     * by [Type1Font] which owns the CharStrings + StandardEncoding tables.
     */
    private val seacResolver: ((Int) -> ByteArray?)? = null,
) {

    private val pathBuilder = KitePath.Builder()
    private val stack = ArrayDeque<Double>()
    private val psStack = ArrayDeque<Double>()  // OtherSubrs "PostScript stack"
    private var x = 0.0
    private var y = 0.0
    private var done = false
    private var depth = 0

    /** Left sidebearing x set by hsbw/sbw — needed for seac accent placement. */
    private var sbx = 0.0

    /* ─── Flex state (OtherSubrs 0/1/2) ──────────────────────────────────── */
    /** True between OtherSubr 1 (flex begin) and OtherSubr 0 (flex end). */
    private var inFlex = false
    /** The reference points collected from the 7 flex rmoveto's (x then y). */
    private val flexPts = ArrayList<Double>(14)

    fun interpret(): KitePath {
        execute(charstring)
        return pathBuilder.build()
    }

    private fun execute(cs: ByteArray) {
        if (done) return
        if (++depth > 10) { done = true; return }
        var i = 0
        try {
            while (i < cs.size && !done) {
                val b = cs[i].toInt() and 0xFF
                when {
                    b in 32..246 -> { stack.addLast((b - 139).toDouble()); i++ }
                    b in 247..250 -> {
                        if (i + 1 >= cs.size) return
                        val b1 = cs[i + 1].toInt() and 0xFF
                        stack.addLast(((b - 247) * 256 + b1 + 108).toDouble())
                        i += 2
                    }
                    b in 251..254 -> {
                        if (i + 1 >= cs.size) return
                        val b1 = cs[i + 1].toInt() and 0xFF
                        stack.addLast((-(b - 251) * 256 - b1 - 108).toDouble())
                        i += 2
                    }
                    b == 255 -> {
                        // 32-bit signed big-endian — Type 1 form (NOT Type 2's fixed-point).
                        if (i + 4 >= cs.size) return
                        val v = ((cs[i + 1].toInt() and 0xFF) shl 24) or
                            ((cs[i + 2].toInt() and 0xFF) shl 16) or
                            ((cs[i + 3].toInt() and 0xFF) shl 8) or
                            (cs[i + 4].toInt() and 0xFF)
                        stack.addLast(v.toDouble())
                        i += 5
                    }
                    b == 12 -> {
                        if (i + 1 >= cs.size) return
                        val op = cs[i + 1].toInt() and 0xFF
                        i += 2
                        handleTwoByteOp(op)
                    }
                    else -> {
                        i++
                        handleOneByteOp(b)
                    }
                }
            }
        } finally {
            depth--
        }
    }

    private fun handleOneByteOp(op: Int) {
        when (op) {
            1, 3 -> stack.clear()                      // hstem / vstem (ignored)
            4 -> {                                     // vmoveto: dy
                if (stack.isNotEmpty()) y += stack.removeLast()
                if (inFlex) { flexPts.add(x); flexPts.add(y) } else pathBuilder.moveTo(x, y)
                stack.clear()
            }
            5 -> {                                     // rlineto: dx dy
                if (stack.size >= 2) {
                    val dy = stack.removeLast(); val dx = stack.removeLast()
                    x += dx; y += dy
                    pathBuilder.lineTo(x, y)
                }
                stack.clear()
            }
            6 -> {                                     // hlineto: dx
                if (stack.isNotEmpty()) x += stack.removeLast()
                pathBuilder.lineTo(x, y); stack.clear()
            }
            7 -> {                                     // vlineto: dy
                if (stack.isNotEmpty()) y += stack.removeLast()
                pathBuilder.lineTo(x, y); stack.clear()
            }
            8 -> {                                     // rrcurveto
                if (stack.size >= 6) {
                    val dy3 = stack.removeLast(); val dx3 = stack.removeLast()
                    val dy2 = stack.removeLast(); val dx2 = stack.removeLast()
                    val dy1 = stack.removeLast(); val dx1 = stack.removeLast()
                    val x1 = x + dx1; val y1 = y + dy1
                    val x2 = x1 + dx2; val y2 = y1 + dy2
                    val x3 = x2 + dx3; val y3 = y2 + dy3
                    pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
                    x = x3; y = y3
                }
                stack.clear()
            }
            9 -> { pathBuilder.close(); stack.clear() } // closepath
            10 -> {                                    // callsubr: n
                if (stack.isEmpty()) return
                val idx = stack.removeLast().toInt()
                val sub = subrs.getOrNull(idx) ?: return
                execute(sub)
            }
            11 -> { done = false; return }             // return (depth is decremented by execute's finally)
            13 -> {                                    // hsbw: sbx wx
                if (stack.size >= 2) {
                    /* wx = */ stack.removeLast()
                    val sb = stack.removeLast()
                    sbx = sb
                    x = sb; y = 0.0
                }
                stack.clear()
            }
            14 -> {                                    // endchar
                if (!pathBuilder.isEmpty()) pathBuilder.close()
                done = true; stack.clear()
            }
            21 -> {                                    // rmoveto: dx dy
                if (stack.size >= 2) {
                    val dy = stack.removeLast(); val dx = stack.removeLast()
                    x += dx; y += dy
                }
                if (inFlex) { flexPts.add(x); flexPts.add(y) } else pathBuilder.moveTo(x, y)
                stack.clear()
            }
            22 -> {                                    // hmoveto: dx
                if (stack.isNotEmpty()) x += stack.removeLast()
                if (inFlex) { flexPts.add(x); flexPts.add(y) } else pathBuilder.moveTo(x, y)
                stack.clear()
            }
            30 -> {                                    // vhcurveto: dy1 dx2 dy2 dx3
                if (stack.size >= 4) {
                    val dx3 = stack.removeLast()
                    val dy2 = stack.removeLast(); val dx2 = stack.removeLast()
                    val dy1 = stack.removeLast()
                    val x1 = x; val y1 = y + dy1
                    val x2 = x1 + dx2; val y2 = y1 + dy2
                    val x3 = x2 + dx3; val y3 = y2
                    pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
                    x = x3; y = y3
                }
                stack.clear()
            }
            31 -> {                                    // hvcurveto: dx1 dx2 dy2 dy3
                if (stack.size >= 4) {
                    val dy3 = stack.removeLast()
                    val dy2 = stack.removeLast(); val dx2 = stack.removeLast()
                    val dx1 = stack.removeLast()
                    val x1 = x + dx1; val y1 = y
                    val x2 = x1 + dx2; val y2 = y1 + dy2
                    val x3 = x2; val y3 = y2 + dy3
                    pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
                    x = x3; y = y3
                }
                stack.clear()
            }
            else -> stack.clear()                      // unknown — defensive reset
        }
    }

    private fun handleTwoByteOp(op: Int) {
        when (op) {
            0, 1, 2 -> stack.clear()                   // dotsection / vstem3 / hstem3
            6 -> seac()                                // composite glyph
            7 -> {                                     // sbw: sbx sby wx wy
                if (stack.size >= 4) {
                    /* wy = */ stack.removeLast()
                    /* wx = */ stack.removeLast()
                    val sby = stack.removeLast()
                    val sb = stack.removeLast()
                    sbx = sb
                    x = sb; y = sby
                }
                stack.clear()
            }
            12 -> {                                    // div
                if (stack.size >= 2) {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(if (b != 0.0) a / b else 0.0)
                }
            }
            16 -> {                                    // callothersubr: arg1 ... argN N othersubr#
                if (stack.size < 2) { stack.clear(); return }
                val otherSubr = stack.removeLast().toInt()
                val n = stack.removeLast().toInt()
                val args = mutableListOf<Double>()
                repeat(n) { if (stack.isNotEmpty()) args.add(0, stack.removeLast()) }
                handleOtherSubr(otherSubr, args)
            }
            17 -> {                                    // pop — moves PS stack top → charstring stack
                if (psStack.isNotEmpty()) stack.addLast(psStack.removeLast())
            }
            33 -> {                                    // setcurrentpoint: x y
                if (stack.size >= 2) {
                    y = stack.removeLast()
                    x = stack.removeLast()
                }
                stack.clear()
            }
            34 -> {                                    // hflex (Type 1 doesn't have it natively but some fonts use it)
                stack.clear()
            }
            else -> stack.clear()
        }
    }

    private fun handleOtherSubr(num: Int, args: List<Double>) {
        // OtherSubrs 0..3 are Adobe's flex/hint machinery (Black Book §8).
        when (num) {
            1 -> {
                // Begin flex. The following 7 rmoveto's set the reference point
                // and 6 Bezier points; they must NOT emit real moves — collect them.
                inFlex = true
                flexPts.clear()
            }
            2 -> {
                // Mid-flex marker between rmoveto's — nothing to do; points are
                // gathered by the rmoveto handler while inFlex is true.
            }
            0 -> {
                // End flex: args = [flexHeight, endX, endY]. Emit the two curves
                // through the 6 collected control/end points (skipping the first
                // reference point at flexPts[0..1]), then leave (endX, endY) on the
                // PS stack for the canonical "pop pop setcurrentpoint".
                inFlex = false
                if (flexPts.size >= 14) {
                    val x1 = flexPts[2];  val y1 = flexPts[3]
                    val x2 = flexPts[4];  val y2 = flexPts[5]
                    val x3 = flexPts[6];  val y3 = flexPts[7]
                    val x4 = flexPts[8];  val y4 = flexPts[9]
                    val x5 = flexPts[10]; val y5 = flexPts[11]
                    val x6 = flexPts[12]; val y6 = flexPts[13]
                    pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
                    pathBuilder.curveTo(x4, y4, x5, y5, x6, y6)
                    x = x6; y = y6
                }
                flexPts.clear()
                // endX, endY come from args when present; fall back to current point.
                val endX = if (args.size >= 3) args[1] else x
                val endY = if (args.size >= 3) args[2] else y
                x = endX; y = endY
                // Push so that: pop -> endX (top), pop -> endY; setcurrentpoint reads y=endY, x=endX.
                psStack.addLast(endY)
                psStack.addLast(endX)
            }
            3 -> {
                // Hint replacement: no-op for outlines. The call form is
                // "subr# 1 3 callothersubr pop callsubr"; leave subr# for the pop.
                psStack.addLast(if (args.isNotEmpty()) args[0] else 3.0)
            }
            else -> {
                // Unknown OtherSubr: preserve PostScript stack semantics by
                // returning the arguments in reverse (each `pop` peels the top).
                for (a in args) psStack.addLast(a)
            }
        }
    }

    private fun seac() {
        // Composite (accented) glyph: `asb adx ady bchar achar seac`.
        // bchar/achar are StandardEncoding codes. Render the base glyph at its
        // own origin, then the accent translated by (sbx + adx - asb, ady) where
        // sbx is the composite's own left sidebearing (from its hsbw). This is
        // the standard Type 1 accented-glyph mechanism (Black Book §6.4).
        val resolver = seacResolver
        if (stack.size < 5 || resolver == null) { stack.clear(); return }
        val achar = stack.removeLast().toInt()
        val bchar = stack.removeLast().toInt()
        val ady = stack.removeLast()
        val adx = stack.removeLast()
        val asb = stack.removeLast()
        stack.clear()

        val baseCs = resolver(bchar)
        val accentCs = resolver(achar)

        // Base glyph: rendered at its natural position.
        if (baseCs != null) appendGlyph(baseCs, 0.0, 0.0)
        // Accent glyph: offset. The accent's own hsbw sets its origin to asb-adjusted
        // sidebearing; the net translation per spec is (sbx + adx - asb, ady).
        if (accentCs != null) appendGlyph(accentCs, sbx + adx - asb, ady)

        done = true
    }

    /** Render [cs] with a fresh interpreter and append its outline, translated by (dx, dy). */
    private fun appendGlyph(cs: ByteArray, dx: Double, dy: Double) {
        val sub = Type1CharstringInterpreter(cs, subrs, seacResolver).interpret()
        for (seg in sub.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> pathBuilder.moveTo(seg.x + dx, seg.y + dy)
                is KitePath.Segment.LineTo -> pathBuilder.lineTo(seg.x + dx, seg.y + dy)
                is KitePath.Segment.CurveTo -> pathBuilder.curveTo(
                    seg.x1 + dx, seg.y1 + dy, seg.x2 + dx, seg.y2 + dy, seg.x3 + dx, seg.y3 + dy,
                )
                is KitePath.Segment.QuadTo -> pathBuilder.quadTo(
                    seg.x1 + dx, seg.y1 + dy, seg.x2 + dx, seg.y2 + dy,
                )
                is KitePath.Segment.Close -> pathBuilder.close()
            }
        }
    }
}
