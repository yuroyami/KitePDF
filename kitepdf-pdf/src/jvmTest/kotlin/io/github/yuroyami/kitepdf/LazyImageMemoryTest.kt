package io.github.yuroyami.kitepdf

import io.github.yuroyami.kiteimagecodec.KiteBitmap
import io.github.yuroyami.kiteimagecodec.KiteImageCodec
import io.github.yuroyami.kiteimagecodec.codec.JpxDecoder
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import io.github.yuroyami.kitepdf.core.render.toShrunkRgbaBytes
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A large scan drawn small allocates for the size it draws, not for its own size (#381). The JVM
 * counts the bytes each thread allocates, so this measures instead of trusting the code.
 */
class LazyImageMemoryTest {

    private fun allocated(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    private fun image(data: ByteArray, w: Int, h: Int, filter: String) = KiteImageData.from(
        PdfStream(
            dict = PdfDictionary(
                linkedMapOf<String, PdfObject>(
                    "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                    "Width" to PdfInt(w.toLong()), "Height" to PdfInt(h.toLong()),
                    "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceRGB"),
                    "Filter" to PdfName(filter), "Length" to PdfInt(data.size.toLong()),
                ),
            ),
            rawBytes = data,
        ),
        refs = { null },
    )

    @Test
    fun a_large_jpeg_drawn_at_an_eighth_never_holds_its_full_size() {
        // 3,000 by 2,000: 18 MB of RGB samples and 24 MB of RGBA at full size.
        val w = 3000
        val h = 2000
        val jpeg = KiteImageCodec.encodeJpeg(KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or (((x * 7 + y) and 0xFF) shl 16) or (((y * 3) and 0xFF) shl 8) or (((x xor y) * 5) and 0xFF)
        }), quality = 85)
        repeat(2) { image(jpeg, w, h, "DCTDecode").toShrunkRgbaBytes(8, 8) } // warm up
        var small: ByteArray? = null
        val bytes = allocated { small = image(jpeg, w, h, "DCTDecode").toShrunkRgbaBytes(8, 8) }
        assertEquals(375 * 250 * 4, small!!.size)
        println("a 3000 by 2000 jpeg loaded and drawn at an eighth: $bytes bytes allocated")
        // The load checks the file with a decode at an eighth, and the draw decodes at an eighth
        // again, so both cost about the output. A full decode alone would take 24 MB.
        assertTrue(bytes < 4_000_000, "$bytes bytes allocated")
    }

    @Test
    fun a_jpeg_2000_image_keeps_its_encoded_data_and_draws_from_the_reduced_decode() {
        assumeTrue("opj_compress is not installed", listOf("/opt/homebrew/bin/opj_compress", "/usr/bin/opj_compress", "/usr/local/bin/opj_compress").any { File(it).canExecute() })
        val compress = listOf("/opt/homebrew/bin/opj_compress", "/usr/bin/opj_compress", "/usr/local/bin/opj_compress").first { File(it).canExecute() }
        val w = 203
        val h = 157
        val dir = Files.createTempDirectory("kitepdf-jpx").toFile()
        try {
            val ppm = File(dir, "in.ppm")
            val body = ByteArray(w * h * 3) { i ->
                val p = i / 3
                when (i % 3) {
                    0 -> (p % w * 255 / w).toByte()
                    1 -> (p / w * 255 / h).toByte()
                    else -> 128.toByte()
                }
            }
            ppm.writeBytes("P6\n$w $h\n255\n".encodeToByteArray() + body)
            val jp2 = File(dir, "out.jp2")
            val process = ProcessBuilder(compress, "-i", ppm.path, "-o", jp2.path).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "opj_compress failed")
            val data = jp2.readBytes()

            val image = image(data, w, h, "JPXDecode")
            assertEquals(KiteImageData.Kind.RAW, image.kind)
            assertEquals(w, image.width)
            assertEquals(data.size.toLong(), image.retainedBytes(), "the image holds decoded samples")
            // Drawn at a quarter, the pixels are the codec's decode with two wavelet levels dropped.
            val reduced = assertNotNull(JpxDecoder.decode(data, 4))
            val expected = ByteArray(reduced.width * reduced.height * 4) { i ->
                if (i % 4 == 3) 0xFF.toByte() else reduced.pixelBytes[i / 4 * 3 + i % 4]
            }
            assertContentEquals(expected, image.toShrunkRgbaBytes(4, 4))
            // Drawn at full size, it decodes in full.
            assertEquals(w * h * 4, image.toRgbaBytes()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
