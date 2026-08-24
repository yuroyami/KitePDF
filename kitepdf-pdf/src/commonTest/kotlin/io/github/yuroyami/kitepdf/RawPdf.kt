package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Hand-built single-page PDFs for tests that need structure `PdfBuilder` does
 * not expose (form XObjects, AcroForm dictionaries, deliberate oddities).
 *
 * Object numbers 1 to 5 are taken: 1 catalog, 2 pages, 3 page, 4 Helvetica,
 * 5 content stream. Extra objects start at 6.
 */
internal object RawPdf {

    /** One `n 0 obj ... endobj` block, optionally carrying a stream. */
    fun obj(number: Int, dict: String, stream: ByteArray? = null): Pair<Int, ByteArray> {
        val buf = ByteArrayBuilder()
        if (stream == null) {
            buf.append("$number 0 obj\n$dict\nendobj\n".encodeToByteArray())
        } else {
            buf.append("$number 0 obj\n<< ${dict.trim().removePrefix("<<").removeSuffix(">>").trim()} /Length ${stream.size} >>\nstream\n".encodeToByteArray())
            buf.append(stream)
            buf.append("\nendstream\nendobj\n".encodeToByteArray())
        }
        return number to buf.toByteArray()
    }

    /**
     * A one-page 612x792 document whose page draws [content].
     *
     * [catalogExtra] and [annots] are pasted verbatim into the catalog and the
     * page dictionary, for tests that need an `/AcroForm` or annotations the
     * builder has no parameter for.
     */
    fun page(
        content: ByteArray,
        resources: String = "<< /Font << /F1 4 0 R >> >>",
        extra: List<Pair<Int, ByteArray>> = emptyList(),
        catalogExtra: String = "",
        annots: String = "",
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun a(s: String) = buf.append(s.encodeToByteArray())
        a("%PDF-1.5\n%Äå\n")
        offsets[1] = buf.size(); a("1 0 obj\n<< /Type /Catalog /Pages 2 0 R $catalogExtra >>\nendobj\n")
        offsets[2] = buf.size(); a("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        val annotsEntry = if (annots.isEmpty()) "" else " $annots"
        offsets[3] = buf.size(); a("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources $resources$annotsEntry /Contents 5 0 R >>\nendobj\n")
        offsets[4] = buf.size(); a("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets[5] = buf.size(); a("5 0 obj\n<< /Length ${content.size} >>\nstream\n"); buf.append(content); a("\nendstream\nendobj\n")
        for ((n, bytes) in extra) { offsets[n] = buf.size(); buf.append(bytes) }
        val xref = buf.size()
        val maxN = offsets.keys.max()
        a("xref\n0 ${maxN + 1}\n0000000000 65535 f \n")
        for (n in 1..maxN) {
            val off = offsets[n]
            a(if (off == null) "0000000000 65535 f \n" else "${off.toString().padStart(10, '0')} 00000 n \n")
        }
        a("trailer\n<< /Size ${maxN + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    /** True when [needle] appears anywhere in [haystack]. Used to prove bytes are gone. */
    fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
