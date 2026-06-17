package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.render.PdfPath

/**
 * Type 1 charstring → [PdfPath] interpreter (Adobe Type 1 Font Format §6.4).
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
) {

    private val pathBuilder = PdfPath.Builder()
    private val stack = ArrayDeque<Double>()
    private val psStack = ArrayDeque<Double>()  // OtherSubrs "PostScript stack"
    private var x = 0.0
    private var y = 0.0
    private var done = false
    private var depth = 0

    fun interpret(): PdfPath {
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
                pathBuilder.moveTo(x, y); stack.clear()
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
            11 -> { depth--; done = false; return }    // return
            13 -> {                                    // hsbw: sbx wx
                if (stack.size >= 2) {
                    /* wx = */ stack.removeLast()
                    val sbx = stack.removeLast()
                    x = sbx; y = 0.0
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
                pathBuilder.moveTo(x, y); stack.clear()
            }
            22 -> {                                    // hmoveto: dx
                if (stack.isNotEmpty()) x += stack.removeLast()
                pathBuilder.moveTo(x, y); stack.clear()
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
                    val sbx = stack.removeLast()
                    x = sbx; y = sby
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
        // OtherSubrs 0..4 are Adobe's flex/hint machinery. We push the
        // appropriate values onto the PS stack so subsequent "pop" operators
        // see them. For non-rendering purposes most are no-ops.
        when (num) {
            0 -> {
                // End of flex: push end x, y, flexHeight (3 values) — but for
                // pure vector output we treat flex as straight segments already
                // drawn by the OtherSubr 1 setup. Push the last point.
                psStack.addLast(y); psStack.addLast(x); psStack.addLast(0.0)
            }
            1, 2 -> { /* start flex / mid flex — handled by drawn segments */ }
            3 -> {
                // Counter control / hint replacement — push subr 3.
                psStack.addLast(3.0)
            }
            else -> args.forEach { psStack.addLast(it) }
        }
    }

    private fun seac() {
        // Composite glyph: asb adx ady bchar achar — we'd recursively render
        // bchar and achar from the same font's CharStrings. We don't have a
        // way to look up by char code here without the encoding context, so
        // we silently skip — affects only a handful of accented glyphs.
        stack.clear()
    }
}
