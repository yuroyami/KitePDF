package io.github.yuroyami.kitepdf.difftest

import java.io.ByteArrayOutputStream

/**
 * A one-page PDF that the tests of each backend render and score against mutool, and
 * the most mean absolute error a backend may score on it.
 */
data class OracleFixture(val name: String, val bytes: ByteArray, val budget: Double)

/**
 * A fixture whose page is 200 by 200 points and draws [content] with [resources].
 * The [extra] objects are numbered from 5.
 */
internal fun oracleFixture(name: String, content: String, resources: String, extra: List<ByteArray>, budget: Double): OracleFixture {
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << $resources >> /Contents 4 0 R >>".toByteArray(),
        pdfStream(content.toByteArray()),
    ) + extra
    val out = ByteArrayOutputStream()
    out.write("%PDF-1.7\n".toByteArray())
    val offsets = objects.mapIndexed { i, body ->
        out.size().also {
            out.write("${i + 1} 0 obj\n".toByteArray())
            out.write(body)
            out.write("\nendobj\n".toByteArray())
        }
    }
    val xref = out.size()
    out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
    for (o in offsets) out.write("${o.toString().padStart(10, '0')} 00000 n \n".toByteArray())
    out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
    return OracleFixture(name, out.toByteArray(), budget)
}

/** A stream object that holds [data], with the [entries] of its dictionary besides /Length. */
internal fun pdfStream(data: ByteArray, entries: String = ""): ByteArray =
    "<< $entries /Length ${data.size} >>\nstream\n".toByteArray() + data + "\nendstream".toByteArray()
