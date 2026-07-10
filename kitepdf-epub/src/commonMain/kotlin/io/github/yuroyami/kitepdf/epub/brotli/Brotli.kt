package io.github.yuroyami.kitepdf.epub.brotli

/**
 * Pure-Kotlin Brotli decoder (RFC 7932), complete for the standard stream
 * format: all context modes, block switching, context maps, the static
 * dictionary with all 121 word transforms, uncompressed and metadata
 * meta-blocks. Large-window brotli (window > 16 MiB, a post-RFC extension)
 * is rejected; WOFF2 never uses it.
 *
 * Lives in `:kitepdf-epub` rather than the core module deliberately: the
 * only in-engine consumer is WOFF2 font unpacking, and the ~160 KiB static
 * dictionary constant should not weigh on every core user.
 *
 * Throws [IllegalStateException] on malformed input or when the output
 * would exceed [decode]'s `maxOutputBytes` (decompression-bomb guard).
 */
internal object Brotli {

    fun decode(input: ByteArray, maxOutputBytes: Int = Int.MAX_VALUE): ByteArray {
        val br = BitReader(input)
        val windowBits = decodeWindowBits(br)
        val windowSize = (1 shl windowBits) - 16
        val st = State(br, windowSize, maxOutputBytes)
        while (true) {
            val isLast = br.bit() == 1
            if (isLast && br.bit() == 1) break // ISLASTEMPTY
            val sizeNibbles = br.bits(2)
            if (sizeNibbles == 3) {
                // Metadata meta-block: skip its payload.
                check(br.bit() == 0) { "brotli: reserved bit set" }
                val skipBytes = br.bits(2)
                val skipLen = if (skipBytes == 0) 0 else br.bits(8 * skipBytes) + 1
                br.alignToByte()
                repeat(skipLen) { br.alignedByte() }
                if (isLast) break
                continue
            }
            val mlen = br.bits(4 * (4 + sizeNibbles)) + 1
            if (!isLast && br.bit() == 1) {
                // Uncompressed meta-block: raw bytes, byte-aligned.
                br.alignToByte()
                repeat(mlen) { st.append(br.alignedByte()) }
                continue
            }
            st.decodeCompressedMetaBlock(mlen)
            if (isLast) break
        }
        return st.result()
    }

    private fun decodeWindowBits(br: BitReader): Int {
        if (br.bit() == 0) return 16
        var n = br.bits(3)
        if (n != 0) return 17 + n
        n = br.bits(3)
        check(n != 1) { "brotli: large-window streams not supported" }
        return if (n == 0) 17 else 8 + n
    }

    /* ─── Fixed tables (RFC 7932 sections 4, 5, 6) ───────────────────────── */

