package io.github.yuroyami.kitepdf.core.render

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading a matrix/TRC ICC profile and colour-managing through it. */
class IccProfileTest {

    /** Build a profile with the tags a matrix/TRC RGB profile carries. */
    private fun rgbProfile(gamma: Double, colorants: List<Triple<Double, Double, Double>>): ByteArray {
        val tags = ArrayList<Pair<String, ByteArray>>()
        fun xyz(x: Double, y: Double, z: Double): ByteArray {
            val b = ByteArray(20)
            "XYZ ".forEachIndexed { i, c -> b[i] = c.code.toByte() }
            s15(b, 8, x); s15(b, 12, y); s15(b, 16, z)
            return b
        }
        fun curv(g: Double): ByteArray {
            val b = ByteArray(14)
            "curv".forEachIndexed { i, c -> b[i] = c.code.toByte() }
            u32(b, 8, 1)
            val fixed = (g * 256.0).toInt()
            b[12] = ((fixed ushr 8) and 0xFF).toByte(); b[13] = (fixed and 0xFF).toByte()
            return b
        }
        tags += "rXYZ" to xyz(colorants[0].first, colorants[0].second, colorants[0].third)
        tags += "gXYZ" to xyz(colorants[1].first, colorants[1].second, colorants[1].third)
        tags += "bXYZ" to xyz(colorants[2].first, colorants[2].second, colorants[2].third)
        tags += "rTRC" to curv(gamma)
        tags += "gTRC" to curv(gamma)
        tags += "bTRC" to curv(gamma)
        tags += "wtpt" to xyz(0.9642, 1.0, 0.8249)
        return assemble("RGB ", tags)
    }

    private fun assemble(space: String, tags: List<Pair<String, ByteArray>>): ByteArray {
        val tableSize = 4 + tags.size * 12
        val header = 128
        var offset = header + tableSize
        val body = ArrayList<Byte>()
        val entries = ArrayList<Triple<String, Int, Int>>()
        for ((name, data) in tags) {
            entries += Triple(name, offset, data.size)
            for (b in data) body.add(b)
            offset += data.size
        }
        val out = ByteArray(header + tableSize + body.size)
        u32(out, 0, out.size)
        space.forEachIndexed { i, c -> out[16 + i] = c.code.toByte() }
        "XYZ ".forEachIndexed { i, c -> out[20 + i] = c.code.toByte() }
        u32(out, 128, tags.size)
        entries.forEachIndexed { i, (name, off, len) ->
            val at = 132 + i * 12
            name.forEachIndexed { k, c -> out[at + k] = c.code.toByte() }
            u32(out, at + 4, off); u32(out, at + 8, len)
        }
        body.forEachIndexed { i, b -> out[header + tableSize + i] = b }
        return out
    }

