package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.render.PdfPath
import kotlin.math.abs
import kotlin.math.sqrt

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
 *   - The full flex family (`flex`, `hflex`, `hflex1`, `flex1`) per TN5177.
 *   - `endchar`, including the deprecated 4-argument `seac` accent-composition
 *     form (via the [seacRenderer] hook the CFF font supplies).
 *   - The optional leading glyph-advance width on the first stack-clearing
 *     operator (exposed as [advanceWidth]).
 *   - Subroutines (`callsubr`, `callgsubr`, `return`).
 *   - Hint operators (`hstem`, `vstem`, `hstemhm`, `vstemhm`, `hintmask`,
 *     `cntrmask`) are recognised so we consume operands + the hint mask bits
 *     correctly; rendering ignores hints (we don't rasterize).
 *   - Arithmetic / storage / conditional ops (`add`, `sub`, `mul`, `div`,
 *     `neg`, `abs`, `sqrt`, `dup`, `drop`, `exch`, `index`, `roll`, `put`,
 *     `get`, `and`, `or`, `not`, `eq`, `ifelse`, `random`) evaluated against a
 *     proper operand + 32-slot transient array so subroutines using compact
 *     tricks still work.
 */
internal class CharstringInterpreter(
    private val charstring: ByteArray,
    private val localSubrs: List<ByteArray>,
    private val globalSubrs: List<ByteArray>,
    private val defaultWidthX: Double,
    private val nominalWidthX: Double,
    /**
     * Optional hook for the deprecated `seac` (`endchar` with 4 operands)
     * accent-composition form. The CFF font supplies a lambda that resolves the
     * base + accent glyphs by their StandardEncoding codes, renders the base at
     * the origin and the accent offset by (adx, ady), and appends the resulting
     * segments to [into]. When null, `seac` is ignored (glyph renders as its own
     * outline only). Kept additive so existing callers stay source-compatible.
     */
    private val seacRenderer: ((baseCode: Int, accentCode: Int, adx: Double, ady: Double, into: PdfPath.Builder) -> Unit)? = null,
) {

    private val pathBuilder = PdfPath.Builder()
    private val stack = ArrayDeque<Double>()
    /** Transient array (`put`/`get`), 32 slots per TN5177. */
    private val transient = DoubleArray(32)
    private var x = 0.0
    private var y = 0.0
    private var hintCount = 0
    private var inHintMask = false
    private var done = false
    private var widthSet = false

    /**
     * Glyph advance width in design units, or null if the charstring did not
     * carry the optional leading width operand (caller should then fall back to
     * the font's default width). Populated during [interpret].
     */
    var advanceWidth: Double? = null
        private set

    /** Recursion depth — Type 2 spec mandates ≤10. */
    private var depth = 0

    fun interpret(): PdfPath {
        execute(charstring)
        return pathBuilder.build()
    }

    private fun execute(cs: ByteArray) {
        if (done) return
        if (++depth > 10) { done = true; depth--; return }
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
                                if (stack.isEmpty()) { done = true; return }
                                val idx = stack.removeLast().toInt() + subrBias(localSubrs.size)
                                val sub = localSubrs.getOrNull(idx) ?: continue
                                execute(sub)
                            }
                            29 -> {                    // callgsubr
                                if (stack.isEmpty()) { done = true; return }
                                val idx = stack.removeLast().toInt() + subrBias(globalSubrs.size)
                                val sub = globalSubrs.getOrNull(idx) ?: continue
                                execute(sub)
                            }
                            11 -> return               // return (depth decremented in finally)
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
            // ── Arithmetic ────────────────────────────────────────────────
            10 -> binOp { a, b -> a + b }                       // add
            11 -> binOp { a, b -> a - b }                       // sub
            12 -> binOp { a, b -> if (b != 0.0) a / b else 0.0 } // div
            24 -> binOp { a, b -> a * b }                       // mul
            14 -> unOp { -it }                                  // neg
            9  -> unOp { abs(it) }                              // abs
            26 -> unOp { if (it >= 0.0) sqrt(it) else 0.0 }     // sqrt
            23 -> stack.addLast(0.5)                            // random — deterministic stub
            // ── Stack ops ─────────────────────────────────────────────────
            18 -> { if (stack.isNotEmpty()) stack.removeLast() }             // drop
            27 -> { if (stack.isNotEmpty()) stack.addLast(stack.last()) }    // dup
            28 -> {                                                          // exch
                if (stack.size >= 2) {
                    val b = stack.removeLast(); val a = stack.removeLast()
                    stack.addLast(b); stack.addLast(a)
                }
            }
            29 -> {                                                          // index
                if (stack.isNotEmpty()) {
                    var n = stack.removeLast().toInt()
                    if (n < 0) n = 0
                    val elem = if (n < stack.size) stack.elementAt(stack.size - 1 - n)
                               else if (stack.isNotEmpty()) stack.last() else 0.0
                    stack.addLast(elem)
                }
            }
            30 -> roll()                                                     // roll
            // ── Transient array ───────────────────────────────────────────
            20 -> {                                                          // put
                if (stack.size >= 2) {
                    val j = stack.removeLast().toInt()
                    val v = stack.removeLast()
                    if (j in transient.indices) transient[j] = v
                }
            }
            21 -> {                                                          // get
                if (stack.isNotEmpty()) {
                    val j = stack.removeLast().toInt()
                    stack.addLast(if (j in transient.indices) transient[j] else 0.0)
                }
            }
            // ── Conditional / boolean ─────────────────────────────────────
            3  -> binOp { a, b -> if (a != 0.0 && b != 0.0) 1.0 else 0.0 }   // and
            4  -> binOp { a, b -> if (a != 0.0 || b != 0.0) 1.0 else 0.0 }   // or
            5  -> unOp { if (it == 0.0) 1.0 else 0.0 }                       // not
            15 -> binOp { a, b -> if (a == b) 1.0 else 0.0 }                 // eq
            22 -> {                                                          // ifelse
                if (stack.size >= 4) {
                    val v2 = stack.removeLast(); val v1 = stack.removeLast()
                    val s2 = stack.removeLast(); val s1 = stack.removeLast()
                    stack.addLast(if (v1 <= v2) s1 else s2)
                }
            }
            // ── Flex family (TN5177 §4.3) ─────────────────────────────────
            34 -> hflex()   // op 12 34 — 7 args
            35 -> flex()    // op 12 35 — 13 args
            36 -> hflex1()  // op 12 36 — 9 args
            37 -> flex1()   // op 12 37 — 11 args
            // Unknown two-byte ops just clear the stack.
            else -> stack.clear()
        }
    }

    private inline fun binOp(f: (Double, Double) -> Double) {
        if (stack.size < 2) return
        val b = stack.removeLast(); val a = stack.removeLast()
        stack.addLast(f(a, b))
    }

    private inline fun unOp(f: (Double) -> Double) {
        if (stack.isEmpty()) return
        stack.addLast(f(stack.removeLast()))
    }

    private fun roll() {
        // roll: num(N) shifted by J. Pops N and J, rotates the top N elements.
        if (stack.size < 2) return
        val j = stack.removeLast().toInt()
        val n = stack.removeLast().toInt()
        if (n <= 0 || n > stack.size) return
        val items = ArrayList<Double>(n)
        repeat(n) { items.add(0, stack.removeLast()) } // items[0]=deepest of the N
        val shift = ((j % n) + n) % n
        // Positive J rolls toward the top: element moves up by J (with wrap).
        val rolled = DoubleArray(n)
        for (k in 0 until n) rolled[(k + shift) % n] = items[k]
        for (k in 0 until n) stack.addLast(rolled[k])
    }

    /* ─── Path operators ─────────────────────────────────────────────────── */

    private fun rmoveto() {
        // rmoveto needs 2 args; an odd extra leading operand is the width.
        maybeTakeWidth(2)
        if (stack.size >= 2) {
            x += stack.removeFirst()
            y += stack.removeFirst()
        }
        pathBuilder.moveTo(x, y)
        stack.clear()
    }

    private fun hmoveto() {
        maybeTakeWidth(1)
        if (stack.isNotEmpty()) x += stack.removeFirst()
        pathBuilder.moveTo(x, y)
        stack.clear()
    }

    private fun vmoveto() {
        maybeTakeWidth(1)
        if (stack.isNotEmpty()) y += stack.removeFirst()
        pathBuilder.moveTo(x, y)
        stack.clear()
    }

    /**
     * On the first stack-clearing operator, an extra leading operand beyond the
     * [expected] argument count is the optional glyph advance width (TN5177:
     * width = nominalWidthX + the extra value). Consumes and records it.
     */
    private fun maybeTakeWidth(expected: Int) {
        if (widthSet) return
        widthSet = true
        if (stack.size > expected) {
            advanceWidth = nominalWidthX + stack.removeFirst()
        } else {
            advanceWidth = defaultWidthX
        }
    }

    /**
     * Consumes the optional leading width on operators that take a fixed even
     * argument count (`hstem`…`endchar`). Any odd extra operand at the front is
     * the width.
     */
    private fun maybeTakeWidthEven() {
        if (widthSet) return
        widthSet = true
        if (stack.size % 2 == 1) {
            advanceWidth = nominalWidthX + stack.removeFirst()
        } else {
            advanceWidth = defaultWidthX
        }
    }

    private fun rlineto() {
        while (stack.size >= 2) {
            x += stack.removeFirst()
            y += stack.removeFirst()
            pathBuilder.lineTo(x, y)
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
            emitCurve(dx1, dy1, dx2, dy2, dx3, dy3)
        }
    }

    private fun rcurveline() {
        while (stack.size >= 8) {
            val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
            val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
            val dx3 = stack.removeFirst(); val dy3 = stack.removeFirst()
            emitCurve(dx1, dy1, dx2, dy2, dx3, dy3)
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
            emitCurve(dx1, dy1, dx2, dy2, dx3, dy3)
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
            emitCurve(dx1, dy1, dx2, dy2, 0.0, dy3)
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
            emitCurve(dx1, dy1, dx2, dy2, dx3, 0.0)
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
            emitCurve(dx1, dy1, dx2, dy2, dx3, dy3)
            horiz = !horiz
        }
        stack.clear()
    }

    /** Emit one cubic from three relative control deltas, advancing the pen. */
    private fun emitCurve(
        dx1: Double, dy1: Double,
        dx2: Double, dy2: Double,
        dx3: Double, dy3: Double,
    ) {
        val x1 = x + dx1; val y1 = y + dy1
        val x2 = x1 + dx2; val y2 = y1 + dy2
        val x3 = x2 + dx3; val y3 = y2 + dy3
        pathBuilder.curveTo(x1, y1, x2, y2, x3, y3)
        x = x3; y = y3
    }

    /* ─── Flex family (Adobe TN5177 §4.3) ────────────────────────────────── */

    /**
     * hflex (op 12 34, 7 args): `dx1 dx2 dy2 dx3 dx4 dx5 dx6`.
     * Two curves whose combined y-travel returns to the starting y.
     * c1 = (dx1,0)(dx2,dy2)(dx3,0); c2 = (dx4,0)(dx5,-dy2)(dx6,0).
     */
    private fun hflex() {
        if (stack.size < 7) { stack.clear(); return }
        val dx1 = stack.removeFirst()
        val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
        val dx3 = stack.removeFirst()
        val dx4 = stack.removeFirst()
        val dx5 = stack.removeFirst()
        val dx6 = stack.removeFirst()
        emitCurve(dx1, 0.0, dx2, dy2, dx3, 0.0)
        emitCurve(dx4, 0.0, dx5, -dy2, dx6, 0.0)
        stack.clear()
    }

    /**
     * flex (op 12 35, 13 args): two full curves plus a trailing flex-depth
     * (fd) operand that is irrelevant to rendering.
     * `dx1 dy1 dx2 dy2 dx3 dy3 dx4 dy4 dx5 dy5 dx6 dy6 fd`.
     */
    private fun flex() {
        if (stack.size < 12) { stack.clear(); return }
        val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
        val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
        val dx3 = stack.removeFirst(); val dy3 = stack.removeFirst()
        val dx4 = stack.removeFirst(); val dy4 = stack.removeFirst()
        val dx5 = stack.removeFirst(); val dy5 = stack.removeFirst()
        val dx6 = stack.removeFirst(); val dy6 = stack.removeFirst()
        // Remaining operand (fd) ignored.
        emitCurve(dx1, dy1, dx2, dy2, dx3, dy3)
        emitCurve(dx4, dy4, dx5, dy5, dx6, dy6)
        stack.clear()
    }

    /**
     * hflex1 (op 12 36, 9 args): `dx1 dy1 dx2 dy2 dx3 dx4 dx5 dy5 dx6`.
     * The y coordinate returns to the starting y; the final dy is chosen so.
     * c1 = (dx1,dy1)(dx2,dy2)(dx3,0);
     * c2 = (dx4,0)(dx5,dy5)(dx6, -(dy1+dy2+dy5)).
     */
    private fun hflex1() {
        if (stack.size < 9) { stack.clear(); return }
        val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
        val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
        val dx3 = stack.removeFirst()
        val dx4 = stack.removeFirst()
        val dx5 = stack.removeFirst(); val dy5 = stack.removeFirst()
        val dx6 = stack.removeFirst()
        emitCurve(dx1, dy1, dx2, dy2, dx3, 0.0)
        emitCurve(dx4, 0.0, dx5, dy5, dx6, -(dy1 + dy2 + dy5))
        stack.clear()
    }

    /**
     * flex1 (op 12 37, 11 args): `dx1 dy1 dx2 dy2 dx3 dy3 dx4 dy4 dx5 dy5 d6`.
     * dx = Σdx1..dx5, dy = Σdy1..dy5. The final point places d6 on the dominant
     * axis while the other axis returns to the starting coordinate:
     * if |dx| > |dy| → last delta = (d6, -dy) else (-dx, d6).
     */
    private fun flex1() {
        if (stack.size < 11) { stack.clear(); return }
        val dx1 = stack.removeFirst(); val dy1 = stack.removeFirst()
        val dx2 = stack.removeFirst(); val dy2 = stack.removeFirst()
        val dx3 = stack.removeFirst(); val dy3 = stack.removeFirst()
        val dx4 = stack.removeFirst(); val dy4 = stack.removeFirst()
        val dx5 = stack.removeFirst(); val dy5 = stack.removeFirst()
        val d6 = stack.removeFirst()
        val dx = dx1 + dx2 + dx3 + dx4 + dx5
        val dy = dy1 + dy2 + dy3 + dy4 + dy5
        emitCurve(dx1, dy1, dx2, dy2, dx3, dy3)
        if (abs(dx) > abs(dy)) {
            emitCurve(dx4, dy4, dx5, dy5, d6, -dy)
        } else {
            emitCurve(dx4, dy4, dx5, dy5, -dx, d6)
        }
        stack.clear()
    }

    private fun endchar() {
        // Deprecated seac accent-composition form: 4 (or 5, with leading width)
        // trailing operands `adx ady bchar achar`.
        if (!widthSet && (stack.size == 5)) {
            advanceWidth = nominalWidthX + stack.removeFirst()
            widthSet = true
        }
        if (stack.size == 4) {
            widthSet = true
            val adx = stack.removeFirst()
            val ady = stack.removeFirst()
            val bchar = stack.removeFirst().toInt()
            val achar = stack.removeFirst().toInt()
            seacRenderer?.invoke(bchar, achar, adx, ady, pathBuilder)
            done = true
            stack.clear()
            return
        }
        // Non-seac endchar: a lone leading operand is the optional glyph width.
        if (!widthSet) {
            widthSet = true
            advanceWidth = if (stack.size >= 1) nominalWidthX + stack.removeFirst() else defaultWidthX
        }
        // Close subpath if any drawing happened.
        if (!pathBuilder.isEmpty()) pathBuilder.close()
        done = true
        stack.clear()
    }

    private fun hint(@Suppress("unused") op: Int) {
        // Each stem is 2 operands. The very first hint/stem operator may carry
        // the optional leading width as an odd extra operand.
        maybeTakeWidthEven()
        hintCount += stack.size / 2
        stack.clear()
    }

    /** Subroutine bias per Type 2 §4.7. */
    private fun subrBias(count: Int): Int = when {
        count < 1240 -> 107
        count < 33900 -> 1131
        else -> 32768
    }
}
