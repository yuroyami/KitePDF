package io.github.yuroyami.kitepdf.core.text

/**
 * The Unicode Bidirectional Algorithm (UAX #9) of Unicode 17 (#320, #323): it resolves an
 * embedding level for each character of a paragraph and reorders a line from logical to
 * visual order, so that mixed Latin, Arabic, Hebrew and numbers display in the right order.
 * It lives in core so that the EPUB layout and PDF text extraction can share it.
 *
 * - [classify] reads the Bidi_Class of a code point.
 * - [baseLevel] finds the level of a paragraph (rules P2 and P3).
 * - [resolveLevels] applies the explicit rules X1 to X10, the weak rules W1 to W7, the paired
 *   brackets of N0, the neutral rules N1 and N2, the implicit rules I1 and I2, and rule L1 to
 *   each line.
 * - [reorderVisually] applies rule L2 to one line.
 *
 * Every test of BidiCharacterTest.txt and BidiTest.txt of Unicode 17 passes.
 */
public object Bidi {

    // The bidi classes, the values of the Bidi_Class property.
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
    public const val NSM: Int = 12
    public const val BN: Int = 13
    public const val LRE: Int = 14
    public const val LRO: Int = 15
    public const val RLE: Int = 16
    public const val RLO: Int = 17
    public const val PDF: Int = 18
    public const val LRI: Int = 19
    public const val RLI: Int = 20
    public const val FSI: Int = 21
    public const val PDI: Int = 22

