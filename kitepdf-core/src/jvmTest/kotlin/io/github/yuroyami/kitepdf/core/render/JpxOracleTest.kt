package io.github.yuroyami.kitepdf.core.render

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-44: the pure-Kotlin JPX decoder against OpenJPEG. Fixtures are produced
 * on the fly with opj_compress from a deterministic PPM, decoded with
 * [JpxDecoder], and compared per-pixel against opj_decompress on the same
 * file. Skips cleanly when the OpenJPEG tools are absent.
 */
class JpxOracleTest {

    private val compress = File("/opt/homebrew/bin/opj_compress")
    private val decompress = File("/opt/homebrew/bin/opj_decompress")

    private fun tools(): Boolean = compress.canExecute() && decompress.canExecute()

    /** Deterministic 97x61 RGB test card: gradients + blocks + a few edges. */
    private fun ppm(w: Int = 97, h: Int = 61, gray: Boolean = false): File {
        val header = "P${if (gray) 5 else 6}\n$w $h\n255\n".encodeToByteArray()
        val body = ByteArray(w * h * (if (gray) 1 else 3))
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val r = (x * 255 / (w - 1))
            val g = (y * 255 / (h - 1))
            val b = if ((x / 8 + y / 8) % 2 == 0) 230 else 25
            if (gray) body[i++] = ((r + g + b) / 3).toByte()
            else { body[i++] = r.toByte(); body[i++] = g.toByte(); body[i++] = b.toByte() }
        }
        return File.createTempFile("kite-jpx", ".ppm").apply {
            deleteOnExit()
            writeBytes(header + body)
        }
    }

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(60, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -1 }
        return proc.exitValue()
    }

    private class Pnm(val w: Int, val h: Int, val comps: Int, val data: ByteArray)

    private fun readPnm(f: File): Pnm {
        val d = f.readBytes()
        var p = 0
        fun token(): String {
            while (d[p].toInt().toChar().isWhitespace()) p++
            if (d[p].toInt().toChar() == '#') { while (d[p].toInt().toChar() != '\n') p++; return token() }
            val sb = StringBuilder()
            while (p < d.size && !d[p].toInt().toChar().isWhitespace()) sb.append(d[p++].toInt().toChar())
            return sb.toString()
        }
        val magic = token()
        val comps = if (magic == "P6") 3 else 1
        val w = token().toInt(); val h = token().toInt(); token() // maxval
        p++ // single whitespace after maxval
        return Pnm(w, h, comps, d.copyOfRange(p, p + w * h * comps))
    }

    /** Encode [src] with opj_compress + [extra] args, return the .jp2 file. */
    private fun encode(src: File, vararg extra: String): File {
        val jp2 = File.createTempFile("kite-jpx", ".jp2").apply { deleteOnExit() }
        val code = run(compress.absolutePath, "-i", src.absolutePath, "-o", jp2.absolutePath, *extra)
        assertEquals(0, code, "opj_compress failed")
        return jp2
    }

    /** Decode [jp2] with both decoders and return (kite, oracle). */
    private fun both(jp2: File): Pair<JpxDecoder.Result, Pnm> {
        val kite = JpxDecoder.decode(jp2.readBytes())
        assertNotNull(kite, "JpxDecoder returned null")
        val out = File.createTempFile("kite-jpx-ref", if (kite.colorSpace == "DeviceRGB") ".ppm" else ".pgm")
            .apply { deleteOnExit() }
        val code = run(decompress.absolutePath, "-i", jp2.absolutePath, "-o", out.absolutePath)
        assertEquals(0, code, "opj_decompress failed")
        return kite to readPnm(out)
    }

    private fun compare(tag: String, kite: JpxDecoder.Result, ref: Pnm, tolerance: Int) {
        assertEquals(ref.w, kite.width, "$tag width")
        assertEquals(ref.h, kite.height, "$tag height")
        val comps = if (kite.colorSpace == "DeviceRGB") 3 else 1
        assertEquals(ref.comps, comps, "$tag component count")
        var maxDiff = 0
        var sum = 0L
        for (i in kite.pixelBytes.indices) {
            val a = kite.pixelBytes[i].toInt() and 0xFF
            val b = ref.data[i].toInt() and 0xFF
            val diff = if (a > b) a - b else b - a
            if (diff > maxDiff) maxDiff = diff
            sum += diff
        }
        val mean = sum.toDouble() / kite.pixelBytes.size
        println("[T-44] $tag: maxDiff=$maxDiff meanDiff=${(mean * 1000).toInt() / 1000.0}")
        assertTrue(maxDiff <= tolerance, "$tag max per-pixel diff $maxDiff must be <= $tolerance")
    }

    @Test
    fun lossless_rgb_53_matches_openjpeg_exactly() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm()) // defaults: reversible 5/3, RCT, LRCP, one tile
        val (kite, ref) = both(jp2)
        compare("lossless-rgb", kite, ref, tolerance = 0)
    }

    @Test
    fun lossless_gray_matches_openjpeg_exactly() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(gray = true))
        val (kite, ref) = both(jp2)
        compare("lossless-gray", kite, ref, tolerance = 0)
    }

    @Test
    fun lossy_97_stays_close_to_openjpeg() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-I", "-r", "10")
        val (kite, ref) = both(jp2)
        // The fixed-point 9/7 path tolerates small rounding differences.
        compare("lossy-97", kite, ref, tolerance = 4)
    }

    @Test
    fun rpcl_progression_and_multiple_layers_decode() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-p", "RPCL", "-r", "20,10,1")
        val (kite, ref) = both(jp2)
        compare("rpcl-layers", kite, ref, tolerance = 0)
    }

    @Test
    fun tiled_image_decodes() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-t", "64,64")
        val (kite, ref) = both(jp2)
        compare("tiled", kite, ref, tolerance = 0)
    }

    @Test
    fun explicit_precincts_and_sop_eph_decode() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-c", "[32,32]", "-SOP", "-EPH")
        val (kite, ref) = both(jp2)
        compare("precincts", kite, ref, tolerance = 0)
    }

    @Test
    fun the_corpus_fixture_jp2_decodes_exactly() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        // Pull the embedded codestream straight out of the corpus PDF.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var pdf: File? = null
        while (dir != null) {
            val f = File(dir, "corpus/pdf/testPDF_JPX.pdf")
            if (f.isFile) { pdf = f; break }
            dir = dir.parentFile
        }
        assumeTrue("corpus fixture not found, skipping.", pdf != null)
        val bytes = pdf!!.readBytes()
        val marker = "JPXDecode".encodeToByteArray()
        var at = -1
        outer@ for (i in 0 until bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            at = i; break
        }
        assumeTrue("no JPXDecode stream in fixture", at >= 0)
        val streamTag = "stream\n".encodeToByteArray()
        var start = -1
        outer2@ for (i in at until bytes.size - streamTag.size) {
            for (j in streamTag.indices) if (bytes[i + j] != streamTag[j]) continue@outer2
            start = i + streamTag.size; break
        }
        val endTag = "\nendstream".encodeToByteArray()
        var end = -1
        outer3@ for (i in start until bytes.size - endTag.size) {
            for (j in endTag.indices) if (bytes[i + j] != endTag[j]) continue@outer3
            end = i; break
        }
        val jp2 = File.createTempFile("kite-jpx-corpus", ".jp2").apply {
            deleteOnExit()
            writeBytes(bytes.copyOfRange(start, end))
        }
        val (kite, ref) = both(jp2)
        compare("corpus-fixture", kite, ref, tolerance = 0)
    }
}
