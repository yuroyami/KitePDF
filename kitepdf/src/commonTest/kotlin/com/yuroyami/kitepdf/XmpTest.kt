package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the XMP metadata parser. Builds PDFs with a catalog `/Metadata`
 * stream containing a representative XMP packet and verifies that all the
 * Dublin Core / pdf / xmp namespace properties extract correctly.
 */
class XmpTest {

    @Test
    fun parses_full_xmp_packet() {
        val xmp = """
            <?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>
            <x:xmpmeta xmlns:x='adobe:ns:meta/'>
              <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
                <rdf:Description rdf:about=''
                    xmlns:dc='http://purl.org/dc/elements/1.1/'
                    xmlns:xmp='http://ns.adobe.com/xap/1.0/'
                    xmlns:pdf='http://ns.adobe.com/pdf/1.3/'>
                  <dc:title>
                    <rdf:Alt>
                      <rdf:li xml:lang='x-default'>KitePDF XMP Test</rdf:li>
                      <rdf:li xml:lang='fr'>Test XMP KitePDF</rdf:li>
                    </rdf:Alt>
                  </dc:title>
                  <dc:creator>
                    <rdf:Seq>
                      <rdf:li>yuroyami</rdf:li>
                      <rdf:li>Claude</rdf:li>
                    </rdf:Seq>
                  </dc:creator>
                  <dc:description>
                    <rdf:Alt>
                      <rdf:li xml:lang='x-default'>A test of the XMP metadata parser.</rdf:li>
                    </rdf:Alt>
                  </dc:description>
                  <dc:subject>
                    <rdf:Bag>
                      <rdf:li>kotlin</rdf:li>
                      <rdf:li>pdf</rdf:li>
                      <rdf:li>xmp</rdf:li>
                    </rdf:Bag>
                  </dc:subject>
                  <pdf:Producer>kitepdf</pdf:Producer>
                  <pdf:Keywords>kotlin, pdf, kite</pdf:Keywords>
                  <pdf:PDFVersion>1.7</pdf:PDFVersion>
                  <xmp:CreatorTool>test-suite</xmp:CreatorTool>
                  <xmp:CreateDate>2026-05-19T14:30:25+02:00</xmp:CreateDate>
                  <xmp:ModifyDate>2026-05-19T15:00:00+02:00</xmp:ModifyDate>
                  <xmp:MetadataDate>2026-05-19T15:00:01+02:00</xmp:MetadataDate>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end='w'?>
        """.trimIndent()

        val doc = KitePDF.open(buildPdfWithXmp(xmp))
        val m = doc.xmp
        assertNotNull(m)
        assertEquals("KitePDF XMP Test", m.title)
        assertEquals(listOf("yuroyami", "Claude"), m.authors)
        assertEquals("A test of the XMP metadata parser.", m.description)
        assertEquals(listOf("kotlin", "pdf", "xmp"), m.subjects)
        assertEquals("kitepdf", m.producer)
        assertEquals("kotlin, pdf, kite", m.keywords)
        assertEquals("1.7", m.pdfVersion)
        assertEquals("test-suite", m.creatorTool)
        assertEquals("2026-05-19T14:30:25+02:00", m.createDate)
        assertEquals("2026-05-19T15:00:00+02:00", m.modifyDate)
        assertEquals("2026-05-19T15:00:01+02:00", m.metadataDate)
    }

    @Test
    fun parses_attribute_form_xmp() {
        // Some writers (notably older Acrobat) emit XMP using the
        // attribute-shorthand form on rdf:Description.
        val xmp = """
            <x:xmpmeta xmlns:x='adobe:ns:meta/'>
              <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
                <rdf:Description rdf:about=''
                    xmlns:pdf='http://ns.adobe.com/pdf/1.3/'
                    xmlns:xmp='http://ns.adobe.com/xap/1.0/'
                    pdf:Producer='AttrForm'
                    pdf:PDFVersion='2.0'
                    xmp:CreatorTool='AttrTool'/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val doc = KitePDF.open(buildPdfWithXmp(xmp))
        val m = doc.xmp
        assertNotNull(m)
        assertEquals("AttrForm", m.producer)
        assertEquals("2.0", m.pdfVersion)
        assertEquals("AttrTool", m.creatorTool)
    }

    @Test
    fun handles_xml_entities() {
        val xmp = """
            <x:xmpmeta xmlns:x='adobe:ns:meta/'>
              <rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>
                <rdf:Description xmlns:pdf='http://ns.adobe.com/pdf/1.3/'>
                  <pdf:Producer>Acme &amp; Co. &lt;v1&gt;</pdf:Producer>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val doc = KitePDF.open(buildPdfWithXmp(xmp))
        assertEquals("Acme & Co. <v1>", doc.xmp?.producer)
    }

    @Test
    fun missing_metadata_returns_null() {
        val doc = KitePDF.open(MetadataPdfBuilder.simpleTwoPagePdf())
        assertNull(doc.xmp)
        assertNull(doc.xmpMetadataXml)
    }

    @Test
    fun raw_xml_is_exposed_for_custom_parsing() {
        val xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>HELLO</x:xmpmeta>"
        val doc = KitePDF.open(buildPdfWithXmp(xmp))
        val raw = doc.xmpMetadataXml
        assertNotNull(raw)
        assertTrue(raw.contains("HELLO"))
    }

    @Test
    fun utf8_bom_is_stripped() {
        // Build a /Metadata stream whose body starts with EF BB BF.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "<x:xmpmeta xmlns:x='adobe:ns:meta/'>BOM</x:xmpmeta>".encodeToByteArray()
        val bytes = buildPdfWithRawXmpBytes(bom + body)
        val doc = KitePDF.open(bytes)
        val raw = doc.xmpMetadataXml
        assertNotNull(raw)
        // The BOM character should NOT appear at the start.
        assertEquals('<', raw.first())
        assertTrue(raw.contains("BOM"))
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun buildPdfWithXmp(xmpPacket: String): ByteArray =
        buildPdfWithRawXmpBytes(xmpPacket.encodeToByteArray())

    private fun buildPdfWithRawXmpBytes(metadataBytes: ByteArray): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.7\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Metadata 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        // Stream object — no filter, just raw XML bytes.
        write("4 0 obj\n<< /Type /Metadata /Subtype /XML /Length ${metadataBytes.size} >>\nstream\n")
        buf.append(metadataBytes)
        write("\nendstream\nendobj\n")

        val xref = buf.size()
        write("xref\n0 5\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
