package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import java.io.File
import kotlin.test.Test

/**
 * One-shot generator: embed a `.jp2` (JPEG 2000) codestream into a PDF via
 * `/JPXDecode` and write it to `corpus/pdf/testPDF_JPX.pdf`, so the differential
 * harness has a JPX fixture (mutool is the oracle). Provide the `.jp2` path via
 * `-Dkitepdf.jp2=/path` (e.g. one made with `opj_compress`); no-ops otherwise.
 */
class MakeJpxFixture {
    @Test
    fun make() {
        val jp2Path = System.getProperty("kitepdf.jp2") ?: "/tmp/test.jp2"
        val jp2 = File(jp2Path)
        if (!jp2.exists()) { println("[jpx] $jp2Path not found"); return }
        val w = System.getProperty("kitepdf.jp2.w")?.toIntOrNull() ?: 330
        val h = System.getProperty("kitepdf.jp2.h")?.toIntOrNull() ?: 255
        val out = Corpus.repoCorpus("pdf")?.let { File(it, "testPDF_JPX.pdf") } ?: return

        val img = PdfImage.jpx(jp2.readBytes(), w, h)
        val pdf = PdfBuilder().page(w.toDouble(), h.toDouble()) {
            drawImage(img, 0.0, 0.0, w.toDouble(), h.toDouble())
        }.build()
        out.writeBytes(pdf)
        println("[jpx] wrote ${out.absolutePath} (${pdf.size} bytes, ${w}x$h)")
    }
}
