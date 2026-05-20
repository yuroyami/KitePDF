package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the catalog parsers added in round 12-17:
 *   - /Lang, /Names /JavaScript, /Names /EmbeddedFiles
 *   - /MarkInfo, /OCProperties, /AcroForm
 */
class CatalogExtrasTest {

    @Test
    fun language_tag_is_exposed() {
        val doc = KitePDF.open(buildPdf(extraCatalogEntries = "/Lang (en-US)"))
        assertEquals("en-US", doc.language)
    }

    @Test
    fun missing_language_returns_null() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertNull(doc.language)
    }

    @Test
    fun document_javascript_map_is_built_from_name_tree() {
        val doc = KitePDF.open(
            buildPdf(
                extraCatalogEntries = "/Names << /JavaScript 4 0 R >>",
                extraObjects = listOf(
                    // 4 0 R: JavaScript name tree leaf with two entries.
                    "<< /Names [(open) 5 0 R (print) 6 0 R] >>",
                    "<< /S /JavaScript /JS (app.alert('opened');) >>",
                    "<< /S /JavaScript /JS (this.print();) >>",
                ),
            ),
        )
        val scripts = doc.documentJavaScripts
        assertEquals(2, scripts.size)
        assertEquals("app.alert('opened');", scripts["open"])
        assertEquals("this.print();", scripts["print"])
    }

    @Test
    fun embedded_files_are_listed_with_metadata() {
        val doc = KitePDF.open(
            buildPdf(
                extraCatalogEntries = "/Names << /EmbeddedFiles 4 0 R >>",
                extraObjects = listOf(
                    // 4 0 R: name tree leaf — one attachment.
                    "<< /Names [(README) 5 0 R] >>",
                    // 5 0 R: FileSpec dict.
                    "<< /Type /Filespec /F (readme.txt) /UF (readme.txt) /Desc (project readme) /EF << /F 6 0 R >> >>",
                    // 6 0 R: the embedded file stream (raw text, length will be patched in the builder).
                    "STREAM:Hello attachment world",
                ),
            ),
        )
        val atts = doc.attachments
        assertEquals(1, atts.size)
        val a = atts[0]
        assertEquals("README", a.name)
        assertEquals("readme.txt", a.filename)
        assertEquals("project readme", a.description)
        assertEquals("Hello attachment world", a.bytes.decodeToString())
    }

    @Test
    fun no_attachments_returns_empty_list() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertTrue(doc.attachments.isEmpty())
    }

    @Test
    fun mark_info_parses_when_present() {
        val doc = KitePDF.open(
            buildPdf(extraCatalogEntries = "/MarkInfo << /Marked true /UserProperties true /Suspects false >>"),
        )
        val mi = doc.markInfo
        assertNotNull(mi)
        assertEquals(true, mi.marked)
        assertEquals(true, mi.userProperties)
        assertEquals(false, mi.suspects)
    }

    @Test
    fun mark_info_null_when_absent() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertNull(doc.markInfo)
    }

    @Test
    fun optional_content_parses_groups_and_default_state() {
        val doc = KitePDF.open(
            buildPdf(
                extraCatalogEntries =
                    "/OCProperties << /OCGs [4 0 R 5 0 R] /D << /Name (default) /BaseState /ON /OFF [5 0 R] >> >>",
                extraObjects = listOf(
                    "<< /Type /OCG /Name (Background) /Intent /View >>",
                    "<< /Type /OCG /Name (Watermarks) /Intent [/View /Design] >>",
                ),
            ),
        )
        val oc = doc.optionalContent
        assertNotNull(oc)
        assertEquals(2, oc.groups.size)
        assertEquals("Background", oc.groups[0].name)
        assertEquals(listOf("View"), oc.groups[0].intent)
        assertEquals(listOf("View", "Design"), oc.groups[1].intent)
        // BaseState ON, then OFF lists OCG 5 → only OCG 4 visible.
        assertEquals("default", oc.defaultConfigName)
        assertTrue(oc.isVisibleByDefault("4"))
        assertTrue(!oc.isVisibleByDefault("5"))
    }

    @Test
    fun acroform_catalog_is_exposed() {
        val doc = KitePDF.open(
            buildPdf(
                extraCatalogEntries =
                    "/AcroForm << /Fields [4 0 R 5 0 R] /NeedAppearances true /SigFlags 3 /Q 1 /DA (/Helv 12 Tf 0 g) >>",
                extraObjects = listOf(
                    "<< /T (name) /FT /Tx >>",
                    "<< /T (sig) /FT /Sig >>",
                ),
            ),
        )
        val form = doc.acroForm
        assertNotNull(form)
        assertEquals(2, form.fieldCount)
        assertEquals(true, form.needAppearances)
        assertEquals(3, form.signatureFlags)
        assertTrue(form.hasSignatures)
        assertTrue(form.appendOnly)
        assertEquals(1, form.defaultQuadding)
        assertEquals("/Helv 12 Tf 0 g", form.defaultAppearance)
        assertTrue(!form.hasXfa)
    }

    @Test
    fun no_acroform_returns_null() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertNull(doc.acroForm)
    }

    /* ─── Builder ─────────────────────────────────────────────────────────── */

    /**
     * Build a single-page PDF where the catalog has additional `extraCatalogEntries`
     * appended verbatim, and the trailer ends with `extraObjects` numbered 4, 5, 6…
     * Stream bodies are marked with the prefix "STREAM:".
     */
    private fun buildPdf(
        extraCatalogEntries: String,
        extraObjects: List<String> = emptyList(),
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.7\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R $extraCatalogEntries >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")

        for ((i, body) in extraObjects.withIndex()) {
            val n = 4 + i
            offsets.add(buf.size())
            if (body.startsWith("STREAM:")) {
                val payload = body.removePrefix("STREAM:").encodeToByteArray()
                w("$n 0 obj\n<< /Length ${payload.size} >>\nstream\n")
                buf.append(payload)
                w("\nendstream\nendobj\n")
            } else {
                w("$n 0 obj\n$body\nendobj\n")
            }
        }

        val xref = buf.size()
        val total = offsets.size + 1
        w("xref\n0 $total\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
