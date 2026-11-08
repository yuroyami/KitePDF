package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A document script reads the annotations, links, layers and word positions the document has (#937). */
class DocQueryScriptTest {

    private fun pdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(n: Int, body: String) {
            offsets[n] = buf.size()
            buf.append("$n 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        val content = "BT /F1 12 Tf 20 100 Td (Hello world) Tj ET"
        buf.append("%PDF-1.7\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [9 0 R 10 0 R] /D << /OFF [10 0 R] >> >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Font << /F1 8 0 R >> >> /Annots [5 0 R 6 0 R 7 0 R] >>")
        obj(4, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
        obj(5, "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /NM (note1) /T (Ada) /Contents (Check this) /M (D:20260101) >>")
        obj(6, "<< /Type /Annot /Subtype /Square /Rect [40 40 80 80] /NM (box1) /F 2 >>")
        obj(7, "<< /Type /Annot /Subtype /Link /Rect [100 100 150 120] /C [1 0 0] /A << /S /URI /URI (https://example.org) >> >>")
        obj(8, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        obj(9, "<< /Type /OCG /Name (Shown) >>")
        obj(10, "<< /Type /OCG /Name (Hidden) >>")
        val xref = buf.size()
        buf.append("xref\n0 11\n0000000000 65535 f \n".encodeToByteArray())
        for (n in 1..10) buf.append("${offsets.getValue(n).toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        buf.append("trailer\n<< /Size 11 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    private fun eval(script: String): String = PdfScriptRunner(PdfDocument.open(pdf())).use { runner ->
        runner.run(PdfAction.JavaScript(script, PdfDictionary(emptyMap()))) ?: ""
    }

    @Test
    fun annots_answer_from_the_page() {
        assertEquals("Text,Square", eval("this.getAnnots({ nPage: 0 }).map(function (a) { return a.type; }).join(',')"))
        assertEquals("Ada|Check this|10,10,30,30|false", eval("var a = this.getAnnot(0, 'note1'); [a.author, a.contents, a.rect.join(','), a.hidden].join('|')"))
        assertEquals("true", eval("String(this.getAnnot(0, 'box1').hidden)"))
        assertEquals("null", eval("String(this.getAnnot(0, 'nothing'))"))
    }

    @Test
    fun links_answer_with_their_rect_and_colour() {
        assertEquals("1|100,100,150,120|RGB,1,0,0", eval("var l = this.getLinks(0); [l.length, l[0].rect.join(','), l[0].borderColor.join(',')].join('|')"))
        assertEquals("0", eval("String(this.getLinks(0, [0, 0, 50, 50]).length)"))
    }

    @Test
    fun layers_answer_with_their_state() {
        assertEquals("Shown:true,Hidden:false", eval("this.getOCGs().map(function (g) { return g.name + ':' + g.state; }).join(',')"))
    }

    @Test
    fun the_quad_of_a_word_holds_its_glyphs() {
        val quad = eval("this.getPageNthWord(0, 1) + '|' + this.getPageNthWordQuads(0, 1)[0].join(',')")
        val (word, numbers) = quad.split('|')
        assertEquals("world", word)
        val q = numbers.split(',').map { it.toDouble() }
        // "world" starts after "Hello " at x 20 in 12 point Helvetica, on the baseline at 100.
        assertTrue(q[0] > 40.0 && q[2] > q[0] && q[2] < 100.0, "x: $q")
        assertTrue(q[1] > 100.0 && q[5] <= 100.0, "y: $q")
    }
}
