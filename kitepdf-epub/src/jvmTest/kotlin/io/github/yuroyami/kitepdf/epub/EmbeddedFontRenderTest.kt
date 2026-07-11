package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end embedded `@font-face` rendering, on the JVM so it can read the
 * in-repo `DroidSansFallback.ttf`. Proves the full path: parse `@font-face`, load
 * the TrueType file from the zip, match a run's `font-family`, and draw the glyphs
 * with real outlines (`hasOutlines=true`, the font's own `unitsPerEm`).
 */
class EmbeddedFontRenderTest {

    private fun droidSans(): ByteArray? {
        val rel = "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.takeIf { it.exists() }?.readBytes()
    }

    private fun glyphRuns(body: String, ttf: ByteArray): List<RecordingCanvas.Call.Glyphs> {
        val doc = EpubDocument.open(EpubFixtures.epub(body, listOf("OEBPS/font.ttf" to ttf)))
        assertNotNull(doc)
        return doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
    }

    @Test
    fun embedded_font_draws_real_outlines() {
        val ttf = droidSans() ?: return // font not present in this checkout — skip
        val css = "@font-face{font-family:'Embedded';src:url(font.ttf)}p{font-family:'Embedded'}"
        val runs = glyphRuns("<body><style>$css</style><p>中文字</p></body>", ttf)

        val embedded = runs.filter { it.hasOutlines }
        assertTrue(embedded.isNotEmpty(), "the @font-face run should draw with embedded outlines")
        assertTrue(embedded.all { it.unitsPerEm > 0 }, "embedded runs carry the font's unitsPerEm")
        assertTrue(embedded.any { r -> r.glyphs.any { it.outline != null } }, "glyphs carry real outlines")
    }

    @Test
    fun unmatched_family_falls_back_to_standard14() {
        val ttf = droidSans() ?: return
        // The face is declared but no element uses it -> everything stays on the fallback path.
        val css = "@font-face{font-family:'Embedded';src:url(font.ttf)}"
        val runs = glyphRuns("<body><style>$css</style><p>hello world</p></body>", ttf)
        assertTrue(runs.isNotEmpty() && runs.none { it.hasOutlines }, "no font-family match keeps the Standard-14 path")
    }

    @Test
    fun font_matching_relaxes_weight_and_style() {
        val ttf = droidSans() ?: return
        val face = FontRegistry.face("book", bold = false, italic = false, ttf)
        assertNotNull(face)
        val registry = FontRegistry(listOf(face))
        assertSame(face, registry.match("book", bold = false, italic = false), "exact match")
        assertSame(face, registry.match("book", bold = true, italic = false), "relaxes to the family's only weight")
        assertSame(face, registry.match("book", bold = true, italic = true), "relaxes weight + style")
        assertNull(registry.match("othername", bold = false, italic = false), "unknown family -> Standard-14 fallback")
    }

    @Test
    fun idpf_obfuscated_font_is_deobfuscated_before_parsing() {
        val ttf = droidSans() ?: return
        val uid = "urn:uuid:12345678-1234-5678-9abc-def012345678"
        val obfuscated = Deobfuscate.idpf(ttf, uid) // publisher-side mangling
        val css = "@font-face{font-family:'Obf';src:url(font.ttf)}p{font-family:'Obf'}"
        val encryption = """
            <?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <CipherData><CipherReference URI="OEBPS/font.ttf"/></CipherData>
              </EncryptedData>
            </encryption>
        """.trimIndent()
        val epub = EpubFixtures.epub(
            "<body><style>$css</style><p>中文</p></body>",
            listOf(
                "OEBPS/font.ttf" to obfuscated,
                "META-INF/encryption.xml" to encryption.encodeToByteArray(),
            ),
            uniqueId = uid,
        )
        val doc = EpubDocument.open(epub)
        assertNotNull(doc)
        val runs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }
        assertTrue(runs.any { it.hasOutlines }, "deobfuscated font parses + draws outlines")
    }
}
