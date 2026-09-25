package io.github.yuroyami.kitepdf.difftest

import java.io.ByteArrayOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pages in an `/ICCBased` space whose profile is a lookup table, the kind most CMYK press
 * profiles are (#200). The profiles are built here, so no third-party profile is needed:
 *
 * - a version 2 CMYK printer profile: `lut16Type` tables to and from Lab, which uses the
 *   legacy 16-bit encoding, so black point compensation maps Lab black through them;
 * - a version 4 RGB display profile: a `lutAtoBType` table with parametric A curves, a
 *   grid, M curves and a matrix.
 *
 * Each profile paints a row of swatches and an 8-bit image. mutool converts them through
 * Little CMS with the relative colorimetric intent and black point compensation.
 */
object IccFixtures {

    fun all(): List<OracleFixture> = listOf(
        swatches("icc-cmyk-lut16-swatches", cmykProfile, 4, CMYK_SWATCHES),
        image("icc-cmyk-lut16-image", cmykProfile, 4),
        swatches("icc-rgb-lutab-swatches", rgbProfile, 3, RGB_SWATCHES),
        image("icc-rgb-lutab-image", rgbProfile, 3),
    )

    private val CMYK_SWATCHES = listOf(
        "0 0 0 0", "1 0 0 0", "0 1 0 0", "0 0 1 0", "0 0 0 1", "1 1 1 1",
        "0.5 0.5 0.5 0", "0.2 0.7 0.1 0.3", "0.75 0.1 0.9 0.05", "0 0 0 0.5", "0.3 0.3 0.3 0.9", "0.9 0.9 0 0",
    )

    private val RGB_SWATCHES = listOf(
        "0 0 0", "1 1 1", "1 0 0", "0 1 0", "0 0 1", "0.5 0.5 0.5",
        "0.2 0.7 0.1", "0.75 0.1 0.9", "0.05 0.05 0.05", "0.9 0.6 0.3", "0.3 0.3 0.8", "0.99 0.99 0.2",
    )

    /**
     * A page of rendering intents, and the centres of its swatches in points from the bottom
     * left of the 200-point page. A test compares the swatches one by one, because the page
     * mean hides a swatch that is off.
     */
    data class SwatchPage(val fixture: OracleFixture, val swatches: List<Pair<Double, Double>>)

    /**
     * Pages whose colours convert through each rendering intent (#201): a CMYK press profile
     * with its own table for each intent and a paper white, a version 4 RGB profile, and a
     * grey profile whose white point tag is D65. mutool converts them through Little CMS.
     */
    fun intents(): List<SwatchPage> = listOf(
        intentRows("icc-intent-ri") { "/$it ri\n" },
        intentRows("icc-intent-extgstate", extGStates = true) { "/GS${INTENTS.indexOf(it)} gs\n" },
        lateIntent(),
        intentImages(),
        noBlackPointCompensation(),
        rgbVersion4Intents(),
        greyWithD65White(),
    )

    private val INTENTS = listOf("Perceptual", "RelativeColorimetric", "Saturation", "AbsoluteColorimetric")
    private val PRESS_SWATCHES = listOf("0 0 0 0", "1 0 0 0", "0.2 0.7 0.1 0.3", "1 1 1 1")

    /** Swatch [col] of row [row]: 40 points a side, four to a row, from the top of the page down. */
    private fun cell(row: Int, col: Int): String = "${10 + col * 45} ${150 - row * 45} 40 40 re f\n"
    private fun centre(row: Int, col: Int): Pair<Double, Double> = (30.0 + col * 45) to (170.0 - row * 45)

    /** One row of [PRESS_SWATCHES] per intent, each row selected by [select]. */
    private fun intentRows(name: String, extGStates: Boolean = false, select: (String) -> String): SwatchPage {
        val content = StringBuilder()
        val centres = ArrayList<Pair<Double, Double>>()
        for ((row, intent) in INTENTS.withIndex()) {
            content.append(select(intent)).append("/CS0 cs\n")
            for ((col, c) in PRESS_SWATCHES.withIndex()) {
                content.append("$c sc ").append(cell(row, col))
                centres += centre(row, col)
            }
        }
        val gs = if (extGStates) " /ExtGState << " + INTENTS.withIndex().joinToString(" ") { (i, n) -> "/GS$i << /RI /$n >>" } + " >>" else ""
        return SwatchPage(
            oracleFixture(name, content.toString(), "/ColorSpace << /CS0 [/ICCBased 5 0 R] >>$gs", listOf(pdfStream(pressProfile, "/N 4")), budget = 0.002),
            centres,
        )
    }

    /**
     * Colours set before the intent that paints them, through `sc` and through `k` in a
     * DefaultCMYK space, and an intent that `Q` takes back. MuPDF converts a colour when it
     * paints, so the later `ri` decides.
     */
    private fun lateIntent(): SwatchPage {
        val content = "/CS0 cs 0.2 0.7 0.1 0.3 sc /Perceptual ri ${cell(0, 0)}" +
            "/Saturation ri 0.2 0.7 0.1 0.3 k /AbsoluteColorimetric ri ${cell(0, 1)}" +
            "/RelativeColorimetric ri /CS0 cs 0.2 0.7 0.1 0.3 sc q /Saturation ri Q ${cell(0, 2)}" +
            "/Perceptual ri 1 0 0 0 k ${cell(1, 0)}"
        return SwatchPage(
            oracleFixture(
                "icc-intent-late", content,
                "/ColorSpace << /CS0 [/ICCBased 5 0 R] /DefaultCMYK [/ICCBased 5 0 R] >>",
                listOf(pdfStream(pressProfile, "/N 4")), budget = 0.002,
            ),
            listOf(centre(0, 0), centre(0, 1), centre(0, 2), centre(1, 0)),
        )
    }

    /** A 2 by 2 image of the press swatches with its own `/Intent`, and the same image without one under `ri`. */
    private fun intentImages(): SwatchPage {
        val samples = ByteArray(16)
        for ((i, c) in PRESS_SWATCHES.withIndex()) {
            val inks = c.split(' ').map { (it.toDouble() * 255).roundToInt() }
            for (k in 0 until 4) samples[i * 4 + k] = inks[k].toByte()
        }
        fun image(intent: String) = pdfStream(
            samples,
            "/Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace [/ICCBased 5 0 R]$intent",
        )
        val content = "/AbsoluteColorimetric ri q 90 0 0 90 5 105 cm /Im1 Do Q /Saturation ri q 90 0 0 90 105 105 cm /Im2 Do Q"
        // The image rows run from the top: samples 0 and 1 on the upper row.
        val centres = listOf(27.5 to 172.5, 72.5 to 172.5, 27.5 to 127.5, 72.5 to 127.5)
        return SwatchPage(
            oracleFixture(
                "icc-intent-image", content, "/XObject << /Im1 6 0 R /Im2 7 0 R >>",
                listOf(pdfStream(pressProfile, "/N 4"), image(" /Intent /Perceptual"), image("")), budget = 0.002,
            ),
            centres + centres.map { (x, y) -> x + 100 to y },
        )
    }

    /** The press swatches with `/UseBlackPtComp /OFF`, which moves the dark colours, then back `/ON`. */
    private fun noBlackPointCompensation(): SwatchPage {
        val content = StringBuilder("/GS0 gs /CS0 cs\n")
        for ((col, c) in PRESS_SWATCHES.withIndex()) content.append("$c sc ").append(cell(0, col))
        content.append("/GS1 gs /Perceptual ri\n")
        for ((col, c) in PRESS_SWATCHES.withIndex()) content.append("$c sc ").append(cell(1, col))
        return SwatchPage(
            oracleFixture(
                "icc-intent-no-bpc", content.toString(),
                "/ColorSpace << /CS0 [/ICCBased 5 0 R] >> /ExtGState << /GS0 << /UseBlackPtComp /OFF >> /GS1 << /UseBlackPtComp /ON >> >>",
                listOf(pdfStream(pressProfile, "/N 4")), budget = 0.002,
            ),
            (0 until 4).map { centre(0, it) } + (0 until 4).map { centre(1, it) },
        )
    }

    /** The version 4 RGB profile, which has only `A2B0`, with the relative and the perceptual intent. */
    private fun rgbVersion4Intents(): SwatchPage {
        val colours = listOf("0 0 0", "0.05 0.05 0.05", "0.2 0.7 0.1", "0.9 0.6 0.3")
        val content = StringBuilder("/CS0 cs\n")
        for ((col, c) in colours.withIndex()) content.append("$c sc ").append(cell(0, col))
        content.append("/Perceptual ri\n")
        for ((col, c) in colours.withIndex()) content.append("$c sc ").append(cell(1, col))
        return SwatchPage(
            oracleFixture(
                "icc-intent-rgb-v4", content.toString(), "/ColorSpace << /CS0 [/ICCBased 5 0 R] >>",
                listOf(pdfStream(rgbProfile, "/N 3")), budget = 0.002,
            ),
            (0 until 4).map { centre(0, it) } + (0 until 4).map { centre(1, it) },
        )
    }

    /**
     * A version 2 grey display profile whose white point tag is D65. Little CMS maps grey to
     * the D50 white of the connection space whatever the tag says, so full grey is white.
     */
    private fun greyWithD65White(): SwatchPage {
        val levels = listOf("1", "0.75", "0.5", "0.25")
        val content = StringBuilder("/CS0 cs\n")
        for ((col, g) in levels.withIndex()) content.append("$g sc ").append(cell(0, col))
        content.append("/AbsoluteColorimetric ri\n")
        for ((col, g) in levels.withIndex()) content.append("$g sc ").append(cell(1, col))
        return SwatchPage(
            oracleFixture(
                "icc-grey-d65-white", content.toString(), "/ColorSpace << /CS0 [/ICCBased 5 0 R] >>",
                listOf(pdfStream(greyD65Profile, "/N 1")), budget = 0.002,
            ),
            (0 until 4).map { centre(0, it) } + (0 until 4).map { centre(1, it) },
        )
    }

    /** Twelve swatches of 40 points in a space of [n] components, on a 190-point page. */
    private fun swatches(name: String, profile: ByteArray, n: Int, colours: List<String>): OracleFixture {
        val content = StringBuilder("/CS0 cs\n")
        for ((i, c) in colours.withIndex()) content.append("$c sc ${10 + (i % 4) * 45} ${140 - (i / 4) * 45} 40 40 re f\n")
        return oracleFixture(
            name, content.toString(),
            "/ColorSpace << /CS0 [/ICCBased 5 0 R] >>",
            listOf(pdfStream(profile, "/N $n")),
            budget = 0.002,
        )
    }

    /** A 24 by 24 image in a space of [n] components that sweeps every channel, drawn over the page. */
    private fun image(name: String, profile: ByteArray, n: Int): OracleFixture {
        val side = 24
        val samples = ByteArray(side * side * n)
        for (y in 0 until side) for (x in 0 until side) {
            val at = (y * side + x) * n
            val u = x / (side - 1.0)
            val v = y / (side - 1.0)
            val channels = if (n == 4) doubleArrayOf(u, v, 1 - u, (u * v).pow(0.5)) else doubleArrayOf(u, v, 1 - (u + v) / 2)
            for (k in 0 until n) samples[at + k] = (channels[k] * 255).roundToInt().toByte()
        }
        return oracleFixture(
            name, "q 180 0 0 180 5 5 cm /Im1 Do Q",
            "/XObject << /Im1 6 0 R >>",
            listOf(
                pdfStream(profile, "/N $n"),
                pdfStream(samples, "/Type /XObject /Subtype /Image /Width $side /Height $side /BitsPerComponent 8 /ColorSpace [/ICCBased 5 0 R]"),
            ),
            budget = 0.002,
        )
    }

    /* ─── The CMYK printer profile ─────────────────────────────────────────── */

    private val cmykProfile: ByteArray by lazy {
        val a2b = lut16(inputs = 4, outputs = 3, grid = 5, inputCurve = { it.pow(0.9) }) { cmyk -> encodeLabV2(inkModelLab(cmyk)) }
        // Lab back to ink, enough for the black point: L* 0 is all four inks, L* 100 none.
        val b2a = lut16(inputs = 3, outputs = 4, grid = 3, inputCurve = { it }) { lab ->
            val ink = 1.0 - lab[0] * 65535.0 / 65280.0
            DoubleArray(4) { ink.coerceIn(0.0, 1.0) }
        }
        profile(0x02100000, "prtr", "CMYK", listOf("A2B0" to a2b, "A2B1" to a2b, "B2A0" to b2a, "B2A1" to b2a))
    }

    /**
     * The press of [cmykProfile] with a table per intent: the perceptual one lifts the black
     * and softens the colours, the saturation one strengthens them, and the white point tag
     * is the paper, a warm Lab 95, 1, 5.
     */
    private val pressProfile: ByteArray by lazy {
        fun table(adjust: (DoubleArray) -> DoubleArray) =
            lut16(inputs = 4, outputs = 3, grid = 5, inputCurve = { it.pow(0.9) }) { cmyk -> encodeLabV2(adjust(inkModelLab(cmyk))) }
        val b2a = lut16(inputs = 3, outputs = 4, grid = 3, inputCurve = { it }) { lab ->
            val ink = 1.0 - lab[0] * 65535.0 / 65280.0
            DoubleArray(4) { ink.coerceIn(0.0, 1.0) }
        }
        profile(
            0x02100000, "prtr", "CMYK",
            listOf(
                "A2B0" to table { lab -> doubleArrayOf(6.0 + lab[0] * 0.9, lab[1] * 0.8, lab[2] * 0.8) },
                "A2B1" to table { it },
                "A2B2" to table { lab -> doubleArrayOf(lab[0], (lab[1] * 1.2).coerceIn(-127.0, 127.0), (lab[2] * 1.2).coerceIn(-127.0, 127.0)) },
                "B2A0" to b2a, "B2A1" to b2a,
            ),
            white = labToXyzD50(95.0, 1.0, 5.0),
        )
    }

    /** A version 2 grey display profile: gamma 2.2 and a D65 white point tag. */
    private val greyD65Profile: ByteArray by lazy {
        val trc = Writer().apply { sig("curv"); u32(0); u32(1); u16(563); u16(0) }.bytes()
        profile(0x02100000, "mntr", "GRAY", listOf("kTRC" to trc), white = doubleArrayOf(0.9505, 1.0, 1.0891), pcs = "XYZ ")
    }

    /** Lab to XYZ, both relative to D50. */
    private fun labToXyzD50(l: Double, a: Double, b: Double): DoubleArray {
        val fy = (l + 16.0) / 116.0
        fun finv(t: Double) = if (t > 6.0 / 29.0) t * t * t else 108.0 / 841.0 * (t - 4.0 / 29.0)
        return doubleArrayOf(0.9642 * finv(fy + a / 500.0), finv(fy), 0.8249 * finv(fy - b / 200.0))
    }

    /** A made-up press: each ink absorbs mostly one third of the spectrum, over a slightly warm paper. */
    private fun inkModelLab(ink: DoubleArray): DoubleArray {
        val (c, m, y, k) = listOf(ink[0], ink[1], ink[2], ink[3])
        val dark = 1 - 0.88 * k
        val r = 0.92 * (1 - 0.86 * c) * (1 - 0.12 * m) * (1 - 0.04 * y) * dark
        val g = 0.90 * (1 - 0.35 * c) * (1 - 0.88 * m) * (1 - 0.10 * y) * dark
        val b = 0.86 * (1 - 0.10 * c) * (1 - 0.30 * m) * (1 - 0.90 * y) * dark
        return linearRgbToLab(r, g, b)
    }

    /* ─── The RGB display profile ──────────────────────────────────────────── */

    private val rgbProfile: ByteArray by lazy {
        val grid = 9
        val clut = DoubleArray(grid * grid * grid * 3)
        for (i in 0 until grid) for (j in 0 until grid) for (l in 0 until grid) {
            // The A curves have already made the channels linear; a wide-gamut matrix gives XYZ.
            val lab = wideGamutToLab(i / (grid - 1.0), j / (grid - 1.0), l / (grid - 1.0))
            val at = ((i * grid + j) * grid + l) * 3
            clut[at] = lab[0] / 100.0
            clut[at + 1] = (lab[1] + 128.0) / 255.0
            clut[at + 2] = (lab[2] + 128.0) / 255.0
        }
        profile(0x04200000, "mntr", "RGB ", listOf("A2B0" to lutAtoB(grid, clut)))
    }

    /** Linear RGB in Display P3 primaries, D50-adapted, to Lab. */
    private fun wideGamutToLab(r: Double, g: Double, b: Double): DoubleArray {
        val x = 0.5151 * r + 0.2920 * g + 0.1571 * b
        val y = 0.2412 * r + 0.6922 * g + 0.0666 * b
        val z = -0.0011 * r + 0.0419 * g + 0.7841 * b
        return xyzToLab(x, y, z)
    }

    /* ─── Colour helpers ───────────────────────────────────────────────────── */

    /** Linear sRGB, D50-adapted as in the sRGB profile, to Lab. */
    private fun linearRgbToLab(r: Double, g: Double, b: Double): DoubleArray = xyzToLab(
        0.4361 * r + 0.3851 * g + 0.1431 * b,
        0.2225 * r + 0.7169 * g + 0.0606 * b,
        0.0139 * r + 0.0971 * g + 0.7141 * b,
    )

    private fun xyzToLab(x: Double, y: Double, z: Double): DoubleArray {
        fun f(t: Double) = if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (841.0 / 108.0) * t + 4.0 / 29.0
        val fx = f(x / 0.9642)
        val fy = f(y)
        val fz = f(z / 0.8249)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** Lab in the legacy 16-bit encoding of lut16Type, normalized: L* 100 at FF00, a* 0 at 8000. */
    private fun encodeLabV2(lab: DoubleArray): DoubleArray = doubleArrayOf(
        lab[0] / 100.0 * 65280.0 / 65535.0,
        (lab[1] + 128.0) * 256.0 / 65535.0,
        (lab[2] + 128.0) * 256.0 / 65535.0,
    )

    /* ─── ICC writing ──────────────────────────────────────────────────────── */

    /**
     * A `lut16Type` table: [inputCurve] as 256-entry input tables, a grid of [grid] points
     * a side whose values [clut] gives for the normalized inputs, and identity output tables.
     */
    private fun lut16(inputs: Int, outputs: Int, grid: Int, inputCurve: (Double) -> Double, clut: (DoubleArray) -> DoubleArray): ByteArray {
        val out = Writer()
        out.sig("mft2"); out.u32(0)
        out.u8(inputs); out.u8(outputs); out.u8(grid); out.u8(0)
        for (i in 0 until 9) out.s15f16(if (i % 4 == 0) 1.0 else 0.0)
        out.u16(256); out.u16(2)
        repeat(inputs) { for (e in 0 until 256) out.u16((inputCurve(e / 255.0) * 65535).roundToInt()) }
        var points = 1
        repeat(inputs) { points *= grid }
        for (index in 0 until points) {
            var rest = index
            val v = DoubleArray(inputs)
            for (d in inputs - 1 downTo 0) {
                v[d] = (rest % grid) / (grid - 1.0)
                rest /= grid
            }
            for (value in clut(v)) out.u16((value.coerceIn(0.0, 1.0) * 65535).roundToInt())
        }
        repeat(outputs) { out.u16(0); out.u16(65535) }
        return out.bytes()
    }

    /**
     * A `lutAtoBType` table from RGB to Lab: sRGB-shaped parametric A curves, the [grid]
     * points a side of [clut], identity M curves, a matrix that swaps nothing, and
     * identity B curves.
     */
    private fun lutAtoB(grid: Int, clut: DoubleArray): ByteArray {
        val b = Writer().apply { repeat(3) { sig("curv"); u32(0); u32(0) } }.bytes()
        val matrix = Writer().apply { for (i in 0 until 12) s15f16(if (i < 9 && i % 4 == 0) 1.0 else 0.0) }.bytes()
        val m = Writer().apply { repeat(3) { sig("para"); u32(0); u16(0); u16(0); s15f16(1.0) } }.bytes()
        val table = Writer().apply {
            for (i in 0 until 16) u8(if (i < 3) grid else 0)
            u8(2); u8(0); u8(0); u8(0)
            for (v in clut) u16((v.coerceIn(0.0, 1.0) * 65535).roundToInt())
            while (size % 4 != 0) u8(0)
        }.bytes()
        val a = Writer().apply {
            repeat(3) {
                sig("para"); u32(0); u16(3); u16(0)
                for (p in doubleArrayOf(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)) s15f16(p)
            }
        }.bytes()
        val header = 32
        val bOff = header
        val matrixOff = bOff + b.size
        val mOff = matrixOff + matrix.size
        val clutOff = mOff + m.size
        val aOff = clutOff + table.size
        val out = Writer()
        out.sig("mAB "); out.u32(0); out.u8(3); out.u8(3); out.u16(0)
        out.u32(bOff); out.u32(matrixOff); out.u32(mOff); out.u32(clutOff); out.u32(aOff)
        out.raw(b); out.raw(matrix); out.raw(m); out.raw(table); out.raw(a)
        return out.bytes()
    }

    /** A profile of [version], [deviceClass] and [space] with the connection space [pcs], the white point tag [white] and [tags]. */
    private fun profile(
        version: Int, deviceClass: String, space: String, tags: List<Pair<String, ByteArray>>,
        white: DoubleArray = doubleArrayOf(0.9642, 1.0, 0.8249), pcs: String = "Lab ",
    ): ByteArray {
        val whiteTag = Writer().apply { sig("XYZ "); u32(0); s15f16(white[0]); s15f16(white[1]); s15f16(white[2]) }.bytes()
        val all = listOf("wtpt" to whiteTag) + tags
        // A table shared by two tags is written once.
        val blocks = LinkedHashMap<ByteArray, Int>()
        var offset = 128 + 4 + 12 * all.size
        for ((_, data) in all) if (data !in blocks) {
            blocks[data] = offset
            offset += (data.size + 3) and 3.inv()
        }
        val out = Writer()
        out.u32(offset); out.u32(0); out.u32(version); out.sig(deviceClass); out.sig(space); out.sig(pcs)
        repeat(12) { out.u8(0) }
        out.sig("acsp"); out.u32(0); out.u32(0); out.u32(0); out.u32(0); out.u32(0); out.u32(0); out.u32(0)
        out.s15f16(0.9642); out.s15f16(1.0); out.s15f16(0.8249)
        out.u32(0)
        repeat(44) { out.u8(0) }
        out.u32(all.size)
        for ((name, data) in all) { out.sig(name); out.u32(blocks.getValue(data)); out.u32(data.size) }
        for ((data, _) in blocks) {
            out.raw(data)
            while (out.size % 4 != 0) out.u8(0)
        }
        return out.bytes()
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        val size: Int get() = out.size()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v) }
        fun s15f16(v: Double) = u32((v * 65536).roundToInt())
        fun sig(s: String) = s.forEach { u8(it.code) }
        fun raw(b: ByteArray) = out.write(b)
        fun bytes(): ByteArray = out.toByteArray()
    }
}
