package io.github.yuroyami.kitepdf.crypto.sig

/**
 * A NIST prime curve y² = x³ + ax + b over the field of [p], with the base point ([gx], [gy]) of
 * order [n] (FIPS 186-4, D.1.2), and ECDSA verification on it (#203). Points are Jacobian inside.
 */
internal class EcCurve private constructor(
    val name: String,
    val oid: String,
    private val p: BigNat,
    private val a: BigNat,
    private val b: BigNat,
    private val gx: BigNat,
    private val gy: BigNat,
    val n: BigNat,
) {
    /** A point in Jacobian coordinates: (X/Z², Y/Z³), or the point at infinity when Z is 0. */
    private class Point(val x: BigNat, val y: BigNat, val z: BigNat) {
        val infinite: Boolean get() = z.isZero
    }

    private fun add(x: BigNat, y: BigNat) = (x + y) % p
    private fun sub(x: BigNat, y: BigNat) = if (x >= y) x - y else x + p - y
    private fun mul(x: BigNat, y: BigNat) = (x * y) % p

    private fun double(q: Point): Point {
        if (q.infinite || q.y.isZero) return INFINITY
        val y2 = mul(q.y, q.y)
        val s = mul(BigNat.of(4), mul(q.x, y2))
        val z2 = mul(q.z, q.z)
        val m = add(mul(BigNat.of(3), mul(q.x, q.x)), mul(a, mul(z2, z2)))
        val x3 = sub(mul(m, m), add(s, s))
        val y3 = sub(mul(m, sub(s, x3)), mul(BigNat.of(8), mul(y2, y2)))
        val z3 = mul(BigNat.of(2), mul(q.y, q.z))
        return Point(x3, y3, z3)
    }

    private fun add(q: Point, r: Point): Point {
        if (q.infinite) return r
        if (r.infinite) return q
        val z1z1 = mul(q.z, q.z)
        val z2z2 = mul(r.z, r.z)
        val u1 = mul(q.x, z2z2)
        val u2 = mul(r.x, z1z1)
        val s1 = mul(q.y, mul(r.z, z2z2))
        val s2 = mul(r.y, mul(q.z, z1z1))
        if (u1 == u2) return if (s1 == s2) double(q) else INFINITY
        val h = sub(u2, u1)
        val rr = sub(s2, s1)
        val h2 = mul(h, h)
        val h3 = mul(h, h2)
        val u1h2 = mul(u1, h2)
        val x3 = sub(sub(mul(rr, rr), h3), add(u1h2, u1h2))
        val y3 = sub(mul(rr, sub(u1h2, x3)), mul(s1, h3))
        val z3 = mul(h, mul(q.z, r.z))
        return Point(x3, y3, z3)
    }

    /** [k] times [q], by double and add from the top bit. */
    private fun multiply(k: BigNat, q: Point): Point {
        var result = INFINITY
        for (i in k.bitLength - 1 downTo 0) {
            result = double(result)
            if (k.testBit(i)) result = add(result, q)
        }
        return result
    }

    private fun affineX(q: Point): BigNat? {
        if (q.infinite) return null
        val zInv = q.z.modPow(p - BigNat.of(2), p)
        return mul(q.x, mul(zInv, zInv))
    }

    /** True when (x, y) satisfies the curve's equation. */
    fun onCurve(x: BigNat, y: BigNat): Boolean {
        if (x >= p || y >= p) return false
        return mul(y, y) == add(add(mul(mul(x, x), x), mul(a, x)), b)
    }

    /** The point that [encoded] holds: 04‖X‖Y, or a compressed 02‖X or 03‖X (SEC 1, 2.3.4). Null when it is not on the curve. */
    fun decodePoint(encoded: ByteArray): Pair<BigNat, BigNat>? {
        val size = (p.bitLength + 7) / 8
        if (encoded.isEmpty()) return null
        val point = when (encoded[0].toInt()) {
            4 -> if (encoded.size != 1 + 2 * size) return null else BigNat.fromBytes(encoded, 1, size) to BigNat.fromBytes(encoded, 1 + size, size)
            2, 3 -> {
                if (encoded.size != 1 + size) return null
                val x = BigNat.fromBytes(encoded, 1, size)
                // Every curve here has p ≡ 3 (mod 4), so the root is a power of p + 1 over 4.
                var y = add(add(mul(mul(x, x), x), mul(a, x)), b).modPow((p + BigNat.ONE).divRem(BigNat.of(4)).first, p)
                if (y.testBit(0) != (encoded[0].toInt() == 3)) y = if (y.isZero) y else p - y
                x to y
            }
            else -> return null
        }
        return point.takeIf { (x, y) -> onCurve(x, y) }
    }

    /**
     * True when (r, s) signs [hash] under the public key ([qx], [qy]) (SEC 1, 4.1.4). A hash longer
     * than the order keeps its leftmost bits.
     */
    fun verify(qx: BigNat, qy: BigNat, hash: ByteArray, r: BigNat, s: BigNat): Boolean {
        if (r.isZero || s.isZero || r >= n || s >= n) return false
        if (!onCurve(qx, qy)) return false
        var e = BigNat.fromBytes(hash)
        val excess = hash.size * 8 - n.bitLength
        if (excess > 0) e = e.divRem(BigNat.ONE.shl(excess)).first
        val w = s.modPow(n - BigNat.of(2), n)
        val u1 = (e * w) % n
        val u2 = (r * w) % n
        val point = add(multiply(u1, Point(gx, gy, BigNat.ONE)), multiply(u2, Point(qx, qy, BigNat.ONE)))
        val x = affineX(point) ?: return false
        return x % n == r
    }

    companion object {
        private val INFINITY = Point(BigNat.ONE, BigNat.ONE, BigNat.ZERO)

        val P256 = curve(
            "P-256", "1.2.840.10045.3.1.7",
            p = "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
            b = "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
            gx = "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
            gy = "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
            n = "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        )

        val P384 = curve(
            "P-384", "1.3.132.0.34",
            p = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF",
            b = "B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE8141120314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF",
            gx = "AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7",
            gy = "3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F",
            n = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973",
        )

        val P521 = curve(
            "P-521", "1.3.132.0.35",
            p = "01FF" + "FF".repeat(64),
            b = "0051953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918EF109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46B503F00",
            gx = "00C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B4D3DBAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C2E5BD66",
            gy = "011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD17273E662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE94769FD16650",
            n = "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E91386409",
        )

        /** The curve a named-curve object identifier names, or null. */
        fun byOid(oid: String): EcCurve? = listOf(P256, P384, P521).firstOrNull { it.oid == oid }

        // Each NIST prime curve has a = -3, which is p - 3.
        private fun curve(name: String, oid: String, p: String, b: String, gx: String, gy: String, n: String): EcCurve {
            val prime = BigNat.fromHex(p)
            return EcCurve(name, oid, prime, prime - BigNat.of(3), BigNat.fromHex(b), BigNat.fromHex(gx), BigNat.fromHex(gy), BigNat.fromHex(n))
        }
    }

    /** True when the base point is on the curve and has order [n]: a check of the constants. */
    internal fun constantsHold(): Boolean = onCurve(gx, gy) && multiply(n, Point(gx, gy, BigNat.ONE)).infinite
}
