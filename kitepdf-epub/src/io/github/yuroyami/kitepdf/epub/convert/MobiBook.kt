package io.github.yuroyami.kitepdf.epub.convert

import io.github.yuroyami.kitepdf.core.compression.Inflate
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.text.TextEncoding
import io.github.yuroyami.kitepdf.epub.EpubFormatException

/**
 * A Kindle book without DRM (#948), a `.mobi` or `.azw3`, as an EPUB package.
 *
 * The file is a Palm database of type `BOOKMOBI`. Record 0 holds the PalmDOC header, the MOBI
 * header and the EXTH metadata. The text records that follow are PalmDOC (LZ77) or HUFF/CDIC
 * compressed, each with trailing entries that `extra_data_flags` declare.
 *
 * - **KF8** (AZW3, and the KF8 half of a joint file that EXTH 121 points to): the text is the raw
 *   markup of every part. The `FDST` record cuts it into flows, the skeleton and fragment indexes
 *   rebuild each XHTML part from flow 0, and `kindle:embed`, `kindle:flow` and `kindle:pos`
 *   references become files and anchors of the package. The NCX index is the table of contents.
 * - **MOBI 6**: one HTML text, cut into chapters at its page breaks. A `filepos` link goes to an
 *   anchor placed at that byte of the text, and a `recindex` image to its image record.
 *
 * There is no public specification. This follows the MobileRead wiki's pages on MOBI, EXTH and
 * KF8, and was checked against Calibre's output. Lenient as the other readers: a broken index or
 * resource is left out with a warning, and only a missing text fails the book. A book with DRM
 * fails with a message that says so; nothing is decrypted.
 */
internal object MobiBook {

    /** Whether [bytes] are a Palm database of type and creator `BOOKMOBI`. */
    fun isMobi(bytes: ByteArray): Boolean =
        bytes.size >= 78 && ascii(bytes, 60, 8) == "BOOKMOBI"

    fun convert(bytes: ByteArray): ByteArray {
        if (!isMobi(bytes)) throw EpubFormatException("not a MOBI or AZW3 book: no BOOKMOBI database header")
        val pdb = Pdb(bytes)
        val first = Header(pdb.rec(0))
        first.checkReadable()
        // A joint file: the MOBI 6 book, a BOUNDARY record, then the KF8 book whose record 0 is EXTH 121.
        val kf8Base = first.exthInt(121)?.takeIf { b ->
            b in 1 until pdb.count && ascii(pdb.rec(b - 1), 0, 8) == "BOUNDARY" && Header(pdb.rec(b)).version >= 8
        }
        return when {
            first.version >= 8 -> Kf8(pdb, 0, first).convert()
            kf8Base != null -> Header(pdb.rec(kf8Base)).let { h ->
                h.checkReadable()
                Kf8(pdb, kf8Base, h, first).convert()
            }
            else -> Mobi6(pdb, first).convert()
        }
    }

    // ---- The database ----

    private class Pdb(val b: ByteArray) {
        val count: Int = u16(b, 76)
        private val starts = IntArray(count + 1)

        init {
            if (78 + count * 8 > b.size) throw EpubFormatException("truncated MOBI record list: $count records")
            for (i in 0 until count) starts[i] = s32(b, 78 + i * 8).coerceIn(0, b.size)
            starts[count] = b.size
        }

        /** Record [i], or an empty array when there is none. */
        fun rec(i: Int): ByteArray {
            if (i !in 0 until count) return ByteArray(0)
            val start = starts[i]
            val end = starts[i + 1].let { if (it >= start) it else b.size }
            return b.copyOfRange(start, end)
        }
    }

    /** Record 0 of a book: the PalmDOC header at 0, the MOBI header at 16 and the EXTH after it. */
    private class Header(val r: ByteArray) {
        val compression = u16(r, 0)
        val textRecords = u16(r, 8)
        val encryption = u16(r, 12)
        val hasMobi = ascii(r, 16, 4) == "MOBI"
        val length = if (hasMobi) s32(r, 20) else 0
        val version = if (hasMobi) field(0x24) else 0
        val encoding = if (hasMobi) field(0x1C) else 1252
        val firstImage = field(0x6C)
        val huffRecord = field(0x70)
        val huffCount = field(0x74)
        val extraFlags = if (hasMobi && length >= 0xE4 && r.size >= 0xF4) u16(r, 0xF2) else 0
        val fdst = field(0xC0)
        val ncx = field(0xF4)
        val fragments = field(0xF8)
        val skeletons = field(0xFC)
        private val exth = HashMap<Int, MutableList<ByteArray>>()

        init {
            if (hasMobi && (field(0x80) and 0x40) != 0) {
                val p = 16 + length
                if (ascii(r, p, 4) == "EXTH" && p + 12 <= r.size) {
                    val n = s32(r, p + 8)
                    var q = p + 12
                    for (k in 0 until n) {
                        if (q + 8 > r.size) break
                        val type = s32(r, q)
                        val len = s32(r, q + 4)
                        if (len < 8 || q + len > r.size) break
                        exth.getOrPut(type) { ArrayList() } += r.copyOfRange(q + 8, q + len)
                        q += len
                    }
                }
            }
        }

