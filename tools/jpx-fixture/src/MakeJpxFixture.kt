package io.github.yuroyami.kitepdf.tools

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import java.io.File

/**
 * Writes `corpus/pdf/testPDF_JPX.pdf`: a `.jp2` (JPEG 2000) codestream embedded
 * through `/JPXDecode`, so the differential harness has a JPX fixture with
 * mutool as the oracle. A tool, not a test, so the gate never runs it (#192):
 *
 * ```
 * ./kotlin run -m jpx-fixture -- /path/file.jp2 330 255
 * ```
 */
fun main(args: Array<String>) {
    val jp2 = File(requireNotNull(args.getOrNull(0)) { "usage: ./kotlin run -m jpx-fixture -- /path/file.jp2 [width] [height]" })
    require(jp2.isFile) { "${jp2.path} not found" }
    val w = args.getOrNull(1)?.toIntOrNull() ?: 330
    val h = args.getOrNull(2)?.toIntOrNull() ?: 255
    val dir = File(requireNotNull(repoRoot()) { "no repo root above ${File("").absolutePath}" }, "corpus/pdf")
    dir.mkdirs()
    val out = File(dir, "testPDF_JPX.pdf")
    val img = PdfImage.jpx(jp2.readBytes(), w, h)
    val pdf = PdfBuilder().page(w.toDouble(), h.toDouble()) {
        drawImage(img, 0.0, 0.0, w.toDouble(), h.toDouble())
    }.build()
    out.writeBytes(pdf)
    println("[jpx] wrote ${out.absolutePath} (${pdf.size} bytes, ${w}x$h)")
}

/** The repository root: the first folder at or above the working directory that holds project.yaml. */
private fun repoRoot(): File? {
    var directory: File? = File(System.getProperty("user.dir")).absoluteFile
    while (directory != null && !File(directory, "project.yaml").exists()) directory = directory.parentFile
    return directory
}
