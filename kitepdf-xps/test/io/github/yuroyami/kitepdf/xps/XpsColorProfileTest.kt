package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An image brush whose ImageSource names a colour profile through ColorConvertedBitmap reads
 * its samples through that profile (ECMA-388; #895). lcms2 maps the grey 128 of a linear RGB
 * profile with the sRGB primaries to 188.
 */
class XpsColorProfileTest {

    /** A 2 by 1 24-bit BMP of grey (128, 128, 128). */
    private fun grayBmp(): ByteArray {
        val bmp = XpsFixtures.bmp2x1()
        for (i in 54 until 60) bmp[i] = 128.toByte()
        return bmp
    }

    /** An ICC v2 RGB matrix profile with the sRGB primaries and a gamma of [gamma]. */
    private fun rgbProfile(gamma: Double): ByteArray {
        fun u32(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        }
        fun xyz(x: Double, y: Double, z: Double) = ByteArray(20).also { b ->
            "XYZ ".forEachIndexed { i, c -> b[i] = c.code.toByte() }
            u32(b, 8, (x * 65536).toInt()); u32(b, 12, (y * 65536).toInt()); u32(b, 16, (z * 65536).toInt())
        }
        fun curv(g: Double) = ByteArray(14).also { b ->
            "curv".forEachIndexed { i, c -> b[i] = c.code.toByte() }
            u32(b, 8, 1)
            val fixed = (g * 256).toInt()
            b[12] = (fixed ushr 8).toByte(); b[13] = fixed.toByte()
        }
        val tags = listOf(
            "rXYZ" to xyz(0.4360, 0.2225, 0.0139), "gXYZ" to xyz(0.3851, 0.7169, 0.0971), "bXYZ" to xyz(0.1431, 0.0606, 0.7141),
            "rTRC" to curv(gamma), "gTRC" to curv(gamma), "bTRC" to curv(gamma), "wtpt" to xyz(0.9642, 1.0, 0.8249),
        )
        val table = 4 + tags.size * 12
        val out = ByteArray(128 + table + tags.sumOf { it.second.size })
        u32(out, 0, out.size)
        "mntr".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        "RGB ".forEachIndexed { i, c -> out[16 + i] = c.code.toByte() }
        "XYZ ".forEachIndexed { i, c -> out[20 + i] = c.code.toByte() }
        u32(out, 128, tags.size)
        var offset = 128 + table
        tags.forEachIndexed { i, (name, data) ->
            val at = 132 + i * 12
            name.forEachIndexed { k, c -> out[at + k] = c.code.toByte() }
            u32(out, at + 4, offset); u32(out, at + 8, data.size)
            data.copyInto(out, offset)
            offset += data.size
        }
        return out
    }

    private fun drawnGrey(imageSource: String): Int {
        val content = """<Path Data="M0,0 L40,0 40,20 0,20 Z"><Path.Fill>
            <ImageBrush ImageSource="$imageSource" Viewbox="0,0,2,1" Viewport="0,0,40,20"
              ViewboxUnits="Absolute" ViewportUnits="Absolute"/>
            </Path.Fill></Path>"""
        val resources = listOf("Resources/gray.bmp" to grayBmp(), "Resources/linear.icc" to rgbProfile(1.0))
        val page = XpsDocument.open(XpsFixtures.packageBytes(content, resources)).pages.single()
        val image = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
        val rgba = assertNotNull(image.toRgbaBytes())
        return rgba[0].toInt() and 0xFF
    }

    @Test
    fun a_color_converted_bitmap_reads_its_samples_through_the_profile_it_names() {
        val plain = drawnGrey("../../Resources/gray.bmp")
        val converted = drawnGrey("{ColorConvertedBitmap ../../Resources/gray.bmp ../../Resources/linear.icc}")
        assertTrue(abs(plain - 128) <= 1, "the plain image keeps its samples: $plain")
        assertTrue(abs(converted - 188) <= 2, "a linear 0.5 is sRGB 0.735: $converted")
    }

    @Test
    fun a_profile_the_package_lacks_leaves_the_samples_as_stored() {
        assertTrue(abs(drawnGrey("{ColorConvertedBitmap ../../Resources/gray.bmp ../../Resources/missing.icc}") - 128) <= 1)
    }
}