        /** A 32-bit field of the MOBI header at [offset] from record 0, or -1 past its end. */
        fun field(offset: Int): Int = if (hasMobi && offset + 4 <= 16 + length && offset + 4 <= r.size) s32(r, offset) else -1

        fun exth(type: Int): List<ByteArray> = exth[type].orEmpty()
        fun exthInt(type: Int): Int? = exth(type).firstOrNull()?.takeIf { it.size == 4 }?.let { s32(it, 0) }
        fun exthText(type: Int): List<String> = exth(type).map { decode(it).trim() }.filter { it.isNotEmpty() }

        fun decode(bytes: ByteArray): String =
            if (encoding == 65001) bytes.decodeToString() else TextEncoding.decode(bytes, "windows-1252")

        val title: String
            get() = exthText(503).firstOrNull() ?: run {
                val off = field(0x54)
                val len = field(0x58)
                if (off > 0 && len > 0 && off + len <= r.size) decode(r.copyOfRange(off, off + len)).trim() else ""
            }.ifEmpty { "Untitled" }

        fun checkReadable() {
            if (encryption != 0) {
                val kind = if (encryption == 1) "the old Mobipocket encryption" else "Amazon's DRM (encryption type $encryption)"
                throw EpubFormatException("this Kindle book is protected by $kind and cannot be opened without it removed")
            }
            if (compression !in setOf(1, 2, HUFF)) throw EpubFormatException("unknown MOBI text compression $compression")
        }

        fun fill(book: BookPackager) {
            book.authors = exthText(100)
            book.description = exthText(103).firstOrNull()
        }
    }

    /** The text of the book that starts at record [base]: each text record decompressed, without its trailing entries. */
    private fun text(pdb: Pdb, base: Int, h: Header): ByteArray {
        val huff = if (h.compression == HUFF) Huff(pdb, base + h.huffRecord, h.huffCount) else null
        val out = Bytes(h.textRecords * 4096)
        for (i in 1..h.textRecords) {
            val rec = pdb.rec(base + i)
            val size = (rec.size - trailing(rec, h.extraFlags)).coerceAtLeast(0)
            when (h.compression) {
                1 -> out.add(rec, 0, size)
                2 -> lz77(rec, size, out)
                else -> out.add(huff!!.unpack(rec, size))
            }
        }
        return out.toArray()
    }

    /**
     * The size of the trailing entries of a text record. Each flag bit above bit 0 is an entry that
     * ends with its own size, a variable-width number written backwards. Bit 0 is the multibyte
     * overlap, whose size is in the low two bits of the byte before the other entries.
     */
    internal fun trailing(data: ByteArray, flags: Int): Int {
        var num = 0
        var f = flags ushr 1
        while (f != 0) {
            if (f and 1 != 0) {
                var bits = 0
                var value = 0
                var p = data.size - num
                while (p > 0) {
                    val v = data[p - 1].toInt() and 0xFF
                    value = value or ((v and 0x7F) shl bits)
                    bits += 7
                    p--
                    if (v and 0x80 != 0 || bits >= 28) break
                }
                num += value
                if (num > data.size) return data.size
            }
            f = f ushr 1
        }
        if (flags and 1 != 0 && data.size - num - 1 >= 0) num += (data[data.size - num - 1].toInt() and 0x3) + 1
        return num.coerceAtMost(data.size)
    }

    /** PalmDOC compression: literals, runs of literals, a space and a letter, and LZ77 copies of up to 10 bytes. */
    internal fun lz77(b: ByteArray, size: Int, out: Bytes) {
        val start = out.size
        var i = 0
        while (i < size) {
            val c = b[i++].toInt() and 0xFF
            when {
                c in 1..8 -> { val n = minOf(c, size - i); out.add(b, i, n); i += n }
                c < 0x80 -> out.add(c)
                c >= 0xC0 -> { out.add(' '.code); out.add(c xor 0x80) }
                else -> {
                    if (i >= size) break
                    val v = (c shl 8) or (b[i++].toInt() and 0xFF)
                    val dist = (v ushr 3) and 0x7FF
                    val len = (v and 7) + 3
                    // A copy from before this record's output is corrupt: stop, as a reader does.
                    if (dist == 0 || dist > out.size - start) break
                    for (k in 0 until len) out.add(out[out.size - dist])
                }
            }
        }
    }

    /**
     * HUFF/CDIC compression: a canonical Huffman code whose symbols are phrases of the CDIC
     * dictionaries. A phrase not marked as plain text is itself compressed, and is unpacked once.
     */
    private class Huff(pdb: Pdb, first: Int, count: Int) {
        private val codeLen = IntArray(256)
        private val term = BooleanArray(256)
        private val maxCode1 = LongArray(256)
        private val minCode = LongArray(33)
        private val maxCode = LongArray(33)
        private val phrases = ArrayList<ByteArray?>()
        private val plain = ArrayList<Boolean>()