    private val INS_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194,
        322, 578, 1090, 2114, 6210, 22594,
    )
    private val INS_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24,
    )
    private val COPY_BASE = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134,
        198, 326, 582, 1094, 2118,
    )
    private val COPY_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24,
    )
    private val INSERT_RANGE_LUT = intArrayOf(0, 0, 8, 8, 0, 16, 8, 16, 16)
    private val COPY_RANGE_LUT = intArrayOf(0, 8, 0, 8, 16, 0, 16, 8, 16)
    private val BLOCK_LEN_BASE = intArrayOf(
        1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241,
        305, 369, 497, 753, 1265, 2289, 4337, 8433, 16625,
    )
    private val BLOCK_LEN_EXTRA = intArrayOf(
        2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24,
    )
    private val CODE_LENGTH_CODE_ORDER = intArrayOf(
        1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    /** Fixed prefix code over the code-length alphabet {0..5}: lengths 2,4,3,2,2,4. */
    private val CODE_LENGTH_META = Huffman(intArrayOf(2, 4, 3, 2, 2, 4))

    /** Deltas for distance short codes 4..9 (off last) and 10..15 (off 2nd last). */
    private val SHORT_DIST_DELTA = intArrayOf(-1, 1, -2, 2, -3, 3)

    /* ─── Bit reader: LSB-first within bytes, codes read MSB of code first ── */

    private class BitReader(val data: ByteArray) {
        private var pos = 0
        private var buf = 0L
        private var cnt = 0

        fun bits(n: Int): Int {
            while (cnt < n) {
                check(pos < data.size) { "brotli: truncated input" }
                buf = buf or ((data[pos++].toLong() and 0xFF) shl cnt)
                cnt += 8
            }
            val v = (buf and ((1L shl n) - 1)).toInt()
            buf = buf ushr n
            cnt -= n
            return v
        }

        fun bit(): Int = bits(1)

        fun alignToByte() {
            val r = cnt and 7
            buf = buf ushr r
            cnt -= r
        }

        /** A whole byte after [alignToByte]; drains buffered bytes first. */
        fun alignedByte(): Int {
            if (cnt >= 8) {
                val v = (buf and 0xFF).toInt()
                buf = buf ushr 8
                cnt -= 8
                return v
            }
            check(pos < data.size) { "brotli: truncated input" }
            return data[pos++].toInt() and 0xFF
        }
    }

    /* ─── Canonical prefix-code decoding ─────────────────────────────────── */

    private class Huffman {
        private val counts = IntArray(16)
        private val symbols: IntArray
        private val single: Int

        constructor(lengths: IntArray) {
            var live = 0
            var last = -1
            for (s in lengths.indices) if (lengths[s] != 0) {
                check(lengths[s] <= 15) { "brotli: code length > 15" }
                counts[lengths[s]]++
                live++
                last = s
            }
            if (live <= 1) {
                single = last
                symbols = IntArray(0)
                return
            }
            single = -1
            // Completeness: sum of 2^(15-len) must be exactly 2^15.
            var space = 1 shl 15
            for (l in 1..15) {
                space -= counts[l] shl (15 - l)
                check(space >= 0) { "brotli: oversubscribed code" }
            }
            check(space == 0) { "brotli: incomplete code" }
            val offsets = IntArray(17)
            for (l in 1..15) offsets[l + 1] = offsets[l] + counts[l]
            symbols = IntArray(live)
            for (s in lengths.indices) if (lengths[s] != 0) symbols[offsets[lengths[s]]++] = s
        }

        fun read(br: BitReader): Int {
            if (single >= 0) return single
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or br.bit()
                val count = counts[len]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            error("brotli: bad prefix code")
        }
    }

    /** Read a prefix-code description (RFC 7932 section 3.4/3.5). */
    private fun readHuffmanCode(alphabetSize: Int, br: BitReader): Huffman {
        val hskip = br.bits(2)
        if (hskip == 1) {
            // Simple code with 1..4 symbols.
            val nsym = br.bits(2) + 1
            var maxBits = 0
            var x = alphabetSize - 1
            while (x > 0) {
                maxBits++
                x = x shr 1
            }
            val syms = IntArray(nsym)
            for (i in 0 until nsym) {
                val s = br.bits(maxBits)
                check(s < alphabetSize) { "brotli: symbol out of range" }
                for (j in 0 until i) check(syms[j] != s) { "brotli: duplicate symbol" }
                syms[i] = s
            }
            val lengths = IntArray(alphabetSize)
            when (nsym) {
                1 -> lengths[syms[0]] = 1 // degenerate; read() consumes no bits
                2 -> {
                    syms.sort()
                    lengths[syms[0]] = 1
                    lengths[syms[1]] = 1
                }
                3 -> {
                    if (syms[1] > syms[2]) {
                        val t = syms[1]; syms[1] = syms[2]; syms[2] = t
                    }
                    lengths[syms[0]] = 1
                    lengths[syms[1]] = 2
                    lengths[syms[2]] = 2
                }
                else -> {
                    if (br.bit() == 1) {
                        if (syms[2] > syms[3]) {
                            val t = syms[2]; syms[2] = syms[3]; syms[3] = t
                        }
                        lengths[syms[0]] = 1
                        lengths[syms[1]] = 2
                        lengths[syms[2]] = 3
                        lengths[syms[3]] = 3
                    } else {
                        syms.sort()
                        for (s in syms) lengths[s] = 2
                    }
                }
            }
            return Huffman(lengths)
        }

        // Complex code: code lengths themselves are prefix-coded.
        val clLengths = IntArray(18)
        var space = 32
        var numCodes = 0
        for (i in hskip until 18) {
            val idx = CODE_LENGTH_CODE_ORDER[i]
            val len = CODE_LENGTH_META.read(br)
            clLengths[idx] = len
            if (len != 0) {
                space -= 32 shr len
                numCodes++
                if (space <= 0) break
            }
        }
        check(numCodes == 1 || space == 0) { "brotli: invalid code-length code" }
        val clTree = Huffman(clLengths)

        val lengths = IntArray(alphabetSize)
        var symbol = 0
        var prevLen = 8
        var repeat = 0
        var repeatLen = 0
        var symSpace = 1 shl 15
        while (symbol < alphabetSize && symSpace > 0) {
            val cl = clTree.read(br)
            if (cl < 16) {
                lengths[symbol++] = cl
                repeat = 0
                if (cl != 0) {
                    prevLen = cl
                    symSpace -= (1 shl 15) shr cl
                }
            } else {
                val extraBits = if (cl == 16) 2 else 3
                val newLen = if (cl == 16) prevLen else 0
                if (repeatLen != newLen) {
                    repeat = 0
                    repeatLen = newLen
                }
                val old = repeat
                if (repeat > 0) repeat = (repeat - 2) shl extraBits
                repeat += br.bits(extraBits) + 3
                val delta = repeat - old
                check(symbol + delta <= alphabetSize) { "brotli: repeat overflows alphabet" }
                for (i in 0 until delta) lengths[symbol++] = repeatLen
                if (repeatLen != 0) symSpace -= delta shl (15 - repeatLen)
            }
        }
        check(symSpace == 0) { "brotli: unbalanced code" }
        return Huffman(lengths)
    }

    /** 1..256 encoded as in RFC 7932 section 9.2 (NBLTYPES / NTREES). */
    private fun decodeVarLenUint8(br: BitReader): Int {
        if (br.bit() == 0) return 0
        val nbits = br.bits(3)
        if (nbits == 0) return 1
        return br.bits(nbits) + (1 shl nbits)
    }

    private fun readContextMap(numTrees: Int, size: Int, br: BitReader): ByteArray {
        val map = ByteArray(size)
        if (numTrees == 1) return map
        var rleMax = 0
        if (br.bit() == 1) rleMax = br.bits(4) + 1
        val tree = readHuffmanCode(numTrees + rleMax, br)
        var i = 0
        while (i < size) {
            val code = tree.read(br)
            when {
                code == 0 -> map[i++] = 0
                code <= rleMax -> {
                    var reps = (1 shl code) + br.bits(code)
                    check(i + reps <= size) { "brotli: context-map run too long" }
                    while (reps-- > 0) map[i++] = 0
                }
                else -> {
                    val v = code - rleMax
                    check(v < numTrees) { "brotli: context-map value out of range" }
                    map[i++] = v.toByte()
                }
            }
        }
        if (br.bit() == 1) {
            // Inverse move-to-front.
            val mtf = ByteArray(256) { it.toByte() }
            for (j in map.indices) {
                val idx = map[j].toInt() and 0xFF
                val v = mtf[idx]
                for (k in idx downTo 1) mtf[k] = mtf[k - 1]
                mtf[0] = v
                map[j] = v
            }
        }
        return map
    }

    /* ─── Per-stream decode state ────────────────────────────────────────── */

    private class State(val br: BitReader, val windowSize: Int, val maxOutput: Int) {
        // Never allocate past the cap, so hitting the buffer end IS the cap check.
        var out = ByteArray(minOf(64 shl 10, maxOutput))
        var pos = 0

        // Distance ring buffer: ring[(idx - k) and 3] = k-th most recent.
        val ring = intArrayOf(16, 15, 11, 4)
        var ringIdx = 4

        fun append(b: Int) {
            if (pos == out.size) grow(pos + 1)
            out[pos++] = b.toByte()
        }

        fun grow(needed: Int) {
            check(needed <= maxOutput) { "brotli: output exceeds $maxOutput bytes" }
            var cap = out.size
            while (cap < needed) cap = if (cap > maxOutput / 2) maxOutput else cap * 2
            out = out.copyOf(cap)
        }

        fun result(): ByteArray = out.copyOf(pos)

        fun ringAt(k: Int) = ring[(ringIdx - k) and 3]

        fun pushRing(d: Int) {
            ring[ringIdx and 3] = d
            ringIdx++
        }

        fun decodeCompressedMetaBlock(metaLen: Int) {
            var remaining = metaLen
            check(maxOutput - pos >= 0) { "brotli: output exceeds $maxOutput bytes" }

            // Block-switch state per category: 0 literals, 1 insert-copy, 2 distances.
            val numTypes = IntArray(3)
            val btype = IntArray(3)
            val btypePrev = intArrayOf(1, 1, 1)
            val blockLen = IntArray(3) { Int.MAX_VALUE }
            val btypeTrees = arrayOfNulls<Huffman>(3)
            val blenTrees = arrayOfNulls<Huffman>(3)
            for (cat in 0..2) {
                numTypes[cat] = decodeVarLenUint8(br) + 1
                if (numTypes[cat] >= 2) {
                    btypeTrees[cat] = readHuffmanCode(numTypes[cat] + 2, br)
                    blenTrees[cat] = readHuffmanCode(26, br)
                    blockLen[cat] = readBlockLength(blenTrees[cat]!!)
                }
            }

            fun switchBlockType(cat: Int) {
                val sym = btypeTrees[cat]!!.read(br)
                val next = when (sym) {
                    0 -> btypePrev[cat]
                    1 -> (btype[cat] + 1) % numTypes[cat]
                    else -> sym - 2
                }
                check(next < numTypes[cat]) { "brotli: block type out of range" }
                btypePrev[cat] = btype[cat]
                btype[cat] = next
                blockLen[cat] = readBlockLength(blenTrees[cat]!!)
            }

            val npostfix = br.bits(2)
            val ndirect = br.bits(4) shl npostfix
            val postfixMask = (1 shl npostfix) - 1

            val cmodes = IntArray(numTypes[0]) { br.bits(2) }

            val ntreesL = decodeVarLenUint8(br) + 1
            val litMap = readContextMap(ntreesL, numTypes[0] shl 6, br)
            val ntreesD = decodeVarLenUint8(br) + 1
            val distMap = readContextMap(ntreesD, numTypes[2] shl 2, br)

            val litTrees = Array(ntreesL) { readHuffmanCode(256, br) }
            val cmdTrees = Array(numTypes[1]) { readHuffmanCode(704, br) }
            val distAlphabet = 16 + ndirect + (48 shl npostfix)
            val distTrees = Array(ntreesD) { readHuffmanCode(distAlphabet, br) }

            val lut = BrotliData.contextLut

            while (remaining > 0) {
                if (blockLen[1] == 0) switchBlockType(1)
                blockLen[1]--
                val cmd = cmdTrees[btype[1]].read(br)
                var rangeIdx = cmd ushr 6
                val implicitDist0 = rangeIdx < 2
                if (rangeIdx >= 2) rangeIdx -= 2
                val insCode = INSERT_RANGE_LUT[rangeIdx] + ((cmd ushr 3) and 7)
                val copyCode = COPY_RANGE_LUT[rangeIdx] + (cmd and 7)
                val insertLen = INS_BASE[insCode] + br.bits(INS_EXTRA[insCode])
                val copyLen = COPY_BASE[copyCode] + br.bits(COPY_EXTRA[copyCode])

                if (insertLen > 0) {
                    if (pos + insertLen > out.size) grow(pos + insertLen)
                    repeat(insertLen) {
                        if (blockLen[0] == 0) switchBlockType(0)
                        blockLen[0]--
                        val mode = cmodes[btype[0]] shl 9
                        val p1 = if (pos > 0) out[pos - 1].toInt() and 0xFF else 0
                        val p2 = if (pos > 1) out[pos - 2].toInt() and 0xFF else 0
                        val ctx = (lut[mode + p1].toInt() or lut[mode + 256 + p2].toInt()) and 0xFF
                        val tree = litTrees[litMap[(btype[0] shl 6) + ctx].toInt() and 0xFF]
                        out[pos++] = tree.read(br).toByte()
                    }
                }
                remaining -= insertLen
                if (remaining <= 0) break

                val maxDistance = if (pos < windowSize) pos else windowSize
                var distance: Int
                var push: Boolean
                if (implicitDist0) {
                    distance = ringAt(1)
                    push = false
                } else {
                    if (blockLen[2] == 0) switchBlockType(2)
                    blockLen[2]--
                    val distCtx = if (copyLen > 4) 3 else copyLen - 2
                    val tree = distTrees[distMap[(btype[2] shl 2) + distCtx].toInt() and 0xFF]
                    val sym = tree.read(br)
                    push = sym != 0
                    distance = when {
                        sym == 0 -> ringAt(1)
                        sym < 4 -> ringAt(sym + 1)
                        sym < 10 -> ringAt(1) + SHORT_DIST_DELTA[sym - 4]
                        sym < 16 -> ringAt(2) + SHORT_DIST_DELTA[sym - 10]
                        sym < 16 + ndirect -> sym - 16 + 1
                        else -> {
                            val v = sym - ndirect - 16
                            val ndistbits = 1 + (v shr (npostfix + 1))
                            val hcode = v shr npostfix
                            val lcode = v and postfixMask
                            val offset = ((2 + (hcode and 1)) shl ndistbits) - 4
                            ((offset + br.bits(ndistbits)) shl npostfix) + lcode + ndirect + 1
                        }
                    }
                    check(distance > 0) { "brotli: non-positive distance" }
                }

                if (distance <= maxDistance) {
                    check(copyLen <= remaining) { "brotli: copy past meta-block end" }
                    if (pos + copyLen > out.size) grow(pos + copyLen)
                    var src = pos - distance
                    repeat(copyLen) { out[pos++] = out[src++] }
                    remaining -= copyLen
                    if (push) pushRing(distance)
                } else {
                    // Static dictionary reference; never pushed to the ring.
                    check(copyLen in 4..24) { "brotli: bad dictionary word length" }
                    val ndbits = BrotliData.SIZE_BITS_BY_LENGTH[copyLen]
                    check(ndbits != 0) { "brotli: bad dictionary word length" }
                    val wordId = distance - maxDistance - 1
                    val wordIdx = wordId and ((1 shl ndbits) - 1)
                    val transformId = wordId ushr ndbits
                    check(transformId < 121) { "brotli: bad dictionary transform" }
                    val word = transformWord(copyLen, wordIdx, transformId)
                    check(word.size <= remaining) { "brotli: dictionary word past meta-block end" }
                    if (pos + word.size > out.size) grow(pos + word.size)
                    word.copyInto(out, pos)
                    pos += word.size
                    remaining -= word.size
                }
            }
            check(remaining == 0) { "brotli: meta-block length mismatch" }
        }

        private fun readBlockLength(tree: Huffman): Int {
            val code = tree.read(br)
            return BLOCK_LEN_BASE[code] + br.bits(BLOCK_LEN_EXTRA[code])
        }
    }

    /* ─── Static dictionary word transforms (RFC 7932 section 8) ─────────── */

    private fun transformWord(len: Int, wordIdx: Int, transformId: Int): ByteArray {
        val dict = BrotliData.dictionary
        val base = BrotliData.OFFSETS_BY_LENGTH[len] + len * wordIdx
        val t = BrotliData.transforms
        val prefixIdx = t[transformId * 3].toInt() and 0xFF
        val type = t[transformId * 3 + 1].toInt() and 0xFF
        val suffixIdx = t[transformId * 3 + 2].toInt() and 0xFF
        // Each prefix/suffix piece is length-prefixed: map[idx] points at a
        // length byte, the piece's bytes follow it.
        val ps = BrotliData.prefixSuffix
        val map = BrotliData.PREFIX_SUFFIX_MAP

        var wordStart = base
        var wordLen = len
        when (type) {
            in 1..9 -> wordLen = maxOf(0, wordLen - type) // omit last N
            in 12..20 -> {
                val omit = minOf(wordLen, type - 11) // omit first N
                wordStart += omit
                wordLen -= omit
            }
        }

        val preLen = ps[map[prefixIdx]].toInt() and 0xFF
        val sufLen = ps[map[suffixIdx]].toInt() and 0xFF
        val outBytes = ByteArray(preLen + wordLen + sufLen)
        ps.copyInto(outBytes, 0, map[prefixIdx] + 1, map[prefixIdx] + 1 + preLen)
        dict.copyInto(outBytes, preLen, wordStart, wordStart + wordLen)
        ps.copyInto(outBytes, preLen + wordLen, map[suffixIdx] + 1, map[suffixIdx] + 1 + sufLen)

        if (type == 10 || type == 11) {
            // Uppercase first / all: RFC 7932's pseudo-UTF-8 fermenting.
            var i = preLen
            val end = preLen + wordLen
            while (i < end) {
                val b = outBytes[i].toInt() and 0xFF
                i += when {
                    b < 192 -> {
                        if (b >= 'a'.code && b <= 'z'.code) {
                            outBytes[i] = (b xor 32).toByte()
                        }
                        1
                    }
                    b < 224 -> {
                        if (i + 1 < end) {
                            outBytes[i + 1] = (outBytes[i + 1].toInt() xor 32).toByte()
                        }
                        2
                    }
                    else -> {
                        if (i + 2 < end) {
                            outBytes[i + 2] = (outBytes[i + 2].toInt() xor 5).toByte()
                        }
                        3
                    }
                }
                if (type == 10) break
            }
        }
        return outBytes
    }
}
