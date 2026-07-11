package io.github.yuroyami.kitepdf.core.text

/**
 * The Unicode Bidirectional Algorithm (UAX #9), implicit subset: it resolves an
 * embedding level per character and reorders a line from logical to visual order,
 * so mixed Latin + Arabic/Hebrew + numbers display correctly. Lives in core so
 * both the EPUB reflow engine and (later) PDF text extraction can share it.
 *
 * Scope: a single paragraph with implicit directionality. Not handled (rare in
 * book text, and each a large addition): explicit embedding/override/isolate
 * controls (LRE/RLE/LRI/…), combining-mark reclassification (W1/NSM), and
 * paired-bracket resolution (N0). Arabic/Hebrew combining marks are folded into
 * their base script's strong class, which reorders acceptably.
 */
public object Bidi {

    // Bidi classes used by the implicit algorithm.
    public const val L: Int = 0
    public const val R: Int = 1
    public const val AL: Int = 2
    public const val EN: Int = 3
    public const val ES: Int = 4
    public const val ET: Int = 5
    public const val AN: Int = 6
    public const val CS: Int = 7
    public const val B: Int = 8
    public const val S: Int = 9
    public const val WS: Int = 10
    public const val ON: Int = 11

    /** Bidi class of a code point (range-based; covers Latin/Hebrew/Arabic/numbers/neutrals). */
    public fun classify(cp: Int): Int = when {
        cp == 0x0A || cp == 0x0D || cp == 0x2029 -> B
        cp == 0x09 || cp == 0x0B -> S
        cp == 0x20 || cp == 0x0C || cp == 0x2028 || cp == 0x3000 || cp in 0x2000..0x200A -> WS
        cp in 0x30..0x39 -> EN
        cp in 0x0660..0x0669 || cp in 0x06F0..0x06F9 -> AN
        cp == 0x2B || cp == 0x2D -> ES
        cp == 0x23 || cp == 0x24 || cp == 0x25 || cp in 0x00A2..0x00A5 || cp == 0x066A -> ET
        cp == 0x2C || cp == 0x2E || cp == 0x2F || cp == 0x3A -> CS
        cp in 0x0590..0x05FF || cp in 0xFB1D..0xFB4F -> R
        cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> AL
        cp < 0x20 -> ON
        else -> L
    }

    /** Base paragraph level: [explicit] 0/1 if forced, else the first strong char (UAX rule P2/P3). */
    public fun baseLevel(cps: IntArray, explicit: Int? = null): Int {
        if (explicit == 0 || explicit == 1) return explicit
        for (cp in cps) when (classify(cp)) {
            L -> return 0
            R, AL -> return 1
        }
        return 0
    }

    /** Resolve an embedding level per code point for one paragraph at [paraLevel]. */
    public fun resolveLevels(cps: IntArray, paraLevel: Int): IntArray {
        val n = cps.size
        val t = IntArray(n) { classify(cps[it]) }
        val e = paraLevel

        // W2: EN → AN when the last strong type is AL.
        var strong = if (e == 1) R else L
        for (i in 0 until n) {
            when (t[i]) { L, R, AL -> strong = t[i] }
            if (t[i] == EN && strong == AL) t[i] = AN
        }
        // W3: AL → R.
        for (i in 0 until n) if (t[i] == AL) t[i] = R
        // W4: a single ES between two EN → EN; a single CS between two like numbers → that number.
        for (i in 1 until n - 1) {
            if (t[i] == ES && t[i - 1] == EN && t[i + 1] == EN) t[i] = EN
            if (t[i] == CS && t[i - 1] == t[i + 1] && (t[i - 1] == EN || t[i - 1] == AN)) t[i] = t[i - 1]
        }
        // W5: a sequence of ET adjacent to EN → EN.
        var i = 0
        while (i < n) {
            if (t[i] == ET) {
                var j = i
                while (j < n && t[j] == ET) j++
                if ((i > 0 && t[i - 1] == EN) || (j < n && t[j] == EN)) for (k in i until j) t[k] = EN
                i = j
            } else i++
        }
        // W6: remaining separators/terminators → ON.
        for (k in 0 until n) if (t[k] == ES || t[k] == ET || t[k] == CS) t[k] = ON
        // W7: EN → L when the last strong type is L.
        strong = if (e == 1) R else L
        for (k in 0 until n) {
            when (t[k]) { L, R -> strong = t[k] }
            if (t[k] == EN && strong == L) t[k] = L
        }
        // N1/N2: resolve neutral runs to a surrounding direction (EN/AN act as R), else the base.
        val baseDir = if (e == 1) R else L
        i = 0
        while (i < n) {
            if (isNeutral(t[i])) {
                var j = i
                while (j < n && isNeutral(t[j])) j++
                val before = if (i > 0) strongDir(t[i - 1]) else baseDir
                val after = if (j < n) strongDir(t[j]) else baseDir
                val fill = if (before == after) before else baseDir
                for (k in i until j) t[k] = fill
                i = j
            } else i++
        }
        // I1/I2: implicit levels.
        val levels = IntArray(n)
        for (k in 0 until n) {
            levels[k] = if (e % 2 == 0) {
                when (t[k]) { R -> e + 1; EN, AN -> e + 2; else -> e }
            } else {
                when (t[k]) { L, EN, AN -> e + 1; else -> e }
            }
        }
        return levels
    }

    /**
     * Reordering rule L2: given per-slot [levels] in logical order, return a
     * permutation `order` where `order[visualSlot]` is the logical index to place
     * there (left to right).
     */
    public fun reorderVisually(levels: IntArray): IntArray {
        val n = levels.size
        val order = IntArray(n) { it }
        if (n == 0) return order
        var maxLevel = 0
        var minOdd = Int.MAX_VALUE
        for (l in levels) { if (l > maxLevel) maxLevel = l; if (l % 2 == 1 && l < minOdd) minOdd = l }
        if (minOdd == Int.MAX_VALUE) return order
        for (level in maxLevel downTo minOdd) {
            var i = 0
            while (i < n) {
                if (levels[order[i]] >= level) {
                    var j = i
                    while (j < n && levels[order[j]] >= level) j++
                    var lo = i; var hi = j - 1
                    while (lo < hi) { val tmp = order[lo]; order[lo] = order[hi]; order[hi] = tmp; lo++; hi-- }
                    i = j
                } else i++
            }
        }
        return order
    }

    private fun isNeutral(t: Int): Boolean = t == B || t == S || t == WS || t == ON
    private fun strongDir(t: Int): Int = when (t) { L -> L; R, EN, AN -> R; else -> L }
}