        init {
            val huff = pdb.rec(first)
            if (ascii(huff, 0, 4) != "HUFF" || huff.size < 24) throw EpubFormatException("no HUFF record at $first")
            val off1 = s32(huff, 8)
            val off2 = s32(huff, 12)
            if (off1 < 0 || off1 + 1024 > huff.size || off2 < 0 || off2 + 256 > huff.size) throw EpubFormatException("truncated HUFF record")
            for (i in 0 until 256) {
                val v = u32(huff, off1 + i * 4)
                codeLen[i] = (v and 0x1F).toInt()
                term[i] = v and 0x80 != 0L
                if (codeLen[i] == 0 || (codeLen[i] <= 8 && !term[i])) throw EpubFormatException("bad HUFF code table")
                maxCode1[i] = (((v ushr 8) + 1) shl (32 - codeLen[i])) - 1
            }
            for (len in 1..32) {
                minCode[len] = u32(huff, off2 + (len - 1) * 8) shl (32 - len)
                maxCode[len] = ((u32(huff, off2 + (len - 1) * 8 + 4) + 1) shl (32 - len)) - 1
            }
            for (k in 1 until count) {
                val cdic = pdb.rec(first + k)
                if (ascii(cdic, 0, 4) != "CDIC" || cdic.size < 16) break
                val total = s32(cdic, 8)
                val bits = s32(cdic, 12).coerceIn(0, 30)
                val n = minOf(1 shl bits, total - phrases.size)
                for (j in 0 until n) {
                    if (16 + j * 2 + 2 > cdic.size) break
                    val off = 16 + u16(cdic, 16 + j * 2)
                    if (off + 2 > cdic.size) { phrases += ByteArray(0); plain += true; continue }
                    val bl = u16(cdic, off)
                    val end = minOf(off + 2 + (bl and 0x7FFF), cdic.size)
                    phrases += cdic.copyOfRange(off + 2, end)
                    plain += bl and 0x8000 != 0
                }
            }
        }

        fun unpack(data: ByteArray, size: Int = data.size, depth: Int = 0): ByteArray {
            if (depth > 32) throw EpubFormatException("HUFF phrases nest too deep")
            val out = Bytes(size * 4)
            var bitsLeft = size.toLong() * 8
            var pos = 0
            fun window(p: Int): Long {
                var x = 0L
                for (k in 0 until 8) x = (x shl 8) or (if (p + k < size) (data[p + k].toLong() and 0xFF) else 0L)
                return x
            }
            var x = window(0)
            var n = 32
            while (true) {
                if (n <= 0) { pos += 4; x = window(pos); n += 32 }
                val code = (x ushr n) and 0xFFFFFFFFL
                val top = (code ushr 24).toInt()
                var len = codeLen[top]
                var max = maxCode1[top]
                if (!term[top]) {
                    while (len < 32 && code < minCode[len]) len++
                    max = maxCode[len]
                }
                n -= len
                bitsLeft -= len
                if (bitsLeft < 0) break
                val r = ((max - code) ushr (32 - len)).toInt()
                if (r !in phrases.indices) throw EpubFormatException("HUFF code out of the dictionary")
                var phrase = phrases[r] ?: throw EpubFormatException("a HUFF phrase that contains itself")
                if (!plain[r]) {
                    phrases[r] = null
                    phrase = unpack(phrase, phrase.size, depth + 1)
                    phrases[r] = phrase
                    plain[r] = true
                }
                out.add(phrase)
            }
            return out.toArray()
        }
    }

    // ---- Indexes ----

    /** One entry of an INDX index: its name and the values of each tag. */
    private class IndexEntry(val name: String, val tags: Map<Int, List<Int>>) {
        fun first(tag: Int): Int? = tags[tag]?.firstOrNull()
    }

    private class Index(val entries: List<IndexEntry>, private val cncx: List<ByteArray>) {
        /** The string at [offset] of the CNCX records: a variable-width length, then the text. */
        fun string(offset: Int, decode: (ByteArray) -> String): String? {
            val rec = cncx.getOrNull(offset ushr 16) ?: return null
            val p = offset and 0xFFFF
            if (p >= rec.size) return null
            val (len, q) = decint(rec, p)
            if (q + len > rec.size) return null
            return decode(rec.copyOfRange(q, q + len))
        }
    }