    private fun u32(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v ushr 24) and 0xFF).toByte(); b[at + 1] = ((v ushr 16) and 0xFF).toByte()
        b[at + 2] = ((v ushr 8) and 0xFF).toByte(); b[at + 3] = (v and 0xFF).toByte()
    }

    private fun s15(b: ByteArray, at: Int, v: Double) = u32(b, at, (v * 65536.0).toInt())

    /** sRGB's own primaries, D50-adapted, which is what a real sRGB profile stores. */
    private val srgbColorants = listOf(
        Triple(0.4360, 0.2225, 0.0139),
        Triple(0.3851, 0.7169, 0.0971),
        Triple(0.1431, 0.0606, 0.7141),
    )

    @Test
    fun an_srgb_like_profile_round_trips_its_primaries() {
        val p = IccProfile.parse(rgbProfile(2.2, srgbColorants))
        assertNotNull(p)
        assertEquals(3, p.componentCount)
        val white = p.toRgb(doubleArrayOf(1.0, 1.0, 1.0))
        assertTrue(white.r > 0.98 && white.g > 0.98 && white.b > 0.98, "white stays white: $white")
        val black = p.toRgb(doubleArrayOf(0.0, 0.0, 0.0))
        assertTrue(black.r < 0.01 && black.g < 0.01 && black.b < 0.01, "black stays black: $black")
        val red = p.toRgb(doubleArrayOf(1.0, 0.0, 0.0))
        assertTrue(red.r > 0.9 && red.g < 0.2 && red.b < 0.2, "red stays red: $red")
    }

    @Test
    fun a_wider_gamut_profile_reads_differently_from_device_rgb() {
        // Adobe RGB primaries: the same numbers mean a more saturated green.
        val wide = listOf(
            Triple(0.6097, 0.3111, 0.0195),
            Triple(0.2052, 0.6257, 0.0609),
            Triple(0.1492, 0.0632, 0.7448),
        )
        val p = IccProfile.parse(rgbProfile(2.2, wide))!!
        val green = p.toRgb(doubleArrayOf(0.0, 1.0, 0.0))
        assertTrue(green.g > 0.9, "still green: $green")
        assertTrue(green.r < 0.5, "and not device green: $green")
    }

    @Test
    fun a_grey_profile_uses_its_tone_curve() {
        val curve = ByteArray(14)
        "curv".forEachIndexed { i, c -> curve[i] = c.code.toByte() }
        u32(curve, 8, 1)
        val fixed = (2.2 * 256.0).toInt()
        curve[12] = ((fixed ushr 8) and 0xFF).toByte(); curve[13] = (fixed and 0xFF).toByte()
        val wtpt = ByteArray(20)
        "XYZ ".forEachIndexed { i, c -> wtpt[i] = c.code.toByte() }
        s15(wtpt, 8, 0.9642); s15(wtpt, 12, 1.0); s15(wtpt, 16, 0.8249)
        val p = IccProfile.parse(assemble("GRAY", listOf("kTRC" to curve, "wtpt" to wtpt)))
        assertNotNull(p)
        assertEquals(1, p.componentCount)
        val mid = p.toRgb(doubleArrayOf(0.5))
        // 0.5 encoded, gamma 2.2, re-encoded for sRGB: mid grey, and neutral.
        assertTrue(mid.r in 0.4..0.6, "mid grey: $mid")
        assertTrue(kotlin.math.abs(mid.r - mid.g) < 0.02 && kotlin.math.abs(mid.g - mid.b) < 0.02, "neutral: $mid")
    }

    @Test
    fun a_lookup_table_profile_is_refused_so_the_caller_falls_back() {
        val a2b = ByteArray(32)
        "mft2".forEachIndexed { i, c -> a2b[i] = c.code.toByte() }
        assertNull(IccProfile.parse(assemble("RGB ", listOf("A2B0" to a2b))))
    }

    @Test
    fun rubbish_is_refused() {
        assertNull(IccProfile.parse(ByteArray(0)))
        assertNull(IccProfile.parse(ByteArray(200)))
        assertNull(IccProfile.parse("not a profile at all".encodeToByteArray()))
    }

    /** A `curv` table holding the real sRGB tone curve. */
    private fun srgbCurve(entries: Int = 1024): ByteArray {
        val b = ByteArray(12 + entries * 2)
        "curv".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        u32(b, 8, entries)
        for (i in 0 until entries) {
            val x = i.toDouble() / (entries - 1)
            val lin = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
            val v = (lin * 65535.0 + 0.5).toInt().coerceIn(0, 65535)
            b[12 + i * 2] = ((v ushr 8) and 0xFF).toByte()
            b[13 + i * 2] = (v and 0xFF).toByte()
        }
        return b
    }

    private fun srgbProfile(): ByteArray {
        fun xyz(t: Triple<Double, Double, Double>): ByteArray {
            val b = ByteArray(20)
            "XYZ ".forEachIndexed { i, c -> b[i] = c.code.toByte() }
            s15(b, 8, t.first); s15(b, 12, t.second); s15(b, 16, t.third)
            return b
        }
        val curve = srgbCurve()
        return assemble(
            "RGB ",
            listOf(
                "rXYZ" to xyz(srgbColorants[0]), "gXYZ" to xyz(srgbColorants[1]), "bXYZ" to xyz(srgbColorants[2]),
                "rTRC" to curve, "gTRC" to curve, "bTRC" to curve,
                "wtpt" to xyz(Triple(0.9642, 1.0, 0.8249)),
            ),
        )
    }

    @Test
    fun a_profile_that_is_srgb_reports_itself_as_identity() {
        val p = IccProfile.parse(srgbProfile())!!
        assertTrue(p.isIdentity, "an sRGB profile transforms to itself, so it should be skipped")
    }

    @Test
    fun a_wider_gamut_profile_is_not_identity() {
        val wide = listOf(
            Triple(0.6097, 0.3111, 0.0195),
            Triple(0.2052, 0.6257, 0.0609),
            Triple(0.1492, 0.0632, 0.7448),
        )
        assertTrue(!IccProfile.parse(rgbProfile(2.2, wide))!!.isIdentity, "AdobeRGB really does change colours")
    }
}