    /** The Bidi_Class of [cp], from DerivedBidiClass.txt of Unicode 17 with its defaults for unassigned code points. */
    public fun classify(cp: Int): Int {
        val t = classes
        var lo = 0
        var hi = t.size / 3 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < t[mid * 3] -> hi = mid - 1
                cp > t[mid * 3 + 1] -> lo = mid + 1
                else -> return t[mid * 3 + 2]
            }
        }
        return L
    }

    /** Base paragraph level: [explicit] 0/1 if forced, else the first strong character outside an isolate (P2, P3). */
    public fun baseLevel(cps: IntArray, explicit: Int? = null): Int {
        if (explicit == 0 || explicit == 1) return explicit
        return baseLevelOfTypes(IntArray(cps.size) { classify(cps[it]) })
    }

    /**
     * Resolves an embedding level for each code point of one paragraph at [paraLevel], with rule L1
     * for the paragraph as one line. A character that rule X9 removes, such as ZWJ, takes the level
     * of the character before it. When the paragraph breaks into lines, [lineLevels] applies L1 to
     * each of them.
     */
    public fun resolveLevels(cps: IntArray, paraLevel: Int): IntArray =
        resolve(IntArray(cps.size) { classify(cps[it]) }, cps, paraLevel, intArrayOf(cps.size))

    /**
     * Rule L1 for one line whose code points [cps] have the resolved [levels]: a segment or
     * paragraph separator, the whitespace before it and the whitespace at the end of the line
     * take [paraLevel]. Returns the new levels.
     */
    public fun lineLevels(cps: IntArray, levels: IntArray, paraLevel: Int): IntArray {
        val out = levels.copyOf()
        applyL1(IntArray(cps.size) { classify(cps[it]) }, out, paraLevel, 0, cps.size)
        return out
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

    /** The level of a paragraph of the bidi classes [types] (P2, P3). */
    internal fun baseLevelOfTypes(types: IntArray): Int {
        val first = firstStrong(types, matchingPdis(types), 0, types.size)
        return if (first == R || first == AL) 1 else 0
    }

    /** True for the classes that rule X9 removes. */
    internal fun isRemoved(type: Int): Boolean = type == BN || type in LRE..PDF

    /**
     * The levels of a paragraph of the bidi classes [initial] at [paraLevel], with its lines ending
     * before [lineEnds]. [cps] gives the brackets for rule N0, or null to leave N0 out.
     */
    internal fun resolve(initial: IntArray, cps: IntArray?, paraLevel: Int, lineEnds: IntArray): IntArray {
        val n = initial.size
        val types = initial.copyOf()
        val levels = IntArray(n)
        val pdi = matchingPdis(initial)
        explicitLevels(initial, types, levels, pdi, paraLevel)
        // Rules I1 and I2 raise the levels of each sequence, and X10 needs the levels before them.
        val explicit = levels.copyOf()
        // X9: the embedding controls and BN take no part in the rules that follow.
        val kept = IntArray(n)
        var m = 0
        for (i in 0 until n) if (!isRemoved(initial[i])) kept[m++] = i
        val position = IntArray(n) { -1 }
        for (k in 0 until m) position[kept[k]] = k
        for (seq in isolatingRunSequences(kept, m, initial, levels, pdi)) {
            val first = seq[0]
            val last = seq[seq.size - 1]
            val level = explicit[first]
            // X10: the direction of the start and the end of the sequence, from the levels on either side.
            val before = if (position[first] > 0) explicit[kept[position[first] - 1]] else paraLevel
            val after = if (initial[last] in LRI..FSI || position[last] == m - 1) paraLevel else explicit[kept[position[last] + 1]]
            val sos = if (maxOf(level, before) % 2 == 1) R else L
            val eos = if (maxOf(level, after) % 2 == 1) R else L
            resolveSequence(seq, initial, types, levels, cps, sos, eos)
        }
        // A character that X9 removed takes the level of the character before it.
        for (i in 0 until n) if (isRemoved(initial[i])) levels[i] = if (i == 0) paraLevel else levels[i - 1]
        var start = 0
        for (end in lineEnds) {
            applyL1(initial, levels, paraLevel, start, end)
            start = end
        }
        return levels
    }

    /** L1 on the line of the classes [types] from [start] until [end]. */
    private fun applyL1(types: IntArray, levels: IntArray, paraLevel: Int, start: Int, end: Int) {
        var trailing = true
        for (i in end - 1 downTo start) {
            val t = types[i]
            when {
                t == S || t == B -> { levels[i] = paraLevel; trailing = true }
                t == WS || t in LRI..PDI || isRemoved(t) -> if (trailing) levels[i] = paraLevel
                else -> trailing = false
            }
        }
    }

    /** BD9: the index of the matching PDI of each isolate initiator, or -1. */
    private fun matchingPdis(types: IntArray): IntArray {
        val pdi = IntArray(types.size) { -1 }
        val open = IntArray(types.size)
        var depth = 0
        for (i in types.indices) when (types[i]) {
            LRI, RLI, FSI -> open[depth++] = i
            PDI -> if (depth > 0) pdi[open[--depth]] = i
            B -> depth = 0
        }
        return pdi
    }

    /** The first class L, R or AL in [from] until [to], passing over each isolate; ON when there is none. */
    private fun firstStrong(types: IntArray, pdi: IntArray, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            when (types[i]) {
                L, R, AL -> return types[i]
                LRI, RLI, FSI -> if (pdi[i] < 0) return ON else i = pdi[i]
            }
            i++
        }
        return ON
    }

    /** X1 to X8: the explicit embedding level of each character, and the classes that an override changes. */
    private fun explicitLevels(initial: IntArray, types: IntArray, levels: IntArray, pdi: IntArray, paraLevel: Int) {
        val stackLevel = IntArray(MAX_DEPTH + 2)
        val stackOverride = IntArray(MAX_DEPTH + 2)
        val stackIsolate = BooleanArray(MAX_DEPTH + 2)
        var top = 0
        stackLevel[0] = paraLevel
        stackOverride[0] = NEUTRAL
        var overflowIsolates = 0
        var overflowEmbeddings = 0
        var validIsolates = 0
        for (i in initial.indices) {
            val t = initial[i]
            when (t) {
                RLE, LRE, RLO, LRO -> {
                    val next = if (t == RLE || t == RLO) nextOdd(stackLevel[top]) else nextEven(stackLevel[top])
                    if (next <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        top++
                        stackLevel[top] = next
                        stackOverride[top] = when (t) { RLO -> R; LRO -> L; else -> NEUTRAL }
                        stackIsolate[top] = false
                    } else if (overflowIsolates == 0) overflowEmbeddings++
                    levels[i] = stackLevel[top]
                }
                RLI, LRI, FSI -> {
                    levels[i] = stackLevel[top]
                    if (stackOverride[top] != NEUTRAL) types[i] = stackOverride[top]
                    // An FSI takes the direction of the first strong character of its isolate.
                    val rtl = t == RLI || (t == FSI && firstStrong(initial, pdi, i + 1, if (pdi[i] >= 0) pdi[i] else initial.size).let { it == R || it == AL })
                    val next = if (rtl) nextOdd(stackLevel[top]) else nextEven(stackLevel[top])
                    if (next <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        validIsolates++
                        top++
                        stackLevel[top] = next
                        stackOverride[top] = NEUTRAL
                        stackIsolate[top] = true
                    } else overflowIsolates++
                }
                PDI -> {
                    if (overflowIsolates > 0) overflowIsolates--
                    else if (validIsolates > 0) {
                        overflowEmbeddings = 0
                        while (!stackIsolate[top]) top--
                        top--
                        validIsolates--
                    }
                    levels[i] = stackLevel[top]
                    if (stackOverride[top] != NEUTRAL) types[i] = stackOverride[top]
                }
                PDF -> {
                    if (overflowIsolates == 0) {
                        if (overflowEmbeddings > 0) overflowEmbeddings--
                        else if (!stackIsolate[top] && top >= 1) top--
                    }
                    levels[i] = stackLevel[top]
                }
                B -> levels[i] = paraLevel
                BN -> levels[i] = stackLevel[top]
                else -> {
                    levels[i] = stackLevel[top]
                    if (stackOverride[top] != NEUTRAL) types[i] = stackOverride[top]
                }
            }
        }
    }

    private fun nextOdd(level: Int): Int = (level + 1) or 1

    private fun nextEven(level: Int): Int = (level + 2) and 1.inv()

    /**
     * BD13: the isolating run sequences, as the indexes of their characters. A level run that ends
     * with an isolate initiator continues with the level run of its matching PDI.
     */
    private fun isolatingRunSequences(kept: IntArray, m: Int, initial: IntArray, levels: IntArray, pdi: IntArray): List<IntArray> {
        val runStarts = ArrayList<Int>()
        for (k in 0 until m) if (k == 0 || levels[kept[k]] != levels[kept[k - 1]]) runStarts += k
        runStarts += m
        val runOf = HashMap<Int, Int>()
        for (r in 0 until runStarts.size - 1) runOf[kept[runStarts[r]]] = r
        val used = BooleanArray(runStarts.size)
        val sequences = ArrayList<IntArray>()
        for (r in 0 until runStarts.size - 1) {
            if (used[r]) continue
            val seq = ArrayList<Int>()
            var run = r
            while (true) {
                used[run] = true
                for (k in runStarts[run] until runStarts[run + 1]) seq += kept[k]
                val last = kept[runStarts[run + 1] - 1]
                val next = if (initial[last] in LRI..FSI && pdi[last] >= 0) runOf[pdi[last]] else null
                if (next == null || used[next]) break
                run = next
            }
            sequences += seq.toIntArray()
        }
        return sequences
    }

    /** W1 to W7, N0 to N2, I1 and I2 on one isolating run sequence [seq]. */
    private fun resolveSequence(seq: IntArray, initial: IntArray, types: IntArray, levels: IntArray, cps: IntArray?, sos: Int, eos: Int) {
        val n = seq.size
        val embedding = if (levels[seq[0]] % 2 == 1) R else L
        // W1: a nonspacing mark takes the class of the character before it, or ON after an isolate control.
        for (k in 0 until n) if (types[seq[k]] == NSM) {
            types[seq[k]] = if (k == 0) sos else types[seq[k - 1]].let { if (it in LRI..PDI) ON else it }
        }
        // W2: a European number after Arabic letters is an Arabic number.
        var strong = sos
        for (i in seq) when (types[i]) {
            L, R, AL -> strong = types[i]
            EN -> if (strong == AL) types[i] = AN
        }
        // W3
        for (i in seq) if (types[i] == AL) types[i] = R
        // W4: one separator between two numbers of one kind joins them.
        for (k in 1 until n - 1) {
            val t = types[seq[k]]
            val prev = types[seq[k - 1]]
            val next = types[seq[k + 1]]
            if ((t == ES || t == CS) && prev == EN && next == EN) types[seq[k]] = EN
            else if (t == CS && prev == AN && next == AN) types[seq[k]] = AN
        }
        // W5: terminators next to a European number are European numbers.
        var k = 0
        while (k < n) {
            if (types[seq[k]] != ET) { k++; continue }
            var j = k
            while (j < n && types[seq[j]] == ET) j++
            if ((k > 0 && types[seq[k - 1]] == EN) || (j < n && types[seq[j]] == EN)) for (x in k until j) types[seq[x]] = EN
            k = j
        }
        // W6
        for (i in seq) if (types[i] == ES || types[i] == ET || types[i] == CS) types[i] = ON
        // W7: a European number after left-to-right text is left to right.
        strong = sos
        for (i in seq) when (types[i]) {
            L, R -> strong = types[i]
            EN -> if (strong == L) types[i] = L
        }
        if (cps != null) resolveBrackets(seq, initial, types, cps, embedding, sos)
        // N1 and N2: a run of neutrals takes the direction around it when both sides agree, else the embedding direction.
        k = 0
        while (k < n) {
            if (!isNeutral(types[seq[k]])) { k++; continue }
            var j = k
            while (j < n && isNeutral(types[seq[j]])) j++
            val before = if (k == 0) sos else direction(types[seq[k - 1]])
            val after = if (j == n) eos else direction(types[seq[j]])
            val fill = if (before == after) before else embedding
            for (x in k until j) types[seq[x]] = fill
            k = j
        }
        // I1 and I2
        for (i in seq) {
            val t = types[i]
            if (levels[i] % 2 == 0) {
                if (t == R) levels[i] += 1 else if (t == AN || t == EN) levels[i] += 2
            } else if (t == L || t == EN || t == AN) levels[i] += 1
        }
    }

    /**
     * N0: a pair of brackets takes the embedding direction when the text inside it has that
     * direction. When the text inside has only the other direction, the pair takes the direction
     * of the text before it. A nonspacing mark after a bracket follows the bracket.
     */
    private fun resolveBrackets(seq: IntArray, initial: IntArray, types: IntArray, cps: IntArray, embedding: Int, sos: Int) {
        // BD16: the pairs, as positions in seq, found with a stack of 63 opening brackets.
        val openers = IntArray(MAX_BRACKETS)
        val closers = IntArray(MAX_BRACKETS)
        var depth = 0
        val pairs = ArrayList<Long>()
        for (k in seq.indices) {
            val i = seq[k]
            if (types[i] != ON) continue
            val pair = bracketPairs[cps[i]] ?: continue
            if (cps[i] in openingBrackets) {
                if (depth == MAX_BRACKETS) break
                openers[depth] = k
                closers[depth] = canonicalBracket(pair)
                depth++
            } else {
                val closer = canonicalBracket(cps[i])
                var d = depth - 1
                while (d >= 0 && closers[d] != closer) d--
                if (d < 0) continue
                pairs += (openers[d].toLong() shl 32) or k.toLong()
                depth = d
            }
        }
        pairs.sort()
        for (p in pairs) {
            val open = (p ushr 32).toInt()
            val close = p.toInt()
            var inside = NEUTRAL
            for (x in open + 1 until close) {
                val d = direction(types[seq[x]])
                if (d == embedding) { inside = embedding; break }
                if (d != NEUTRAL) inside = d
            }
            if (inside == NEUTRAL) continue
            val fill = if (inside == embedding) embedding else {
                var context = sos
                for (x in open - 1 downTo 0) {
                    val d = direction(types[seq[x]])
                    if (d != NEUTRAL) { context = d; break }
                }
                if (context == inside) inside else embedding
            }
            for (at in intArrayOf(open, close)) {
                types[seq[at]] = fill
                var x = at + 1
                while (x < seq.size && initial[seq[x]] == NSM) types[seq[x++]] = fill
            }
        }
    }

    /** The strong direction of a resolved class, with numbers as right to left, or [NEUTRAL]. */
    private fun direction(type: Int): Int = when (type) {
        L -> L
        R, AL, EN, AN -> R
        else -> NEUTRAL
    }

    /** True for a neutral or an isolate control, which rules N1 and N2 resolve. */
    private fun isNeutral(type: Int): Boolean = type == B || type == S || type == WS || type == ON || type in LRI..PDI

    /** U+2329 and U+232A match U+3008 and U+3009, their canonical equivalents. */
    private fun canonicalBracket(cp: Int): Int = when (cp) {
        0x2329 -> 0x3008
        0x232A -> 0x3009
        else -> cp
    }

    private const val MAX_DEPTH = 125
    private const val MAX_BRACKETS = 63
    private const val NEUTRAL = -1

    /** The first character, last character and class of each run of [CLASSES]. */
    private val classes: IntArray by lazy {
        IntArray(CLASSES.length / 14 * 3) { k ->
            val at = k / 3 * 14
            when (k % 3) {
                0 -> CLASSES.substring(at, at + 6).toInt(16)
                1 -> CLASSES.substring(at + 6, at + 12).toInt(16)
                else -> CLASSES.substring(at + 12, at + 14).toInt(16)
            }
        }
    }

    /** The paired bracket of each bracket of [BRACKETS]. */
    private val bracketPairs: Map<Int, Int> by lazy {
        HashMap<Int, Int>().apply {
            for (r in 0 until BRACKETS.length / 13) put(BRACKETS.substring(r * 13, r * 13 + 6).toInt(16), BRACKETS.substring(r * 13 + 6, r * 13 + 12).toInt(16))
        }
    }

    /** The opening brackets of [BRACKETS]. */
    private val openingBrackets: Set<Int> by lazy {
        (0 until BRACKETS.length / 13).filter { BRACKETS[it * 13 + 12] == 'o' }.map { BRACKETS.substring(it * 13, it * 13 + 6).toInt(16) }.toSet()
    }

    /**
     * Every code point whose class is not L, in runs of one class: the first and last code point
     * in six hex digits each, and the class in two. Generated from DerivedBidiClass.txt of
     * Unicode 17, whose defaults give unassigned code points in right-to-left blocks their class.
     */
    private const val CLASSES =
        "0000000000080D0000090000090900000A00000A0800000B00000B0900000C00000C0A00000D00000D0800000E00001B0D00001C00001E0800001F00" +
        "001F090000200000200A0000210000220B0000230000250500002600002A0B00002B00002B0400002C00002C0700002D00002D0400002E00002F0700" +
        "00300000390300003A00003A0700003B0000400B00005B0000600B00007B00007E0B00007F0000840D0000850000850800008600009F0D0000A00000" +
        "A0070000A10000A10B0000A20000A5050000A60000A90B0000AB0000AC0B0000AD0000AD0D0000AE0000AF0B0000B00000B1050000B20000B3030000" +
        "B40000B40B0000B60000B80B0000B90000B9030000BB0000BF0B0000D70000D70B0000F70000F70B0002B90002BA0B0002C20002CF0B0002D20002DF" +
        "0B0002E50002ED0B0002EF0002FF0B00030000036F0C0003740003750B00037E00037E0B0003840003850B0003870003870B0003F60003F60B000483" +
        "0004890C00058A00058A0B00058D00058E0B00058F00058F05000590000590010005910005BD0C0005BE0005BE010005BF0005BF0C0005C00005C001" +
        "0005C10005C20C0005C30005C3010005C40005C50C0005C60005C6010005C70005C70C0005C80005FF01000600000605060006060006070B00060800" +
        "06080200060900060A0500060B00060B0200060C00060C0700060D00060D0200060E00060F0B00061000061A0C00061B00064A0200064B00065F0C00" +
        "06600006690600066A00066A0500066B00066C0600066D00066F020006700006700C0006710006D5020006D60006DC0C0006DD0006DD060006DE0006" +
        "DE0B0006DF0006E40C0006E50006E6020006E70006E80C0006E90006E90B0006EA0006ED0C0006EE0006EF020006F00006F9030006FA000710020007" +
        "110007110C00071200072F0200073000074A0C00074B0007A5020007A60007B00C0007B10007BF020007C00007EA010007EB0007F30C0007F40007F5" +
        "010007F60007F90B0007FA0007FC010007FD0007FD0C0007FE000815010008160008190C00081A00081A0100081B0008230C00082400082401000825" +
        "0008270C0008280008280100082900082D0C00082E0008580100085900085B0C00085C00085F0100086000088F020008900008910600089200089602" +
        "00089700089F0C0008A00008C9020008CA0008E10C0008E20008E2060008E30009020C00093A00093A0C00093C00093C0C0009410009480C00094D00" +
        "094D0C0009510009570C0009620009630C0009810009810C0009BC0009BC0C0009C10009C40C0009CD0009CD0C0009E20009E30C0009F20009F30500" +
        "09FB0009FB050009FE0009FE0C000A01000A020C000A3C000A3C0C000A41000A420C000A47000A480C000A4B000A4D0C000A51000A510C000A70000A" +
        "710C000A75000A750C000A81000A820C000ABC000ABC0C000AC1000AC50C000AC7000AC80C000ACD000ACD0C000AE2000AE30C000AF1000AF105000A" +
        "FA000AFF0C000B01000B010C000B3C000B3C0C000B3F000B3F0C000B41000B440C000B4D000B4D0C000B55000B560C000B62000B630C000B82000B82" +
        "0C000BC0000BC00C000BCD000BCD0C000BF3000BF80B000BF9000BF905000BFA000BFA0B000C00000C000C000C04000C040C000C3C000C3C0C000C3E" +
        "000C400C000C46000C480C000C4A000C4D0C000C55000C560C000C62000C630C000C78000C7E0B000C81000C810C000CBC000CBC0C000CCC000CCD0C" +
        "000CE2000CE30C000D00000D010C000D3B000D3C0C000D41000D440C000D4D000D4D0C000D62000D630C000D81000D810C000DCA000DCA0C000DD200" +
        "0DD40C000DD6000DD60C000E31000E310C000E34000E3A0C000E3F000E3F05000E47000E4E0C000EB1000EB10C000EB4000EBC0C000EC8000ECE0C00" +
        "0F18000F190C000F35000F350C000F37000F370C000F39000F390C000F3A000F3D0B000F71000F7E0C000F80000F840C000F86000F870C000F8D000F" +
        "970C000F99000FBC0C000FC6000FC60C00102D0010300C0010320010370C00103900103A0C00103D00103E0C0010580010590C00105E0010600C0010" +
        "710010740C0010820010820C0010850010860C00108D00108D0C00109D00109D0C00135D00135F0C0013900013990B0014000014000B001680001680" +
        "0A00169B00169C0B0017120017140C0017320017330C0017520017530C0017720017730C0017B40017B50C0017B70017BD0C0017C60017C60C0017C9" +
        "0017D30C0017DB0017DB050017DD0017DD0C0017F00017F90B00180000180A0B00180B00180D0C00180E00180E0D00180F00180F0C0018850018860C" +
        "0018A90018A90C0019200019220C0019270019280C0019320019320C00193900193B0C0019400019400B0019440019450B0019DE0019FF0B001A1700" +
        "1A180C001A1B001A1B0C001A56001A560C001A58001A5E0C001A60001A600C001A62001A620C001A65001A6C0C001A73001A7C0C001A7F001A7F0C00" +
        "1AB0001ADD0C001AE0001AEB0C001B00001B030C001B34001B340C001B36001B3A0C001B3C001B3C0C001B42001B420C001B6B001B730C001B80001B" +
        "810C001BA2001BA50C001BA8001BA90C001BAB001BAD0C001BE6001BE60C001BE8001BE90C001BED001BED0C001BEF001BF10C001C2C001C330C001C" +
        "36001C370C001CD0001CD20C001CD4001CE00C001CE2001CE80C001CED001CED0C001CF4001CF40C001CF8001CF90C001DC0001DFF0C001FBD001FBD" +
        "0B001FBF001FC10B001FCD001FCF0B001FDD001FDF0B001FED001FEF0B001FFD001FFE0B00200000200A0A00200B00200D0D00200F00200F01002010" +
        "0020270B0020280020280A0020290020290800202A00202A0E00202B00202B1000202C00202C1200202D00202D0F00202E00202E1100202F00202F07" +
        "002030002034050020350020430B0020440020440700204500205E0B00205F00205F0A0020600020650D002066002066130020670020671400206800" +
        "2068150020690020691600206A00206F0D002070002070030020740020790300207A00207B0400207C00207E0B0020800020890300208A00208B0400" +
        "208C00208E0B0020A00020CF050020D00020F00C0021000021010B0021030021060B0021080021090B0021140021140B0021160021180B00211E0021" +
        "230B0021250021250B0021270021270B0021290021290B00212E00212E0500213A00213B0B0021400021440B00214A00214D0B00215000215F0B0021" +
        "8900218B0B0021900022110B00221200221204002213002213050022140023350B00237B0023940B0023960024290B00244000244A0B002460002487" +
        "0B00248800249B030024EA0026AB0B0026AD0027FF0B002900002B730B002B76002BFF0B002CE5002CEA0B002CEF002CF10C002CF9002CFF0B002D7F" +
        "002D7F0C002DE0002DFF0C002E00002E5D0B002E80002E990B002E9B002EF30B002F00002FD50B002FF0002FFF0B0030000030000A0030010030040B" +
        "0030080030200B00302A00302D0C0030300030300B0030360030370B00303D00303F0B00309900309A0C00309B00309C0B0030A00030A00B0030FB00" +
        "30FB0B0031C00031E50B0031EF0031EF0B00321D00321E0B00325000325F0B00327C00327E0B0032B10032BF0B0032CC0032CF0B00337700337A0B00" +
        "33DE0033DF0B0033FF0033FF0B004DC0004DFF0B00A49000A4C60B00A60D00A60F0B00A66F00A6720C00A67300A6730B00A67400A67D0C00A67E00A6" +
        "7F0B00A69E00A69F0C00A6F000A6F10C00A70000A7210B00A78800A7880B00A80200A8020C00A80600A8060C00A80B00A80B0C00A82500A8260C00A8" +
        "2800A82B0B00A82C00A82C0C00A83800A8390500A87400A8770B00A8C400A8C50C00A8E000A8F10C00A8FF00A8FF0C00A92600A92D0C00A94700A951" +
        "0C00A98000A9820C00A9B300A9B30C00A9B600A9B90C00A9BC00A9BD0C00A9E500A9E50C00AA2900AA2E0C00AA3100AA320C00AA3500AA360C00AA43" +
        "00AA430C00AA4C00AA4C0C00AA7C00AA7C0C00AAB000AAB00C00AAB200AAB40C00AAB700AAB80C00AABE00AABF0C00AAC100AAC10C00AAEC00AAED0C" +
        "00AAF600AAF60C00AB6A00AB6B0B00ABE500ABE50C00ABE800ABE80C00ABED00ABED0C00FB1D00FB1D0100FB1E00FB1E0C00FB1F00FB280100FB2900" +
        "FB290400FB2A00FB4F0100FB5000FBC20200FBC300FBD20B00FBD300FD3D0200FD3E00FD4F0B00FD5000FD8F0200FD9000FD910B00FD9200FDC70200" +
        "FDC800FDCF0B00FDD000FDEF0D00FDF000FDFC0200FDFD00FDFF0B00FE0000FE0F0C00FE1000FE190B00FE2000FE2F0C00FE3000FE4F0B00FE5000FE" +
        "500700FE5100FE510B00FE5200FE520700FE5400FE540B00FE5500FE550700FE5600FE5E0B00FE5F00FE5F0500FE6000FE610B00FE6200FE630400FE" +
        "6400FE660B00FE6800FE680B00FE6900FE6A0500FE6B00FE6B0B00FE7000FEFE0200FEFF00FEFF0D00FF0100FF020B00FF0300FF050500FF0600FF0A" +
        "0B00FF0B00FF0B0400FF0C00FF0C0700FF0D00FF0D0400FF0E00FF0F0700FF1000FF190300FF1A00FF1A0700FF1B00FF200B00FF3B00FF400B00FF5B" +
        "00FF650B00FFE000FFE10500FFE200FFE40B00FFE500FFE60500FFE800FFEE0B00FFF000FFF80D00FFF900FFFD0B00FFFE00FFFF0D0101010101010B" +
        "01014001018C0B01019001019C0B0101A00101A00B0101FD0101FD0C0102E00102E00C0102E10102FB0301037601037A0C01080001091E0101091F01" +
        "091F0B010920010A0001010A01010A030C010A04010A0401010A05010A060C010A07010A0B01010A0C010A0F0C010A10010A3701010A38010A3A0C01" +
        "0A3B010A3E01010A3F010A3F0C010A40010AE401010AE5010AE60C010AE7010B3801010B39010B3F0B010B40010CFF01010D00010D2302010D24010D" +
        "270C010D28010D2F02010D30010D3906010D3A010D3F02010D40010D4906010D4A010D6801010D69010D6D0C010D6E010D6E0B010D6F010E5F01010E" +
        "60010E7E06010E7F010EAA01010EAB010EAC0C010EAD010EBF01010EC0010ECF02010ED0010ED80B010ED9010EF902010EFA010EFF0C010F00010F2F" +
        "01010F30010F4502010F46010F500C010F51010F6F02010F70010F8101010F82010F850C010F86010FFF010110010110010C0110380110460C011052" +
        "0110650B0110700110700C0110730110740C01107F0110810C0110B30110B60C0110B90110BA0C0110C20110C20C0111000111020C01112701112B0C" +
        "01112D0111340C0111730111730C0111800111810C0111B60111BE0C0111C90111CC0C0111CF0111CF0C01122F0112310C0112340112340C01123601" +
        "12370C01123E01123E0C0112410112410C0112DF0112DF0C0112E30112EA0C0113000113010C01133B01133C0C0113400113400C01136601136C0C01" +
        "13700113740C0113BB0113C00C0113CE0113CE0C0113D00113D00C0113D20113D20C0113E10113E20C01143801143F0C0114420114440C0114460114" +
        "460C01145E01145E0C0114B30114B80C0114BA0114BA0C0114BF0114C00C0114C20114C30C0115B20115B50C0115BC0115BD0C0115BF0115C00C0115" +
        "DC0115DD0C01163301163A0C01163D01163D0C01163F0116400C01166001166C0B0116AB0116AB0C0116AD0116AD0C0116B00116B50C0116B70116B7" +
        "0C01171D01171D0C01171F01171F0C0117220117250C01172701172B0C01182F0118370C01183901183A0C01193B01193C0C01193E01193E0C011943" +
        "0119430C0119D40119D70C0119DA0119DB0C0119E00119E00C011A01011A060C011A09011A0A0C011A33011A380C011A3B011A3E0C011A47011A470C" +
        "011A51011A560C011A59011A5B0C011A8A011A960C011A98011A990C011B60011B600C011B62011B640C011B66011B660C011C30011C360C011C3801" +
        "1C3D0C011C92011CA70C011CAA011CB00C011CB2011CB30C011CB5011CB60C011D31011D360C011D3A011D3A0C011D3C011D3D0C011D3F011D450C01" +
        "1D47011D470C011D90011D910C011D95011D950C011D97011D970C011EF3011EF40C011F00011F010C011F36011F3A0C011F40011F400C011F42011F" +
        "420C011F5A011F5A0C011FD5011FDC0B011FDD011FE005011FE1011FF10B0134400134400C0134470134550C01611E0161290C01612D01612F0C016A" +
        "F0016AF40C016B30016B360C016F4F016F4F0C016F8F016F920C016FE2016FE20B016FE4016FE40C01BC9D01BC9E0C01BCA001BCA30D01CC0001CCD5" +
        "0B01CCF001CCF90301CCFA01CCFC0B01CD0001CEB30B01CEBA01CED00B01CEE001CEF00B01CF0001CF2D0C01CF3001CF460C01D16701D1690C01D173" +
        "01D17A0D01D17B01D1820C01D18501D18B0C01D1AA01D1AD0C01D1E901D1EA0B01D20001D2410B01D24201D2440C01D24501D2450B01D30001D3560B" +
        "01D6C101D6C10B01D6DB01D6DB0B01D6FB01D6FB0B01D71501D7150B01D73501D7350B01D74F01D74F0B01D76F01D76F0B01D78901D7890B01D7A901" +
        "D7A90B01D7C301D7C30B01D7CE01D7FF0301DA0001DA360C01DA3B01DA6C0C01DA7501DA750C01DA8401DA840C01DA9B01DA9F0C01DAA101DAAF0C01" +
        "E00001E0060C01E00801E0180C01E01B01E0210C01E02301E0240C01E02601E02A0C01E08F01E08F0C01E13001E1360C01E2AE01E2AE0C01E2EC01E2" +
        "EF0C01E2FF01E2FF0501E4EC01E4EF0C01E5EE01E5EF0C01E6E301E6E30C01E6E601E6E60C01E6EE01E6EF0C01E6F501E6F50C01E80001E8CF0101E8" +
        "D001E8D60C01E8D701E9430101E94401E94A0C01E94B01EC6F0101EC7001ECBF0201ECC001ECFF0101ED0001ED4F0201ED5001EDFF0101EE0001EEEF" +
        "0201EEF001EEF10B01EEF201EEFF0201EF0001EFFF0101F00001F02B0B01F03001F0930B01F0A001F0AE0B01F0B101F0BF0B01F0C101F0CF0B01F0D1" +
        "01F0F50B01F10001F10A0301F10B01F10F0B01F12F01F12F0B01F16A01F16F0B01F1AD01F1AD0B01F26001F2650B01F30001F6D80B01F6DC01F6EC0B" +
        "01F6F001F6FC0B01F70001F7D90B01F7E001F7EB0B01F7F001F7F00B01F80001F80B0B01F81001F8470B01F85001F8590B01F86001F8870B01F89001" +
        "F8AD0B01F8B001F8BB0B01F8C001F8C10B01F8D001F8D80B01F90001FA570B01FA6001FA6D0B01FA7001FA7C0B01FA8001FA8A0B01FA8E01FAC60B01" +
        "FAC801FAC80B01FACD01FADC0B01FADF01FAEA0B01FAEF01FAF80B01FB0001FB920B01FB9401FBEF0B01FBF001FBF90301FBFA01FBFA0B01FFFE01FF" +
        "FF0D02FFFE02FFFF0D03FFFE03FFFF0D04FFFE04FFFF0D05FFFE05FFFF0D06FFFE06FFFF0D07FFFE07FFFF0D08FFFE08FFFF0D09FFFE09FFFF0D0AFF" +
        "FE0AFFFF0D0BFFFE0BFFFF0D0CFFFE0CFFFF0D0DFFFE0E00FF0D0E01000E01EF0C0E01F00E0FFF0D0EFFFE0EFFFF0D0FFFFE0FFFFF0D10FFFE10FFFF" +
        "0D"

    /**
     * Each paired bracket: the bracket and its pair in six hex digits each, then `o` for an
     * opening bracket and `c` for a closing one. From BidiBrackets.txt of Unicode 17.
     */
    private const val BRACKETS =
        "000028000029o000029000028c00005B00005Do00005D00005Bc00007B00007Do00007D00007Bc000F3A000F3Bo000F3B000F3Ac000F3C000F3Do000" +
        "F3D000F3Cc00169B00169Co00169C00169Bc002045002046o002046002045c00207D00207Eo00207E00207Dc00208D00208Eo00208E00208Dc002308" +
        "002309o002309002308c00230A00230Bo00230B00230Ac00232900232Ao00232A002329c002768002769o002769002768c00276A00276Bo00276B002" +
        "76Ac00276C00276Do00276D00276Cc00276E00276Fo00276F00276Ec002770002771o002771002770c002772002773o002773002772c002774002775" +
        "o002775002774c0027C50027C6o0027C60027C5c0027E60027E7o0027E70027E6c0027E80027E9o0027E90027E8c0027EA0027EBo0027EB0027EAc00" +
        "27EC0027EDo0027ED0027ECc0027EE0027EFo0027EF0027EEc002983002984o002984002983c002985002986o002986002985c002987002988o00298" +
        "8002987c00298900298Ao00298A002989c00298B00298Co00298C00298Bc00298D002990o00298E00298Fc00298F00298Eo00299000298Dc00299100" +
        "2992o002992002991c002993002994o002994002993c002995002996o002996002995c002997002998o002998002997c0029D80029D9o0029D90029D" +
        "8c0029DA0029DBo0029DB0029DAc0029FC0029FDo0029FD0029FCc002E22002E23o002E23002E22c002E24002E25o002E25002E24c002E26002E27o0" +
        "02E27002E26c002E28002E29o002E29002E28c002E55002E56o002E56002E55c002E57002E58o002E58002E57c002E59002E5Ao002E5A002E59c002E" +
        "5B002E5Co002E5C002E5Bc003008003009o003009003008c00300A00300Bo00300B00300Ac00300C00300Do00300D00300Cc00300E00300Fo00300F0" +
        "0300Ec003010003011o003011003010c003014003015o003015003014c003016003017o003017003016c003018003019o003019003018c00301A0030" +
        "1Bo00301B00301Ac00FE5900FE5Ao00FE5A00FE59c00FE5B00FE5Co00FE5C00FE5Bc00FE5D00FE5Eo00FE5E00FE5Dc00FF0800FF09o00FF0900FF08c" +
        "00FF3B00FF3Do00FF3D00FF3Bc00FF5B00FF5Do00FF5D00FF5Bc00FF5F00FF60o00FF6000FF5Fc00FF6200FF63o00FF6300FF62c"
}