    /**
     * The index whose header is record [at]: the header's TAGX section says which tags an entry
     * has, the records after it hold the entries behind their IDXT offsets, and the CNCX records
     * after those hold the strings.
     */
    private fun index(pdb: Pdb, at: Int): Index? {
        if (at < 0 || at >= pdb.count) return null
        val h = pdb.rec(at)
        if (ascii(h, 0, 4) != "INDX") return null
        val hl = s32(h, 4)
        val records = s32(h, 24)
        val cncxCount = s32(h, 52).coerceIn(0, 64)
        if (ascii(h, hl, 4) != "TAGX") return null
        val tagLen = s32(h, hl + 4)
        val controlBytes = s32(h, hl + 8)
        class Tag(val tag: Int, val perEntry: Int, val mask: Int, val end: Boolean)
        val tags = (0 until ((tagLen - 12) / 4).coerceAtLeast(0)).mapNotNull { k ->
            val p = hl + 12 + 4 * k
            if (p + 4 > h.size) null else Tag(h[p].toInt() and 0xFF, h[p + 1].toInt() and 0xFF, h[p + 2].toInt() and 0xFF, h[p + 3].toInt() == 1)
        }
        val entries = ArrayList<IndexEntry>()
        for (ri in at + 1..at + records) {
            val b = pdb.rec(ri)
            if (ascii(b, 0, 4) != "INDX") break
            val idxt = s32(b, 20)
            val count = s32(b, 24)
            if (idxt < 0 || idxt + 4 + count * 2 > b.size) continue
            val pos = IntArray(count + 1) { j -> if (j == count) idxt else u16(b, idxt + 4 + 2 * j) }
            for (j in 0 until count) {
                val e = b.copyOfRange(pos[j].coerceAtMost(b.size), pos[j + 1].coerceIn(pos[j].coerceAtMost(b.size), b.size))
                if (e.isEmpty()) continue
                val nameLen = e[0].toInt() and 0xFF
                if (1 + nameLen + controlBytes > e.size) continue
                val name = ascii(e, 1, nameLen)
                var p = 1 + nameLen
                var control = e.copyOfRange(p, p + controlBytes).map { it.toInt() and 0xFF }
                p += controlBytes
                class Pending(val tag: Int, val count: Int?, val bytes: Int?, val perEntry: Int)
                val pending = ArrayList<Pending>()
                for (t in tags) {
                    if (t.end) { control = control.drop(1); continue }
                    var value = (control.firstOrNull() ?: 0) and t.mask
                    if (value == 0) continue
                    if (value == t.mask) {
                        if (t.mask.countOneBits() > 1) {
                            val (n, q) = decint(e, p); p = q
                            pending += Pending(t.tag, null, n, t.perEntry)
                        } else pending += Pending(t.tag, 1, null, t.perEntry)
                    } else {
                        var m = t.mask
                        while (m and 1 == 0) { m = m ushr 1; value = value ushr 1 }
                        pending += Pending(t.tag, value, null, t.perEntry)
                    }
                }
                val map = HashMap<Int, List<Int>>()
                for (t in pending) {
                    val values = ArrayList<Int>()
                    if (t.count != null) {
                        repeat(t.count * t.perEntry) { if (p < e.size) { val (v, q) = decint(e, p); p = q; values += v } }
                    } else {
                        val stop = p + t.bytes!!
                        while (p < stop && p < e.size) { val (v, q) = decint(e, p); p = q; values += v }
                    }
                    map[t.tag] = values
                }
                entries += IndexEntry(name, map)
            }
        }
        val cncx = (0 until cncxCount).map { pdb.rec(at + records + 1 + it) }
        return Index(entries, cncx)
    }

    /** A forward variable-width number: seven bits a byte, the last byte marked by its high bit. */
    private fun decint(b: ByteArray, from: Int): Pair<Int, Int> {
        var v = 0
        var p = from
        while (p < b.size) {
            val c = b[p++].toInt() and 0xFF
            v = (v shl 7) or (c and 0x7F)
            if (c and 0x80 != 0) break
        }
        return v to p
    }

    // ---- Resources ----

    /** The image and font records from [first] on, numbered from 1 as `kindle:embed` and `recindex` count them. */
    private class Resources(private val pdb: Pdb, private val first: Int) {
        private val cache = HashMap<Int, Pair<String, String>?>()
        val used = LinkedHashMap<String, Pair<ByteArray, String>>()

        /** The package path of resource [n], or null when it is no image or font. */
        fun path(n: Int): String? {
            if (n < 1 || first < 0) return null
            return cache.getOrPut(n) { load(n) }?.first
        }

        private fun load(n: Int): Pair<String, String>? {
            val data = pdb.rec(first + n - 1)
            val (bytes, ext, type) = kind(data) ?: return null
            val path = "res${n.toString().padStart(5, '0')}.$ext"
            used[path] = bytes to type
            return path to type
        }

        private fun kind(d: ByteArray): Triple<ByteArray, String, String>? = when {
            d.size > 3 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() -> Triple(d, "jpg", "image/jpeg")
            d.size > 8 && d[0] == 0x89.toByte() && ascii(d, 1, 3) == "PNG" -> Triple(d, "png", "image/png")
            ascii(d, 0, 4) == "GIF8" -> Triple(d, "gif", "image/gif")
            ascii(d, 0, 2) == "BM" -> Triple(d, "bmp", "image/bmp")
            ascii(d, 0, 4) == "FONT" -> font(d)?.let { f ->
                val otf = ascii(f, 0, 4) == "OTTO"
                Triple(f, if (otf) "otf" else "ttf", if (otf) "font/otf" else "font/ttf")
            }
            else -> null
        }

