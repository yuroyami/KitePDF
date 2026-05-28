package com.yuroyami.kitepdf.font

import com.yuroyami.kitepdf.render.PdfPath

/**
 * Type 2 charstring → [PdfPath] interpreter (Adobe Tech Note 5177).
 *
 * Type 2 is a stack-based bytecode that drives a pen across the glyph. Each
 * charstring is independent; subroutine calls (`callsubr`, `callgsubr`)
 * index into local / global INDEX tables with a bias adjustment that depends
 * on the table size (Tech Note 5176, Appendix D).
 *
 * Coverage:
 *   - All path-construction operators (`rmoveto`, `hmoveto`, `vmoveto`,
 *     `rlineto`, `hlineto`, `vlineto`, `rrcurveto`, `hhcurveto`, `vvcurveto`,
 *     `hvcurveto`, `vhcurveto`, `rcurveline`, `rlinecurve`).
 *   - `endchar` (with optional width as first stack value).
 *   - Subroutines (`callsubr`, `callgsubr`, `return`).
 *   - Hint operators (`hstem`, `vstem`, `hstemhm`, `vstemhm`, `hintmask`,
 *     `cntrmask`) are recognised so we consume operands + the hint mask bits
 *     correctly; rendering ignores hints (we don't rasterize).
 *   - Flex operators approximated as the closest cubic Bézier sequence.
 *   - Arithmetic ops (`add`, `sub`, `mul`, `div`, `neg`, `random`, …) supported
 *     so subroutines using compact tricks still evaluate.
 */
