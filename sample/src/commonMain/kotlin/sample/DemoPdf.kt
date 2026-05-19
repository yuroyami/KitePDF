package sample

import com.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Builds tiny in-memory PDFs that exercise the KitePDF pipeline end-to-end.
 *
 * Constructing the bytes programmatically (rather than committing a hex blob)
 * keeps xref offsets correct as we tweak content — and shows roughly what a
 * minimal PDF actually looks like on the wire.
 */
object DemoPdf {

    val helloWorld: ByteArray by lazy { buildHelloWorld() }

    val twoPages: ByteArray by lazy { buildTwoPages() }

    private fun buildHelloWorld(): ByteArray {
        val b = Builder("1.4")
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        b.addObject("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
            | /Resources << /Font << /F1 4 0 R >> >>
            | /Contents 5 0 R >>""".trimMargin(),
        )
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        b.addStream(
            "<< /Length %LEN% >>",
            """BT
              |/F1 24 Tf
              |72 720 Td
              |(Hello, KitePDF!) Tj
              |0 -36 Td
              |(Pure-Kotlin PDF parsing.) Tj
              |ET""".trimMargin(),
        )
        return b.finish(rootRef = "1 0 R")
    }

    private fun buildTwoPages(): ByteArray {
        val b = Builder("1.4")
        // 1: catalog
        b.addObject("<< /Type /Catalog /Pages 2 0 R >>")
        // 2: pages tree
        b.addObject("<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
        // 3: page 1
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 7 0 R >> >>
              | /Contents 4 0 R >>""".trimMargin(),
        )
        // 4: page 1 content
        b.addStream(
            "<< /Length %LEN% >>",
            """BT /F1 18 Tf 72 720 Td (Page one of two.) Tj ET""",
        )
        // 5: page 2
        b.addObject(
            """<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
              | /Resources << /Font << /F1 7 0 R >> >>
              | /Contents 6 0 R >>""".trimMargin(),
        )
        // 6: page 2 content
        b.addStream(
            "<< /Length %LEN% >>",
            """BT /F1 18 Tf 72 720 Td (Page two of two.) Tj ET""",
        )
        // 7: font
        b.addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        return b.finish(rootRef = "1 0 R")
    }
}

/** Minimal PDF writer: tracks per-object byte offsets so the xref table is exact. */
private class Builder(version: String) {
    private val buf = ByteArrayBuilder(1024)
    private val offsets = mutableListOf<Int>()
    private var objCounter = 0

    init {
        write("%PDF-$version\n%Äå\n")  // header + binary marker
    }

    fun addObject(body: String): Int {
        objCounter++
        offsets.add(buf.size())
        write("$objCounter 0 obj\n$body\nendobj\n")
        return objCounter
    }

    fun addStream(dictTemplate: String, payload: String): Int {
        objCounter++
        offsets.add(buf.size())
        val payloadBytes = payload.encodeToByteArray()
        val dict = dictTemplate.replace("%LEN%", payloadBytes.size.toString())
        write("$objCounter 0 obj\n$dict\nstream\n")
        buf.append(payloadBytes)
        write("\nendstream\nendobj\n")
        return objCounter
    }

    fun finish(rootRef: String): ByteArray {
        val xrefOffset = buf.size()
        write("xref\n0 ${offsets.size + 1}\n")
        write("0000000000 65535 f \n")
        for (off in offsets) {
            val padded = off.toString().padStart(10, '0')
            write("$padded 00000 n \n")
        }
        write("trailer\n<< /Size ${offsets.size + 1} /Root $rootRef >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun write(s: String) = buf.append(s.encodeToByteArray())
}