        /** A FONT record: zlib compressed when flag 1 is set, its first 1040 bytes XORed with a key when flag 2 is. */
        private fun font(d: ByteArray): ByteArray? = runCatching {
            if (d.size < 24) return null
            val size = s32(d, 4)
            val flags = s32(d, 8)
            val dataOff = s32(d, 12)
            val keyLen = s32(d, 16)
            val keyOff = s32(d, 20)
            var f = d.copyOfRange(dataOff, d.size)
            if (flags and 2 != 0 && keyLen > 0 && keyOff + keyLen <= d.size) {
                for (i in 0 until minOf(1040, f.size)) f[i] = (f[i].toInt() xor d[keyOff + i % keyLen].toInt()).toByte()
            }
            if (flags and 1 != 0) f = Inflate.decodePlatform(f, 2, f.size - 2, maxOutputBytes = maxOf(size, 0) + 1024)
            f
        }.getOrElse { kiteWarn { "MOBI: an unreadable FONT record: ${it.message}" }; null }
    }

    /** The record of the first resource: as the header says, or, for a joint file, from the KF8 record 0 on. */
    private fun firstResource(pdb: Pdb, base: Int, h: Header): Int {
        val fi = h.firstImage
        if (fi < 0) return -1
        if (base == 0) return fi
        fun looksLikeResource(i: Int) = pdb.rec(i).let { d ->
            d.size > 4 && (d[0] == 0xFF.toByte() || d[0] == 0x89.toByte() || ascii(d, 0, 3) == "GIF" || ascii(d, 0, 4) in RESOURCE_MAGIC)
        }
        return if (looksLikeResource(base + fi)) base + fi else fi
    }

    // ---- KF8 ----

    private class Kf8(val pdb: Pdb, val base: Int, val h: Header, val outer: Header = h) {
        private lateinit var parts: List<Part>
        private lateinit var frags: List<IndexEntry>
        private lateinit var res: Resources
        private val flowFiles = HashMap<Int, String>()
        private lateinit var flows: List<String>

        /** One rebuilt XHTML part: where its text starts in flow 0, and the text in Latin-1, one char a byte. */
        class Part(val start: Int, val text: String, val file: String)

        fun convert(): ByteArray {
            val raw = text(pdb, base, h)
            val fdst = pdb.rec(base + h.fdst)
            val bounds = if (h.fdst >= 0 && ascii(fdst, 0, 4) == "FDST") {
                val n = s32(fdst, 8)
                (0 until n).mapNotNull { k ->
                    val p = 12 + 8 * k
                    if (p + 8 > fdst.size) null else s32(fdst, p).coerceIn(0, raw.size) to s32(fdst, p + 4).coerceIn(0, raw.size)
                }
            } else emptyList()
            val all = latin1(raw)
            flows = bounds.ifEmpty { listOf(0 to raw.size) }.map { (a, b) -> if (b > a) all.substring(a, b) else "" }
            val flow0 = flows[0]
            res = Resources(pdb, firstResource(pdb, base, h))
            val skels = index(pdb, base + h.skeletons)?.entries.orEmpty()
            frags = index(pdb, base + h.fragments)?.entries.orEmpty()
            parts = if (skels.isEmpty()) listOf(Part(0, flow0, "part0000.xhtml")) else assemble(flow0, skels)

            val book = BookPackager(outer.title.ifEmpty { h.title }, outer.exthText(524).firstOrNull() ?: h.exthText(524).firstOrNull() ?: "en")
            outer.fill(book)
            for (p in parts) {
                val markup = decode(resolve(p.text, inMarkup = true))
                val xml = markup.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("<?xml")
                book.chapter(BookPackager.Chapter(p.file, markup, if (xml) "application/xhtml+xml" else "text/html"))
            }
            // Flows are written as they are referenced, and may reference more of each other.
            var done = 0
            while (done < flowFiles.size) {
                val pending = flowFiles.entries.toList().drop(done)
                done = flowFiles.size
                for ((n, file) in pending) {
                    val body = flows.getOrNull(n) ?: continue
                    val type = if (file.endsWith(".css")) "text/css" else "image/svg+xml"
                    book.resource(file, decode(resolve(body, inMarkup = file.endsWith(".svg"))).encodeToByteArray(), type)
                }
            }
            cover(h, outer, res)?.let { book.coverImage = it }
            for ((path, r) in res.used) book.resource(path, r.first, r.second)
            index(pdb, base + h.ncx)?.let { ncx -> toc(ncx, h, ::hrefAt, ::hrefOfFid).forEach(book::nav) }
            return book.build()
        }

        private fun decode(latin: String): String = h.decode(unlatin1(latin))

        /** Each skeleton with its fragments inserted, in the order the fragment index lists them. */
        private fun assemble(flow0: String, skels: List<IndexEntry>): List<Part> {
            var fi = 0
            return skels.mapIndexed { n, s ->
                val count = s.first(1) ?: 0
                val sv = s.tags[6].orEmpty()
                val start = sv.getOrElse(0) { 0 }.coerceIn(0, flow0.length)
                val len = sv.getOrElse(1) { 0 }
                val sb = StringBuilder(flow0.substring(start, (start + len).coerceIn(start, flow0.length)))
                var at = start + len
                repeat(count) {
                    val f = frags.getOrNull(fi++) ?: return@repeat
                    val insert = (f.name.toIntOrNull() ?: return@repeat) - start
                    val flen = f.tags[6]?.getOrNull(1) ?: 0
                    val slice = flow0.substring(at.coerceIn(0, flow0.length), (at + flen).coerceIn(0, flow0.length))
                    sb.insert(insert.coerceIn(0, sb.length), slice)
                    at += flen
                }
                Part(start, sb.toString(), "part${n.toString().padStart(4, '0')}.xhtml")
            }
        }

