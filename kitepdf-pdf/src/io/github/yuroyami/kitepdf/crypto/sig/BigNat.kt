package io.github.yuroyami.kitepdf.crypto.sig

/**
 * A non-negative integer of any size, for the arithmetic of RSA and elliptic curve signatures
 * (#203). The limbs are 32-bit and little-endian, with no zero limb on top, so zero has none.
 */
internal class BigNat private constructor(private val mag: IntArray) : Comparable<BigNat> {

    val isZero: Boolean get() = mag.isEmpty()

    val bitLength: Int get() = if (mag.isEmpty()) 0 else (mag.size - 1) * 32 + (32 - mag.last().countLeadingZeroBits())

    fun testBit(n: Int): Boolean {
        val i = n ushr 5
        return i < mag.size && (mag[i] ushr (n and 31)) and 1 != 0
    }

    override fun compareTo(other: BigNat): Int {
        if (mag.size != other.mag.size) return mag.size.compareTo(other.mag.size)
        for (i in mag.indices.reversed()) {
            val a = mag[i].toLong() and MASK
            val b = other.mag[i].toLong() and MASK
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is BigNat && mag.contentEquals(other.mag)

    override fun hashCode(): Int = mag.contentHashCode()

    operator fun plus(other: BigNat): BigNat {
        val (a, b) = if (mag.size >= other.mag.size) mag to other.mag else other.mag to mag
        val r = IntArray(a.size + 1)
        var carry = 0L
        for (i in a.indices) {
            val s = (a[i].toLong() and MASK) + (if (i < b.size) b[i].toLong() and MASK else 0L) + carry
            r[i] = s.toInt()
            carry = s ushr 32
        }
        r[a.size] = carry.toInt()
        return normalized(r)
    }

    /** This minus [other], which must not be larger. */
    operator fun minus(other: BigNat): BigNat {
        require(this >= other) { "negative result" }
        val r = IntArray(mag.size)
        var borrow = 0L
        for (i in mag.indices) {
            val t = (mag[i].toLong() and MASK) - (if (i < other.mag.size) other.mag[i].toLong() and MASK else 0L) - borrow
            r[i] = t.toInt()
            borrow = if (t < 0) 1L else 0L
        }
        return normalized(r)
    }

    operator fun times(other: BigNat): BigNat {
        if (isZero || other.isZero) return ZERO
        val r = IntArray(mag.size + other.mag.size)
        for (i in mag.indices) {
            val a = mag[i].toLong() and MASK
            var carry = 0L
            for (j in other.mag.indices) {
                // a * b + r + carry stays below 2^64, so the unsigned view holds it.
                val t = (a * (other.mag[j].toLong() and MASK)).toULong() + (r[i + j].toLong() and MASK).toULong() + carry.toULong()
                r[i + j] = t.toInt()
                carry = (t shr 32).toLong()
            }
            r[i + other.mag.size] = carry.toInt()
        }
        return normalized(r)
    }

    operator fun rem(m: BigNat): BigNat = divRem(m).second

    /** The quotient and the remainder of this by [divisor] (Knuth, TAOCP 4.3.1, algorithm D). */
    fun divRem(divisor: BigNat): Pair<BigNat, BigNat> {
        require(!divisor.isZero) { "division by zero" }
        if (this < divisor) return ZERO to this
        if (divisor.mag.size == 1) {
            val d = divisor.mag[0].toLong() and MASK
            val q = IntArray(mag.size)
            var r = 0L
            for (i in mag.indices.reversed()) {
                val cur = ((r shl 32) or (mag[i].toLong() and MASK)).toULong()
                q[i] = (cur / d.toULong()).toInt()
                r = (cur % d.toULong()).toLong()
            }
            return normalized(q) to of(r)
        }
        val shift = divisor.mag.last().countLeadingZeroBits()
        val v = shiftLeft(divisor.mag, shift, extra = 0)
        val u = shiftLeft(mag, shift, extra = 1)
        val n = v.size
        val m = u.size - n - 1
        val q = IntArray(m + 1)
        val vTop = (v[n - 1].toLong() and MASK).toULong()
        val vNext = (v[n - 2].toLong() and MASK).toULong()
        val base = 1UL shl 32
        for (j in m downTo 0) {
            val num = ((u[j + n].toLong() and MASK).toULong() shl 32) or (u[j + n - 1].toLong() and MASK).toULong()
            var qhat = num / vTop
            var rhat = num % vTop
            while (qhat >= base || qhat * vNext > ((rhat shl 32) or (u[j + n - 2].toLong() and MASK).toULong())) {
                qhat -= 1UL
                rhat += vTop
                if (rhat >= base) break
            }
            // Multiply and subtract; a negative result adds one divisor back.
            var borrow = 0L
            var carry = 0UL
            for (i in 0 until n) {
                val p = qhat * (v[i].toLong() and MASK).toULong() + carry
                carry = p shr 32
                val t = (u[i + j].toLong() and MASK) - (p and 0xFFFFFFFFUL).toLong() - borrow
                u[i + j] = t.toInt()
                borrow = if (t < 0) 1L else 0L
            }
            val t = (u[j + n].toLong() and MASK) - carry.toLong() - borrow
            u[j + n] = t.toInt()
            if (t < 0) {
                qhat -= 1UL
                var c = 0L
                for (i in 0 until n) {
                    val s = (u[i + j].toLong() and MASK) + (v[i].toLong() and MASK) + c
                    u[i + j] = s.toInt()
                    c = s ushr 32
                }
                u[j + n] = u[j + n] + c.toInt()
            }
            q[j] = qhat.toInt()
        }
        val remainder = IntArray(n)
        for (i in 0 until n) {
            val low = (u[i].toLong() and MASK) ushr shift
            val high = if (shift == 0 || i + 1 >= u.size) 0L else ((u[i + 1].toLong() and MASK) shl (32 - shift)) and MASK
            remainder[i] = (low or high).toInt()
        }
        return normalized(q) to normalized(remainder)
    }

    /** This times 2 to the power [bits]. */
    fun shl(bits: Int): BigNat {
        if (isZero || bits == 0) return this
        val limbs = bits / 32
        val shifted = shiftLeft(mag, bits % 32, extra = 1)
        return normalized(IntArray(limbs) + shifted)
    }

    /** This to the power [exponent], modulo [modulus]. */
    fun modPow(exponent: BigNat, modulus: BigNat): BigNat {
        var result = ONE % modulus
        val base = this % modulus
        for (i in exponent.bitLength - 1 downTo 0) {
            result = (result * result) % modulus
            if (exponent.testBit(i)) result = (result * base) % modulus
        }
        return result
    }

    /** The big-endian bytes of this, left-padded with zeros to [length]. Null when it does not fit. */
    fun toBytes(length: Int = (bitLength + 7) / 8): ByteArray? {
        if ((bitLength + 7) / 8 > length) return null
        val out = ByteArray(length)
        for (i in 0 until length) {
            val limb = i / 4
            if (limb >= mag.size) break
            out[length - 1 - i] = (mag[limb] ushr (8 * (i % 4))).toByte()
        }
        return out
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL

        val ZERO = BigNat(IntArray(0))
        val ONE = of(1)

        fun of(value: Long): BigNat {
            require(value >= 0)
            return normalized(intArrayOf(value.toInt(), (value ushr 32).toInt()))
        }

        /** The unsigned big-endian integer in [bytes] from [offset], [length] bytes long. */
        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): BigNat {
            val limbs = IntArray((length + 3) / 4)
            for (i in 0 until length) {
                val b = bytes[offset + length - 1 - i].toInt() and 0xFF
                limbs[i / 4] = limbs[i / 4] or (b shl (8 * (i % 4)))
            }
            return normalized(limbs)
        }

        fun fromHex(hex: String): BigNat {
            val clean = hex.filter { !it.isWhitespace() }
            val padded = if (clean.length % 2 == 1) "0$clean" else clean
            return fromBytes(ByteArray(padded.length / 2) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        private fun normalized(limbs: IntArray): BigNat {
            var size = limbs.size
            while (size > 0 && limbs[size - 1] == 0) size--
            return BigNat(if (size == limbs.size) limbs else limbs.copyOf(size))
        }

        private fun shiftLeft(limbs: IntArray, shift: Int, extra: Int): IntArray {
            val out = IntArray(limbs.size + extra)
            if (shift == 0) {
                limbs.copyInto(out)
                return out
            }
            var carry = 0
            for (i in limbs.indices) {
                out[i] = (limbs[i] shl shift) or carry
                carry = limbs[i] ushr (32 - shift)
            }
            if (extra > 0) out[limbs.size] = carry
            return out
        }
    }
}