internal class CharstringInterpreter(
    private val charstring: ByteArray,
    private val localSubrs: List<ByteArray>,
    private val globalSubrs: List<ByteArray>,
    @Suppress("unused") private val defaultWidthX: Double,
    @Suppress("unused") private val nominalWidthX: Double,
) {

    private val pathBuilder = PdfPath.Builder()
    private val stack = ArrayDeque<Double>()
    private var x = 0.0
    private var y = 0.0
    private var hintCount = 0
    private var inHintMask = false
    private var done = false
    private var widthSet = false
    /** Recursion depth — Type 2 spec mandates ≤10. */
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
                    b == 28 -> {
                        if (i + 2 >= cs.size) return
                        val v = ((cs[i + 1].toInt() and 0xFF) shl 8) or (cs[i + 2].toInt() and 0xFF)
                        val signed = if (v and 0x8000 != 0) v - 0x10000 else v
                        stack.addLast(signed.toDouble())
                        i += 3
                    }
                    b == 255 -> {
                        if (i + 4 >= cs.size) return
                        val v = ((cs[i + 1].toInt() and 0xFF) shl 24) or
                            ((cs[i + 2].toInt() and 0xFF) shl 16) or
                            ((cs[i + 3].toInt() and 0xFF) shl 8) or
                            (cs[i + 4].toInt() and 0xFF)
                        stack.addLast(v / 65536.0)
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
                        when (b) {
                            21 -> { rmoveto() }
                            22 -> { hmoveto() }
                            4 -> { vmoveto() }
                            5 -> rlineto()
                            6 -> hlineto()
                            7 -> vlineto()
                            8 -> rrcurveto()
                            24 -> rcurveline()
                            25 -> rlinecurve()
                            26 -> vvcurveto()
                            27 -> hhcurveto()
                            30 -> vhcurveto()
                            31 -> hvcurveto()
                            14 -> { endchar(); return }
                            1, 18 -> hint(b)           // hstem / hstemhm
                            3, 23 -> hint(b)           // vstem / vstemhm
                            19, 20 -> {                // hintmask / cntrmask
                                hint(b)
                                val bits = (hintCount + 7) / 8
                                i += bits
                                stack.clear()
                                inHintMask = true
                            }
                            10 -> {                    // callsubr
                                val idx = stack.removeLast().toInt() + subrBias(localSubrs.size)
                                val sub = localSubrs.getOrNull(idx) ?: continue
                                execute(sub)
                            }
                            29 -> {                    // callgsubr
                                val idx = stack.removeLast().toInt() + subrBias(globalSubrs.size)
                                val sub = globalSubrs.getOrNull(idx) ?: continue
                                execute(sub)
                            }
                            11 -> { depth--; return }  // return
                            else -> stack.clear()      // unknown — defensive reset
                        }
                    }
                }
            }
        } finally {
            depth--
        }
    }

    private fun handleTwoByteOp(op: Int) {
        when (op) {
            // Arithmetic
            10 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a + b) }   // add
            11 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a - b) }   // sub
            12 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (b != 0.0) a / b else 0.0) }  // div
            24 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a * b) }   // mul
            14 -> stack.addLast(-stack.removeLast())   // neg
            23 -> stack.addLast(0.5)                   // random — stub to a constant
            // Flex approximations (treat as 6 separate curves; not pixel-perfect).
            35 -> flex(6)        // flex
            34 -> flex(7)        // hflex
            36 -> flex(11)       // hflex1
            37 -> flex(5)        // flex1
            // Unknown two-byte ops just clear the stack.
            else -> stack.clear()
        }
    }

    /* ─── Path operators ─────────────────────────────────────────────────── */

    private fun rmoveto() {
        consumeWidthOnFirstMove()
        if (stack.size >= 2) {
            y += stack.removeLast()
            x += stack.removeLast()
        }
        pathBuilder.moveTo(x, y)
        stack.clear()
    }

    private fun hmoveto() {
        consumeWidthOnFirstMove()
        if (stack.isNotEmpty()) x += stack.removeLast()
        pathBuilder.moveTo(x, y)
        stack.clear()
    }

    private fun vmoveto() {
        consumeWidthOnFirstMove()
        if (stack.isNotEmpty()) y += stack.removeLast()
        pathBuilder.moveTo(x, y)
        stack.clear()
    }

    private fun consumeWidthOnFirstMove() {
        // First moveto may have an odd number of arguments — the leading one is
        // the optional width. We never use the width here.
        if (widthSet) return
        widthSet = true
        // hmoveto/vmoveto need 1 operand, rmoveto needs 2; anything extra is the width.
        // The caller pops the rest.
    }

    private fun rlineto() {
        var k = 0
        while (k + 1 < stack.size + 2) {
            if (stack.size < 2) break
            val dy = stack.removeFirst()
            // Wait — we want first-in-first-out for these operators since stack
            // arguments are pushed in order. removeFirst preserves order.
            val dx = dy
            val dy2 = stack.removeFirst()
            x += dx; y += dy2
            pathBuilder.lineTo(x, y)
            k += 2
        }
        stack.clear()
    }

    private fun hlineto() {
        // Alternating horizontal/vertical lines.
        var horizontal = true
        while (stack.isNotEmpty()) {
            if (horizontal) x += stack.removeFirst() else y += stack.removeFirst()
            pathBuilder.lineTo(x, y)
            horizontal = !horizontal
        }
    }

    private fun vlineto() {
        var vertical = true
        while (stack.isNotEmpty()) {
            if (vertical) y += stack.removeFirst() else x += stack.removeFirst()
            pathBuilder.lineTo(x, y)
            vertical = !vertical
        }
    }

    private fun rrcurveto() {
        while (stack.size >= 6) {
            val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
            val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
            val dx3 = stack.removeFirst(); val dy3 = stack.removeFirst()
            val x1 = x + dx1; val y1 = y + dy1
            val x2 = x1 + dx2; val y2 = y1 + dy2
            val x3 = x2 + dx3; val y3 = y2 + dy3
            pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
            x = x3; y = y3
        }
    }

    private fun rcurveline() {
        while (stack.size >= 8) {
            val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
            val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
            val dx3 = stack.removeFirst(); val dy3 = stack.removeFirst()
            val x1 = x + dx1; val y1 = y + dy1
            val x2 = x1 + dx2; val y2 = y1 + dy2
            val x3 = x2 + dx3; val y3 = y2 + dy3
            pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
            x = x3; y = y3
        }
        if (stack.size >= 2) {
            x += stack.removeFirst(); y += stack.removeFirst()
            pathBuilder.lineTo(x, y)
        }
        stack.clear()
    }

    private fun rlinecurve() {
        while (stack.size >= 8) {
            x += stack.removeFirst(); y += stack.removeFirst()
            pathBuilder.lineTo(x, y)
        }
        if (stack.size >= 6) {
            val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
            val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
            val dx3 = stack.removeFirst(); val dy3 = stack.removeFirst()
            val x1 = x + dx1; val y1 = y + dy1
            val x2 = x1 + dx2; val y2 = y1 + dy2
            val x3 = x2 + dx3; val y3 = y2 + dy3
            pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
            x = x3; y = y3
        }
        stack.clear()
    }

    private fun vvcurveto() {
        var firstDx = 0.0
        if (stack.size % 4 == 1) firstDx = stack.removeFirst()
        while (stack.size >= 4) {
            val dx1 = firstDx; firstDx = 0.0
            val dy1 = stack.removeFirst()
            val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
            val dy3 = stack.removeFirst()
            val x1 = x + dx1; val y1 = y + dy1
            val x2 = x1 + dx2; val y2 = y1 + dy2
            val x3 = x2; val y3 = y2 + dy3
            pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
            x = x3; y = y3
        }
        stack.clear()
    }

    private fun hhcurveto() {
        var firstDy = 0.0
        if (stack.size % 4 == 1) firstDy = stack.removeFirst()
        while (stack.size >= 4) {
            val dx1 = stack.removeFirst()
            val dy1 = firstDy; firstDy = 0.0
            val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
            val dx3 = stack.removeFirst()
            val x1 = x + dx1; val y1 = y + dy1
            val x2 = x1 + dx2; val y2 = y1 + dy2
            val x3 = x2 + dx3; val y3 = y2
            pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
            x = x3; y = y3
        }
        stack.clear()
    }

    /** Sequence of alternating-axis curve segments starting horizontally. */
    private fun hvcurveto() = alternatingCurve(startHorizontal = true)
    private fun vhcurveto() = alternatingCurve(startHorizontal = false)

    private fun alternatingCurve(startHorizontal: Boolean) {
        var horiz = startHorizontal
        while (stack.size >= 4) {
            val a = stack.removeFirst()
            val b = stack.removeFirst()
            val c = stack.removeFirst()
            val d = stack.removeFirst()
            val tail = if (stack.size == 1) stack.removeFirst() else 0.0
            val (dx1, dy1) = if (horiz) a to 0.0 else 0.0 to a
            val (dx2, dy2) = b to c
            val (dx3, dy3) = if (horiz) tail to d else d to tail
            val x1 = x + dx1; val y1 = y + dy1
            val x2 = x1 + dx2; val y2 = y1 + dy2
            val x3 = x2 + dx3; val y3 = y2 + dy3
            pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
            x = x3; y = y3
            horiz = !horiz
        }
        stack.clear()
    }

    private fun endchar() {
        // Close subpath if any drawing happened.
        if (!pathBuilder.isEmpty()) pathBuilder.close()
        done = true
        stack.clear()
    }

    private fun hint(@Suppress("unused") op: Int) {
        // Count stems: each stem is 2 operands. If width was given as the leading
        // odd operand, hintCount += stack.size / 2.
        hintCount += stack.size / 2
        stack.clear()
    }

    /**
     * Flex operators draw a sequence of curves; we approximate as the
     * simplest interpretation (one rrcurveto-style block per operator).
     */
    private fun flex(@Suppress("unused") expectedArgs: Int) {
        // Treat remaining stack as a sequence of curve operands.
        rrcurveto()
        stack.clear()
    }

    /** Subroutine bias per Type 2 §4.7. */
    private fun subrBias(count: Int): Int = when {
        count < 1240 -> 107
        count < 33900 -> 1131
        else -> 32768
    }
}