        /** [s] with its `kindle:` references made package paths, and its `aid` attributes made ids. */
        private fun resolve(s: String, inMarkup: Boolean): String {
            var t = KINDLE_POS.replace(s) { m -> hrefOfFid(base32(m.groupValues[1]), base32(m.groupValues[2])) ?: "#" }
            t = KINDLE_EMBED.replace(t) { m -> res.path(base32(m.groupValues[1])) ?: m.value }
            t = KINDLE_FLOW.replace(t) { m ->
                val n = base32(m.groupValues[1])
                val svg = m.groupValues[2].contains("svg")
                if (n !in flows.indices || n == 0) m.value
                else flowFiles.getOrPut(n) { "flow${n.toString().padStart(4, '0')}.${if (svg) "svg" else "css"}" }
            }
            return if (inMarkup) aidsToIds(t) else t
        }

        /** The href of the place [off] bytes past the insert position of fragment [fid]. */
        fun hrefOfFid(fid: Int, off: Int): String? {
            val f = frags.getOrNull(fid) ?: return null
            val insert = f.name.toIntOrNull() ?: return null
            return hrefAt(insert + off)
        }

        /** The href of byte [pos] of flow 0: its part, and the nearest anchor at or before it. */
        fun hrefAt(pos: Int): String? {
            val part = parts.lastOrNull { it.start <= pos } ?: parts.firstOrNull() ?: return null
            val id = anchorBefore(part.text, pos - part.start)
            return if (id != null) "${part.file}#$id" else part.file
        }
    }

    /** The id, `a name` or `aid` of the last start tag at or before [at], as [aidsToIds] will name it. */
    private fun anchorBefore(text: String, at: Int): String? {
        var n = at.coerceIn(0, text.length)
        val gt = text.indexOf('>', n)
        val lt = text.indexOf('<', n)
        // Inside a tag, or at its start: the tag itself is the target.
        if (gt >= 0 && (lt == n || lt < 0 || gt < lt)) n = gt + 1
        var end = n
        while (end > 0) {
            val open = text.lastIndexOf('<', end - 1)
            if (open < 0) break
            val close = text.indexOf('>', open).let { if (it < 0 || it > end) end else it + 1 }
            val tag = text.substring(open, close)
            end = open
            if (tag.startsWith("</") || tag.startsWith("<!") || tag.startsWith("<?")) continue
            ID_ATTR.find(tag)?.let { return it.groupValues[2] }
            NAME_ATTR.find(tag)?.let { return it.groupValues[2] }
            AID_ATTR.find(tag)?.let { return "aid-" + it.groupValues[2] }
        }
        return null
    }

    /** Each start tag's `aid` made an id when the tag has none, and dropped when it has one. */
    private fun aidsToIds(s: String): String = TAG_WITH_AID.replace(s) { m ->
        val tag = m.value
        if (ID_ATTR.containsMatchIn(tag)) AID_ATTR_FULL.replace(tag, "")
        else AID_ATTR_FULL.replace(tag) { a -> " id=\"aid-${a.groupValues[2]}\"" }
    }

    /** The cover from EXTH 201, an offset from the first resource, or EXTH 129, a `kindle:embed` reference. */
    private fun cover(h: Header, outer: Header, res: Resources): String? {
        val offset = h.exthInt(201) ?: outer.exthInt(201)
        offset?.takeIf { it >= 0 }?.let { o -> res.path(o + 1)?.let { return it } }
        val uri = (h.exth(129) + outer.exth(129)).firstOrNull()?.let { latin1(it) } ?: return null
        return KINDLE_EMBED.find(uri)?.let { res.path(base32(it.groupValues[1])) }
    }

    /**
     * The table of contents from the NCX index: tag 3 is the label in the CNCX records, tag 4 the
     * depth, and tag 6 the fragment and offset of the target, or, before KF8, tag 1 its byte.
     */
    private fun toc(ncx: Index, h: Header, hrefAt: (Int) -> String?, hrefOfFid: ((Int, Int) -> String?)?): List<BookPackager.NavEntry> {
        class Node(val label: String, val href: String, val depth: Int, val children: ArrayList<Node> = ArrayList())
        val roots = ArrayList<Node>()
        val stack = ArrayList<Node>()
        for (e in ncx.entries) {
            val label = e.first(3)?.let { ncx.string(it, h::decode) }?.trim().orEmpty()
            val fid = e.tags[6]
            val href = (if (hrefOfFid != null && fid != null && fid.size >= 2) hrefOfFid(fid[0], fid[1]) else null)
                ?: e.first(1)?.let(hrefAt) ?: continue
            if (label.isEmpty()) continue
            val node = Node(label, href, e.first(4) ?: 0)
            while (stack.isNotEmpty() && stack.last().depth >= node.depth) stack.removeAt(stack.size - 1)
            (stack.lastOrNull()?.children ?: roots) += node
            stack += node
        }
        fun out(n: Node): BookPackager.NavEntry = BookPackager.NavEntry(n.label, n.href, n.children.map(::out))
        return roots.map(::out)
    }

