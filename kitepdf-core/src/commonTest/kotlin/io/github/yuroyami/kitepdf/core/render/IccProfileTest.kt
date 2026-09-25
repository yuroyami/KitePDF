package io.github.yuroyami.kitepdf.core.render

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Reading matrix/TRC and lookup-table ICC profiles and colour-managing through them. */
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

    private fun assemble(space: String, tags: List<Pair<String, ByteArray>>, pcs: String = "XYZ ", deviceClass: String = "mntr"): ByteArray {
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
        deviceClass.forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        space.forEachIndexed { i, c -> out[16 + i] = c.code.toByte() }
        pcs.forEachIndexed { i, c -> out[20 + i] = c.code.toByte() }
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
    fun a_lookup_table_that_is_cut_short_is_refused_so_the_caller_falls_back() {
        val a2b = ByteArray(32)
        "mft2".forEachIndexed { i, c -> a2b[i] = c.code.toByte() }
        assertNull(IccProfile.parse(assemble("RGB ", listOf("A2B0" to a2b))))
        assertNull(IccProfile.parse(assemble("CMYK", listOf("A2B0" to a2b), pcs = "Lab ")))
    }

    /**
     * A CMYK to Lab `lut16Type` table of two points a side in which only black ink darkens:
     * L* runs from [paper] with no black to [ink] with full black, and a* and b* stay 0.
     */
    private fun blackOnlyCmyk(paper: Double, ink: Double): ByteArray {
        val points = 16
        val b = ByteArray(52 + 4 * 2 * 2 + points * 3 * 2 + 3 * 2 * 2)
        "mft2".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[8] = 4; b[9] = 3; b[10] = 2
        for (i in 0 until 9) s15(b, 12 + 4 * i, if (i % 4 == 0) 1.0 else 0.0)
        fun u16(at: Int, v: Int) { b[at] = ((v ushr 8) and 0xFF).toByte(); b[at + 1] = (v and 0xFF).toByte() }
        u16(48, 2); u16(50, 2)
        var at = 52
        repeat(4) { u16(at, 0); u16(at + 2, 65535); at += 4 }
        for (node in 0 until points) {
            // The last input, black, varies fastest.
            val l = if (node % 2 == 1) ink else paper
            u16(at, (l / 100.0 * 65280.0).toInt()); u16(at + 2, 0x8000); u16(at + 4, 0x8000)
            at += 6
        }
        repeat(3) { u16(at, 0); u16(at + 2, 65535); at += 4 }
        return b
    }

    @Test
    fun a_lookup_table_cmyk_profile_converts_through_its_table() {
        val p = IccProfile.parse(assemble("CMYK", listOf("A2B1" to blackOnlyCmyk(100.0, 20.0)), pcs = "Lab ", deviceClass = "scnr"))
        assertNotNull(p)
        assertEquals(4, p.componentCount)
        val white = p.toRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        assertEquals(1.0, white.r, 1e-6)
        val half = p.toRgb(doubleArrayOf(0.7, 0.2, 0.9, 0.5))
        assertTrue(kotlin.math.abs(half.r - half.g) < 0.01 && kotlin.math.abs(half.g - half.b) < 0.01, "only black darkens, so grey: $half")
        // Black point compensation takes the darkest ink, L* 20, to black.
        val black = p.toRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertTrue(black.r < 0.01, "the darkest ink is black: $black")
        assertTrue(half.r > black.r && half.r < white.r, "black ink darkens steadily: $half")
    }

    @Test
    fun a_paper_close_to_white_lands_on_white_as_little_cms_makes_it() {
        val p = IccProfile.parse(assemble("CMYK", listOf("A2B1" to blackOnlyCmyk(93.0, 20.0)), pcs = "Lab ", deviceClass = "scnr"))!!
        val white = p.toRgb(doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        assertTrue(white.r == 1.0 && white.g == 1.0 && white.b == 1.0, "no ink is white: $white")
    }

    /** An XYZ tag holding [x], [y] and [z]. */
    private fun xyzTag(x: Double, y: Double, z: Double): ByteArray = ByteArray(20).also { b ->
        "XYZ ".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        s15(b, 8, x); s15(b, 12, y); s15(b, 16, z)
    }

    /**
     * A black-only CMYK profile whose darkest ink is L* 45 perceptually, 20 colorimetrically and
     * 35 for saturation. Little CMS clamps a black point to L* 50, so each can compensate to black.
     */
    private fun intentCmyk(deviceClass: String = "scnr", white: ByteArray? = null): ByteArray = assemble(
        "CMYK",
        listOf("A2B0" to blackOnlyCmyk(100.0, 45.0), "A2B1" to blackOnlyCmyk(100.0, 20.0), "A2B2" to blackOnlyCmyk(100.0, 35.0)) +
            listOfNotNull(white?.let { "wtpt" to it }),
        pcs = "Lab ", deviceClass = deviceClass,
    )

    @Test
    fun each_intent_converts_through_its_own_table() {
        val p = IccProfile.parse(intentCmyk())!!
        assertSame(p, p.forRendering(KiteRenderingIntent.RelativeColorimetric, true), "the profile as read is the default")
        fun black(intent: KiteRenderingIntent) = p.forRendering(intent, blackPointCompensation = false).toRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)).g
        val perceptual = black(KiteRenderingIntent.Perceptual)
        val saturation = black(KiteRenderingIntent.Saturation)
        val relative = black(KiteRenderingIntent.RelativeColorimetric)
        assertTrue(perceptual > saturation && saturation > relative, "L* 45, 35 and 20: $perceptual, $saturation, $relative")
        // The absolute intent reads the colorimetric table, and this profile's medium is D50.
        assertEquals(relative, black(KiteRenderingIntent.AbsoluteColorimetric), 1e-9)
        assertSame(p.forRendering(KiteRenderingIntent.Perceptual, false), p.forRendering(KiteRenderingIntent.Perceptual, false), "built once")
    }

    @Test
    fun black_point_compensation_takes_the_darkest_ink_of_each_intent_to_black() {
        val p = IccProfile.parse(intentCmyk())!!
        for (intent in listOf(KiteRenderingIntent.Perceptual, KiteRenderingIntent.RelativeColorimetric, KiteRenderingIntent.Saturation)) {
            val black = p.forRendering(intent, true).toRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0))
            assertTrue(black.g < 0.01, "$intent: $black")
        }
        // The absolute intent never compensates.
        assertTrue(p.forRendering(KiteRenderingIntent.AbsoluteColorimetric, true).toRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)).g > 0.1)
    }

    @Test
    fun the_absolute_intent_keeps_the_colour_of_the_paper() {
        // A paper of L* 90: the colorimetric table maps it to white, the absolute intent back to the paper.
        val y = ((90.0 + 16.0) / 116.0).pow(3)
        val paper = xyzTag(0.9642 * y, y, 0.8249 * y)
        val p = IccProfile.parse(intentCmyk(white = paper))!!
        val none = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        assertEquals(1.0, p.toRgb(none).g, 1e-6)
        val absolute = p.forRendering(KiteRenderingIntent.AbsoluteColorimetric, true).toRgb(none)
        assertTrue(absolute.g in 0.85..0.9 && kotlin.math.abs(absolute.r - absolute.b) < 0.01, "L* 90 grey: $absolute")
        // A version 2 display profile's medium reads as D50, as Little CMS reads it, so its paper is white.
        val display = IccProfile.parse(intentCmyk(deviceClass = "mntr", white = paper))!!
        assertEquals(1.0, display.forRendering(KiteRenderingIntent.AbsoluteColorimetric, true).toRgb(none).g, 0.002)
    }

    @Test
    fun a_grey_profile_maps_full_grey_to_white_whatever_its_white_point_tag_says() {
        val curve = ByteArray(14)
        "curv".forEachIndexed { i, c -> curve[i] = c.code.toByte() }
        u32(curve, 8, 1)
        curve[12] = 2; curve[13] = 0x33
        val p = IccProfile.parse(assemble("GRAY", listOf("kTRC" to curve, "wtpt" to xyzTag(0.9505, 1.0, 1.0891))))!!
        val white = p.toRgb(doubleArrayOf(1.0))
        assertTrue(white.r > 0.995 && white.g > 0.995 && white.b > 0.995, "white, not tinted by the D65 tag: $white")
    }

    @Test
    fun a_space_built_on_a_profile_converts_through_its_intent() {
        val icc = KiteColorSpace.IccBased(IccProfile.parse(intentCmyk())!!)
        assertSame(icc, icc.withIntent(KiteRenderingIntent.RelativeColorimetric))
        assertSame(KiteColorSpace.DeviceCMYK, KiteColorSpace.DeviceCMYK.withIntent(KiteRenderingIntent.Perceptual))
        val perceptual = icc.withIntent(KiteRenderingIntent.Perceptual, blackPointCompensation = false)
        assertSame(perceptual, icc.withIntent(KiteRenderingIntent.Perceptual, blackPointCompensation = false), "built once")
        val k = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        assertTrue(perceptual.toRgb(k).g > icc.withIntent(KiteRenderingIntent.RelativeColorimetric, false).toRgb(k).g)
        // The state converts its current colour again when the intent changes.
        val state = GraphicsState(fillColorSpace = icc, fillColor = icc.toRgb(k), fillComponents = k)
            .withColorRendering(KiteRenderingIntent.Perceptual, blackPointCompensation = false)
        assertEquals(perceptual.toRgb(k), state.fillColor)
    }

    @Test
    fun the_same_profile_is_read_once() {
        val bytes = assemble("CMYK", listOf("A2B1" to blackOnlyCmyk(100.0, 20.0)), pcs = "Lab ", deviceClass = "scnr")
        kotlin.test.assertSame(IccProfile.parse(bytes), IccProfile.parse(bytes.copyOf()))
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
