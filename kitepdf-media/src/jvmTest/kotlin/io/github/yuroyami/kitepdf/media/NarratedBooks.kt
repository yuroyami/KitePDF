package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Books with synchronised narration for the tests, and the audio they read from (#36). */
internal object NarratedBooks {

    /** A chapter: its body, and the `par` elements of its media overlay, or null for a chapter without one. */
    class Chapter(val body: String, val pars: String? = null)

    /**
     * A synthetic audio file: [seconds] of a 440 Hz tone as 16-bit mono PCM at 16 kHz, in a WAV
     * that FFmpeg reads with an exact duration.
     */
    fun wav(seconds: Int): ByteArray {
        val rate = 16_000
        val samples = ByteBuffer.allocate(seconds * rate * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until seconds * rate) samples.putShort((sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort())
        val data = samples.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".encodeToByteArray()).putInt(36 + data.size).put("WAVE".encodeToByteArray())
        header.put("fmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".encodeToByteArray()).putInt(data.size)
        return header.array() + data
    }

    /** A `par` that reads the element [id] of chapter [chapter], one-based, from [audio] between [begin] and [end] seconds. */
    fun par(chapter: Int, id: String, audio: String?, begin: Double, end: Double): String {
        val sound = audio?.let { """<audio src="$it" clipBegin="${begin}s" clipEnd="${end}s"/>""" }.orEmpty()
        return """<par id="$id"><text src="c$chapter.xhtml#$id"/>$sound</par>"""
    }

    /**
     * A book of [chapters], `c1.xhtml` onward, with [files] beside them in `OEBPS/`. Its pages are
     * 300 by 200 points with a margin of 20, so a little text fills a page.
     */
    fun book(chapters: List<Chapter>, files: Map<String, ByteArray>): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to container.encodeToByteArray(),
        )
        val texts = LinkedHashMap<String, ByteArray>()
        chapters.forEachIndexed { i, chapter ->
            val n = i + 1
            val overlay = if (chapter.pars != null) """ media-overlay="mo$n"""" else ""
            manifest.append("""<item id="c$n" href="c$n.xhtml" media-type="application/xhtml+xml"$overlay/>""")
            spine.append("""<itemref idref="c$n"/>""")
            texts["OEBPS/c$n.xhtml"] = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>${chapter.body}</body></html>""".encodeToByteArray()
            if (chapter.pars != null) {
                manifest.append("""<item id="mo$n" href="c$n.smil" media-type="application/smil+xml"/>""")
                texts["OEBPS/c$n.smil"] = ("""<?xml version="1.0"?><smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">""" +
                    """<body><seq>${chapter.pars}</seq></body></smil>""").encodeToByteArray()
            }
        }
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">narrated</dc:identifier></metadata>""" +
            """<manifest>$manifest${MediaBooks.items(files.keys)}</manifest><spine>$spine</spine></package>"""
        entries["OEBPS/content.opf"] = opf.encodeToByteArray()
        entries.putAll(texts)
        for ((name, bytes) in files) entries["OEBPS/$name"] = bytes
        return EpubDocument.open(MediaBooks.storedZip(entries), EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0))
    }
}