    // ---- MOBI 6 ----

    private class Mobi6(val pdb: Pdb, val h: Header) {
        fun convert(): ByteArray {
            var s = latin1(text(pdb, 0, h))
            val ncx = index(pdb, h.ncx)
            val targets = (FILEPOS.findAll(s).map { it.groupValues[1].toInt() } +
                ncx?.entries.orEmpty().mapNotNull { it.first(1) }).distinct().sorted().toList()
            // Anchors go in from the end, so each byte offset still names its place; one inside a tag goes before it.
            val sb = StringBuilder(s)
            for (pos in targets.asReversed()) {
                var at = pos.coerceIn(0, s.length)
                val lt = s.lastIndexOf('<', at - 1)
                if (lt >= 0 && s.indexOf('>', lt).let { it < 0 || it >= at }) at = lt
                sb.insert(at, "<a id=\"filepos$pos\"></a>")
            }
            s = sb.toString()
            val bodyStart = BODY_OPEN.find(s)?.let { it.range.last + 1 } ?: 0
            val bodyEnd = s.lastIndexOf("</body>", ignoreCase = true).let { if (it < bodyStart) s.length else it }
            val pieces = PAGEBREAK.split(s.substring(bodyStart, bodyEnd)).filter { p ->
                p.contains("<img", ignoreCase = true) || p.contains("id=\"filepos") || TAG.replace(p, "").isNotBlank()
            }.ifEmpty { listOf("") }
            val files = pieces.indices.map { "chapter${it.toString().padStart(4, '0')}.html" }
            val home = HashMap<Int, String>()
            pieces.forEachIndexed { i, p -> ANCHOR_ID.findAll(p).forEach { home.getOrPut(it.groupValues[1].toInt()) { files[i] } } }
            fun hrefAt(pos: Int): String? = home[pos]?.let { "$it#filepos$pos" }
            val res = Resources(pdb, h.firstImage)

            val book = BookPackager(h.title, h.exthText(524).firstOrNull() ?: "en")
            h.fill(book)
            pieces.forEachIndexed { i, p ->
                var t = FILEPOS_ATTR.replace(p) { m -> "href=\"${hrefAt(m.groupValues[1].toInt()) ?: "#"}\"" }
                t = RECINDEX.replace(t) { m -> res.path(m.groupValues[1].toInt())?.let { "src=\"$it\"" } ?: "" }
                t = MBP_TAG.replace(t, "")
                t = LEGACY_BLOCK.replace(t, ::legacySpacing)
                val body = h.decode(unlatin1(t))
                book.chapter(BookPackager.Chapter(files[i],
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>${BookPackager.text(h.title)}</title><style>$MOBI6_CSS</style></head><body>$body</body></html>",
                    "text/html"))
            }
            cover(h, h, res)?.let { book.coverImage = it }
            for ((path, r) in res.used) book.resource(path, r.first, r.second)
            ncx?.let { toc(it, h, ::hrefAt, null).forEach(book::nav) }
            return book.build()
        }
    }

    /**
     * A MOBI 6 block's `height` is the space above it and its `width` the indent of its first
     * line, a negative one a hanging indent. Left as they are, they would size the box.
     */
    private fun legacySpacing(m: MatchResult): String {
        var tag = m.value
        val css = StringBuilder()
        LEGACY_HEIGHT.find(tag)?.let { css.append("margin-top:").append(unit(it.groupValues[2])).append(';'); tag = tag.replaceRange(it.range, "") }
        LEGACY_WIDTH.find(tag)?.let { w ->
            val v = unit(w.groupValues[2])
            css.append("text-indent:").append(v).append(';')
            if (v.startsWith("-")) css.append("margin-left:").append(v.substring(1)).append(';')
            tag = tag.replaceRange(w.range, "")
        }
        if (css.isEmpty()) return tag
        val style = STYLE_ATTR.find(tag)
        // The value starts after the attribute's opening quote; the whole match's range is common to every target.
        val valueStart = style?.let { it.range.first + it.value.indexOf('"') + 1 }
        return if (valueStart != null) tag.substring(0, valueStart) + css + tag.substring(valueStart)
        else tag.substring(0, tag.length - 1).trimEnd('/').trimEnd() + " style=\"$css\"" + (if (tag.endsWith("/>")) "/>" else ">")
    }

    private fun unit(v: String): String = v.trim().let { if (it.isNotEmpty() && it.last().isDigit()) it + "px" else it }

    // ---- Bytes ----

