package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct unit tests for PdfAction.parse covering each /S subtype.
 *
 * Uses a tiny no-op resolver since the input dicts are built in-memory and
 * don't reference indirect objects.
 */
class ActionTest {

    private val noResolver: IndirectResolver = IndirectResolver { _: PdfReference -> null }

    @Test
    fun parses_goto_action() {
        val dict = dict(
            "S" to PdfName("GoTo"),
            "D" to PdfString("named-dest".encodeToByteArray()),
        )
        val a = PdfAction.parse(dict, noResolver)
        assertTrue(a is PdfAction.GoTo)
        assertEquals("named-dest", (a.destination as PdfString).asText())
    }

    @Test
    fun parses_gotor_action() {
        val dict = dict(
            "S" to PdfName("GoToR"),
            "F" to PdfString("../other.pdf".encodeToByteArray()),
            "D" to PdfArray(listOf(io.github.yuroyami.kitepdf.parser.PdfInt(2), PdfName("Fit"))),
            "NewWindow" to io.github.yuroyami.kitepdf.parser.PdfBoolean(true),
        )
        val a = PdfAction.parse(dict, noResolver)
        assertTrue(a is PdfAction.GoToR)
        assertEquals("../other.pdf", a.filename)
        assertEquals(true, a.newWindow)
        assertNotNull(a.destination)
    }

    @Test
    fun parses_launch_action_with_file_string() {
        val dict = dict(
            "S" to PdfName("Launch"),
            "F" to PdfString("/Applications/Preview.app".encodeToByteArray()),
        )
        val a = PdfAction.parse(dict, noResolver)
        assertTrue(a is PdfAction.Launch)
        assertEquals("/Applications/Preview.app", a.filename)
        assertEquals(false, a.newWindow)
    }

    @Test
    fun parses_uri_action() {
        val dict = dict(
            "S" to PdfName("URI"),
            "URI" to PdfString("https://example.com".encodeToByteArray()),
            "IsMap" to io.github.yuroyami.kitepdf.parser.PdfBoolean(true),
        )
        val a = PdfAction.parse(dict, noResolver)
        assertTrue(a is PdfAction.Uri)
        assertEquals("https://example.com", a.uri)
        assertEquals(true, a.isMap)
    }

    @Test
    fun parses_named_action_known_and_unknown() {
        val nextPage = PdfAction.parse(
            dict("S" to PdfName("Named"), "N" to PdfName("NextPage")),
            noResolver,
        )
        assertTrue(nextPage is PdfAction.Named)
        assertEquals(PdfAction.NamedActionType.NextPage, nextPage.name)

        val custom = PdfAction.parse(
            dict("S" to PdfName("Named"), "N" to PdfName("SaveAs")),
            noResolver,
        )
        assertTrue(custom is PdfAction.Named)
        assertEquals(PdfAction.NamedActionType.Other, custom.name)
        assertEquals("SaveAs", custom.nameRaw)
    }

    @Test
    fun parses_javascript_action_with_string_body() {
        val dict = dict(
            "S" to PdfName("JavaScript"),
            "JS" to PdfString("this.print();".encodeToByteArray()),
        )
        val a = PdfAction.parse(dict, noResolver)
        assertTrue(a is PdfAction.JavaScript)
        assertEquals("this.print();", a.script)
    }

    @Test
    fun parses_submit_and_reset_form_actions() {
        val submit = PdfAction.parse(
            dict(
                "S" to PdfName("SubmitForm"),
                "F" to PdfString("https://forms.example.com/submit".encodeToByteArray()),
                "Fields" to PdfArray(listOf(PdfString("name".encodeToByteArray()))),
                "Flags" to io.github.yuroyami.kitepdf.parser.PdfInt(4),
            ),
            noResolver,
        )
        assertTrue(submit is PdfAction.SubmitForm)
        assertEquals("https://forms.example.com/submit", submit.url)
        assertEquals(1, submit.fields?.size)
        assertEquals(4, submit.flags)

        val reset = PdfAction.parse(
            dict("S" to PdfName("ResetForm"), "Flags" to io.github.yuroyami.kitepdf.parser.PdfInt(0)),
            noResolver,
        )
        assertTrue(reset is PdfAction.ResetForm)
        assertNull(reset.fields)
    }

    @Test
    fun unknown_action_type_falls_through_to_unknown() {
        val dict = dict("S" to PdfName("Sound"), "Volume" to io.github.yuroyami.kitepdf.parser.PdfReal(0.7))
        val a = PdfAction.parse(dict, noResolver)
        assertTrue(a is PdfAction.Unknown)
        assertEquals("Sound", a.type)
    }

    @Test
    fun null_dict_returns_null_action() {
        assertNull(PdfAction.parse(null, noResolver))
    }

    @Test
    fun annotation_action_is_exposed_alongside_legacy_uri() {
        // End-to-end sanity check that the annotation's parsed action matches
        // the URI extracted from the legacy field.
        val pdf = buildPdfWithUriAnnotation("https://example.org")
        val doc = KitePDF.open(pdf)
        val annot = doc.pages[0].annotations.single()
        assertEquals("https://example.org", annot.uri)
        assertTrue(annot.action is PdfAction.Uri)
        assertEquals("https://example.org", (annot.action as PdfAction.Uri).uri)
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun dict(vararg entries: Pair<String, PdfObject>): PdfDictionary =
        PdfDictionary(entries.toMap())

    private fun buildPdfWithUriAnnotation(uri: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>\nendobj\n")
        offsets.add(buf.size())
        w("4 0 obj\n<< /Type /Annot /Subtype /Link /Rect [0 0 1 1] /A << /S /URI /URI ($uri) >> >>\nendobj\n")
        val xref = buf.size()
        w("xref\n0 5\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
