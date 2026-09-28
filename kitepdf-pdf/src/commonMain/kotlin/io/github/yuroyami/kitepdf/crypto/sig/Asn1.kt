package io.github.yuroyami.kitepdf.crypto.sig

/**
 * One BER or DER element (X.690): its tag, and where its header, content and end are in
 * [bytes]. A constructed element reads its children on demand. An indefinite length, which
 * some signers write, ends at its end-of-contents marker (#203).
 */
internal class Asn1Node(
    val bytes: ByteArray,
    val tag: Int,
    val start: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val end: Int,
    val constructed: Boolean,
    private val depth: Int,
) {
    val children: List<Asn1Node> by lazy {
        if (!constructed) return@lazy emptyList()
        val out = ArrayList<Asn1Node>()
        var at = contentStart
        while (at < contentEnd && out.size < Asn1.MAX_CHILDREN) {
            val child = Asn1.read(bytes, at, contentEnd, depth + 1) ?: break
            out += child
            at = child.end
        }
        out
    }

    /** The content octets. A constructed string, which BER allows, joins the contents of its parts. */
    fun content(): ByteArray {
        if (!constructed) return bytes.copyOfRange(contentStart, contentEnd)
        val parts = children.map { it.content() }
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** The whole element as it is encoded. */
    fun encoded(): ByteArray = bytes.copyOfRange(start, end)

    /** The dotted object identifier, or null when this is not one. */
    fun oid(): String? {
        if (tag != Asn1.OID || contentEnd <= contentStart) return null
        val parts = ArrayList<Long>()
        var value = 0L
        for (i in contentStart until contentEnd) {
            val b = bytes[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) {
                parts += value
                value = 0L
            }
        }
        if (parts.isEmpty()) return null
        val first = parts[0]
        val head = if (first < 80) listOf(first / 40, first % 40) else listOf(2L, first - 80)
        return (head + parts.drop(1)).joinToString(".")
    }

    /** The integer, as its magnitude: a certificate serial or an RSA number is never negative. */
    fun integer(): BigNat? = if (tag != Asn1.INTEGER) null else BigNat.fromBytes(bytes, contentStart, contentEnd - contentStart)

    /** The bits of a BIT STRING, without its unused-bits octet. */
    fun bitString(): ByteArray? {
        if (tag != Asn1.BIT_STRING) return null
        val c = content()
        return if (c.isEmpty()) null else c.copyOfRange(1, c.size)
    }

    /** The text of a string element: UTF-8, printable, IA5, visible, Teletex (read as Latin-1) or BMP. */
    fun string(): String? {
        val c = content()
        return when (tag) {
            0x0C, 0x13, 0x16, 0x1A, 0x12 -> c.decodeToString()
            0x14 -> c.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
            0x1E -> (0 until c.size / 2).joinToString("") { i -> (((c[2 * i].toInt() and 0xFF) shl 8) or (c[2 * i + 1].toInt() and 0xFF)).toChar().toString() }
            else -> null
        }
    }

    /** The text of a UTCTime or a GeneralizedTime, as it is written. */
    fun time(): String? = if (tag == 0x17 || tag == 0x18) content().decodeToString() else null

    /** The child with the context tag [n], constructed or not. */
    fun context(n: Int): Asn1Node? = children.firstOrNull { it.tag == 0xA0 + n || it.tag == 0x80 + n }
}

/** Reads BER and DER. */
internal object Asn1 {
    const val INTEGER = 0x02
    const val BIT_STRING = 0x03
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OID = 0x06
    const val SEQUENCE = 0x30
    const val SET = 0x31

    /** Nesting deeper than this, or more children than [MAX_CHILDREN], is not read. */
    const val MAX_DEPTH = 64
    const val MAX_CHILDREN = 100_000

    /** The DER encoding of an element with [tag] and [content]. */
    fun encode(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
            n < 0x1000000 -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x84.toByte(), (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    /** The DER encoding of a SEQUENCE of [parts]. */
    fun sequence(vararg parts: ByteArray): ByteArray = encode(SEQUENCE, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    /** The DER encoding of the object identifier [dotted], such as `1.2.840.113549.1.7.2`. */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ArrayList<Byte>()
        fun base128(value: Long) {
            val groups = ArrayList<Int>()
            var v = value
            do {
                groups += (v and 0x7F).toInt()
                v = v ushr 7
            } while (v != 0L)
            for (i in groups.indices.reversed()) out += (groups[i] or if (i > 0) 0x80 else 0).toByte()
        }
        base128(arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) base128(arc)
        return encode(OID, out.toByteArray())
    }

    /** The element at [offset], within [limit], or null when the bytes do not hold one. */
    fun read(bytes: ByteArray, offset: Int, limit: Int = bytes.size, depth: Int = 0): Asn1Node? {
        if (depth > MAX_DEPTH || offset + 2 > limit) return null
        val tag = bytes[offset].toInt() and 0xFF
        if (tag and 0x1F == 0x1F) return null // high tag numbers do not appear in CMS or X.509
        val constructed = tag and 0x20 != 0
        var at = offset + 1
        val first = bytes[at++].toInt() and 0xFF
        if (first == 0x80) {
            // Indefinite length: the content runs to an end-of-contents marker.
            if (!constructed) return null
            var cursor = at
            var count = 0
            while (cursor + 2 <= limit && count++ < MAX_CHILDREN) {
                if (bytes[cursor].toInt() == 0 && bytes[cursor + 1].toInt() == 0) {
                    return Asn1Node(bytes, tag, offset, at, cursor, cursor + 2, constructed, depth)
                }
                cursor = read(bytes, cursor, limit, depth + 1)?.end ?: return null
            }
            return null
        }
        val length: Long = if (first and 0x80 == 0) first.toLong() else {
            val n = first and 0x7F
            if (n > 4 || at + n > limit) return null
            var l = 0L
            repeat(n) { l = (l shl 8) or (bytes[at++].toLong() and 0xFF) }
            l
        }
        val end = at + length
        if (length < 0 || end > limit) return null
        return Asn1Node(bytes, tag, offset, at, end.toInt(), end.toInt(), constructed, depth)
    }
}