    /** A growable byte buffer that an LZ77 copy can read back from. */
    internal class Bytes(capacity: Int = 4096) {
        private var buf = ByteArray(capacity.coerceIn(16, 1 shl 26))
        var size = 0
            private set

        operator fun get(i: Int): Int = buf[i].toInt() and 0xFF
        fun add(b: Int) { if (size == buf.size) grow(1); buf[size++] = b.toByte() }
        fun add(b: ByteArray, from: Int = 0, n: Int = b.size - from) {
            if (size + n > buf.size) grow(n)
            b.copyInto(buf, size, from, from + n)
            size += n
        }
        fun toArray(): ByteArray = buf.copyOf(size)
        private fun grow(n: Int) { buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
    }

    private const val HUFF = 17480

    /**
     * What a Kindle reader assumes of MOBI 6 markup: paragraphs without space between them and
     * with an indent unless a `width` says otherwise, and a narrow indent for each `blockquote`,
     * which poems nest five deep.
     */
    private const val MOBI6_CSS = "p{margin:0;text-indent:1.5em}blockquote{margin:0 0 0 1em}"
    private val RESOURCE_MAGIC = setOf("FONT", "RESC", "FLIS", "FCIS", "SRCS", "DATP", "CRES", "CONT", "kind")

    private fun u16(b: ByteArray, p: Int): Int = if (p + 2 > b.size || p < 0) 0 else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int): Long = if (p + 4 > b.size || p < 0) 0L else
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
    /** A big-endian 32-bit field as an Int, so `0xFFFFFFFF`, the "none" of the MOBI header, reads as -1. */
    private fun s32(b: ByteArray, p: Int): Int = if (p + 4 > b.size || p < 0) -1 else u32(b, p).toInt()
    private fun ascii(b: ByteArray, p: Int, n: Int): String =
        if (p < 0 || p + n > b.size) "" else CharArray(n) { (b[p + it].toInt() and 0xFF).toChar() }.concatToString()

    /** Bytes as a string of one char a byte, so string positions are byte offsets. */
    private fun latin1(b: ByteArray): String = CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() }.concatToString()
    private fun unlatin1(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    /** A number in the base 32 of `kindle:` references: digits, then `A` to `V`. */
    private fun base32(s: String): Int {
        var v = 0
        for (c in s.uppercase()) {
            val d = when (c) { in '0'..'9' -> c - '0'; in 'A'..'V' -> c - 'A' + 10; else -> return -1 }
            v = v * 32 + d
            if (v < 0) return -1
        }
        return v
    }

    private val KINDLE_POS = Regex("""kindle:pos:fid:([0-9A-Va-v]+):off:([0-9A-Va-v]+)""")
    private val KINDLE_EMBED = Regex("""kindle:embed:([0-9A-Va-v]+)(?:\?mime=[^"')\s]*)?""")
    private val KINDLE_FLOW = Regex("""kindle:flow:([0-9A-Va-v]+)(?:\?mime=([^"')\s]*))?""")
    private val ID_ATTR = Regex("""\s(id|ID)\s*=\s*['"]([^'"]+)['"]""")
    private val NAME_ATTR = Regex("""^<\s*[aA]\s[^>]*?\b(name|NAME)\s*=\s*['"]([^'"]+)['"]""")
    private val AID_ATTR = Regex("""\s(aid|AID)\s*=\s*['"]([^'"]+)['"]""")
    private val AID_ATTR_FULL = Regex("""\s(aid|AID)\s*=\s*['"]([^'"]*)['"]""")
    private val TAG_WITH_AID = Regex("""<[A-Za-z][^<>]*\s(?:aid|AID)\s*=[^<>]*>""")
    private val FILEPOS = Regex("""filepos\s*=\s*['"]?0*(\d{1,9})""", RegexOption.IGNORE_CASE)
    private val FILEPOS_ATTR = Regex("""filepos\s*=\s*['"]?0*(\d{1,9})['"]?""", RegexOption.IGNORE_CASE)
    private val RECINDEX = Regex("""recindex\s*=\s*['"]?0*(\d{1,9})['"]?""", RegexOption.IGNORE_CASE)
    private val ANCHOR_ID = Regex("""<a id="filepos(\d+)"></a>""")
    private val BODY_OPEN = Regex("""<body[^>]*>""", RegexOption.IGNORE_CASE)
    private val PAGEBREAK = Regex("""<mbp:pagebreak[^>]*>""", RegexOption.IGNORE_CASE)
    private val MBP_TAG = Regex("""</?mbp:[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val LEGACY_BLOCK = Regex("""<(p|div|blockquote|li|ul|ol)\s[^>]*\b(width|height)\s*=[^>]*>""", RegexOption.IGNORE_CASE)
    private val LEGACY_HEIGHT = Regex("""\s(height)\s*=\s*['"]?([-0-9.]+[a-z%]*)['"]?""", RegexOption.IGNORE_CASE)
    private val LEGACY_WIDTH = Regex("""\s(width)\s*=\s*['"]?([-0-9.]+[a-z%]*)['"]?""", RegexOption.IGNORE_CASE)
    private val STYLE_ATTR = Regex("""\s(style)\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
}
