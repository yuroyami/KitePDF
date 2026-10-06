package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An audio or video element keeps a box on the page, paints its poster or a placeholder, and is
 * listed on the page with its sources. It left no box, and an app had no way to find the media (#29).
 */
class MediaTest {

    private fun book(body: String, manifest: String = "", files: List<Pair<String, ByteArray>> = emptyList()): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>$manifest</manifest>
            <spine><itemref idref="c1"/></spine></package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/c1.xhtml" to chapter.encodeToByteArray(),
                ) + files.map { (name, bytes) -> "OEBPS/$name" to bytes },
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0),
        ) ?: error("the book did not open")
    }

    private fun page(doc: EpubDocument) = doc.page(KiteLocation(0, 0))

    @Test
    fun a_video_with_a_poster_paints_the_poster_and_lists_itself() {
        val doc = book(
            """<p>Before.</p><video id="clip" src="clip.mp4" poster="poster.bmp" controls="controls" width="320" height="180"></video><p>After.</p>""",
            manifest = """<item id="p" href="poster.bmp" media-type="image/bmp"/><item id="v" href="clip.mp4" media-type="video/mp4"/>""",
            files = listOf("poster.bmp" to EpubFixtures.bmp2x1(), "clip.mp4" to ByteArray(8) { 1 }),
        )
        val canvas = RecordingCanvas().also { page(doc).renderTo(it) }
        assertEquals(1, canvas.calls.count { it is RecordingCanvas.Call.Image }, "the poster was not painted")
        val media = page(doc).media.single()
        assertEquals(EpubMediaKind.VIDEO, media.kind)
        assertEquals("clip", media.id)
        assertEquals("OEBPS/poster.bmp", media.poster)
        assertEquals(listOf("OEBPS/clip.mp4"), media.sources.map { it.href })
        assertEquals(listOf("video/mp4"), media.sources.map { it.type }, "the type comes from the manifest")
        assertTrue(media.controls)
        assertFalse(media.autoplay)
        // 320 by 180 CSS pixels are 240 by 135 points.
        assertEquals(240.0, media.rect.right - media.rect.left, 0.5)
        assertEquals(135.0, media.rect.top - media.rect.bottom, 0.5)
    }

    @Test
    fun an_audio_player_lists_its_sources_in_order_and_hides_its_fallback() {
        val doc = book(
            """<audio controls="controls" loop="loop"><source src="a.ogg" type="audio/ogg"/><source src="a.mp3"/><p>Fallback words.</p></audio>""",
            manifest = """<item id="o" href="a.ogg" media-type="audio/ogg"/><item id="m" href="a.mp3" media-type="audio/mpeg"/>""",
        )
        val media = page(doc).media.single()
        assertEquals(EpubMediaKind.AUDIO, media.kind)
        assertEquals(listOf("OEBPS/a.ogg" to "audio/ogg", "OEBPS/a.mp3" to "audio/mpeg"), media.sources.map { it.href to it.type })
        assertTrue(media.loop)
        assertNull(media.poster)
        assertEquals(40.0, media.rect.top - media.rect.bottom, 0.5, "an audio player is a 40 pt bar")
        val canvas = RecordingCanvas().also { page(doc).renderTo(it) }
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().map { it.color }
        // The placeholder is a plain grey box. A page plays nothing, so it draws no play button (#31).
        assertTrue(RgbColor(0.85, 0.85, 0.85) in fills, "no placeholder box was painted: $fills")
        assertEquals(1, fills.count { it == RgbColor(0.85, 0.85, 0.85) }, "the placeholder is one box: $fills")
        assertFalse(RgbColor(0.45, 0.45, 0.45) in fills, "a play triangle was painted with no player: $fills")
        assertFalse("Fallback words." in page(doc).textContent().plainText, "the fallback children were painted")
    }

    @Test
    fun an_audio_element_without_controls_is_not_rendered() {
        assertTrue(page(book("""<p>Text.</p><audio src="a.mp3"></audio>""")).media.isEmpty())
    }

    @Test
    fun a_video_without_a_poster_keeps_a_16_to_9_box_and_a_url_source_stays_a_url() {
        val media = page(book("""<video><source src="https://example.com/v.mp4" type="video/mp4"/></video>""")).media.single()
        val width = media.rect.right - media.rect.left
        assertTrue(width > 100.0, "the box has no width: ${media.rect}")
        assertEquals(width * 9.0 / 16.0, media.rect.top - media.rect.bottom, 0.5)
        assertEquals("https://example.com/v.mp4", media.sources.single().href)
    }

    @Test
    fun resource_reads_the_bytes_and_the_type_of_a_file() {
        val bytes = ByteArray(8) { (it * 3).toByte() }
        val doc = book("<p>x</p>", manifest = """<item id="v" href="clip.mp4" media-type="video/mp4"/>""", files = listOf("clip.mp4" to bytes))
        assertContentEquals(bytes, doc.resource("OEBPS/clip.mp4"))
        assertEquals("video/mp4", doc.resourceType("OEBPS/clip.mp4#t=10"))
        assertNull(doc.resource("OEBPS/missing.mp4"))
    }

    @Test
    fun a_media_element_lists_its_tracks_with_their_kind_language_and_label() {
        val doc = book(
            """<video src="clip.mp4" controls="controls">""" +
                """<track src="captions/en.vtt" kind="captions" srclang="en" label="English" default="default"/>""" +
                """<track src="fr.vtt" srclang="fr"/>""" +
                """<track src="desc.vtt" kind="Descriptions"/>""" +
                """<track src="chapters.vtt" kind="chapters"/>""" +
                """<track src="data.vtt" kind="sign-language"/>""" +
                """<track kind="captions" label="No file"/>""" +
                """<track src="https://example.com/de.vtt" kind="subtitles" srclang="de"/>""" +
                """</video>""",
        )
        val tracks = page(doc).media.single().tracks
        assertEquals(
            listOf("OEBPS/captions/en.vtt", "OEBPS/fr.vtt", "OEBPS/desc.vtt", "OEBPS/chapters.vtt", "OEBPS/data.vtt", "https://example.com/de.vtt"),
            tracks.map { it.href },
            "a track without a src is left out",
        )
        assertEquals(
            listOf(EpubTrackKind.CAPTIONS, EpubTrackKind.SUBTITLES, EpubTrackKind.DESCRIPTIONS, EpubTrackKind.CHAPTERS, EpubTrackKind.METADATA, EpubTrackKind.SUBTITLES),
            tracks.map { it.kind },
            "no kind means subtitles, and a kind HTML does not know means metadata",
        )
        assertEquals(listOf("en", "fr", null, null, null, "de"), tracks.map { it.language })
        assertEquals(listOf("English", null, null, null, null, null), tracks.map { it.label })
        assertEquals(listOf(true, false, false, false, false, false), tracks.map { it.isDefault })
    }
}
