package sample

/**
 * Builds a small EPUB in memory, chapter by chapter, so the sample has a
 * reflowable book to open without shipping someone else's novel.
 *
 * It is deliberately long enough to matter: 24 chapters is enough that laying
 * the whole thing out would be visible, which is what the incremental reader
 * exists to avoid.
 */
object DemoEpub {

    val book: ByteArray by lazy { build(chapters = 24) }

    private const val CSS = """
        body { margin: 0; font-family: serif; line-height: 1.5 }
        h1 { font-size: 1.6em; margin: 0 0 0.6em; text-align: center }
        p { margin: 0 0 0.7em; text-indent: 1.2em; text-align: justify }
        p.first { text-indent: 0 }
        blockquote { margin: 1em 2em; font-style: italic }
    """

    private fun chapterBody(index: Int): String {
        val n = index + 1
        val paragraphs = (0 until 14).joinToString("") { p ->
            val cls = if (p == 0) " class=\"first\"" else ""
            "<p$cls>${sentence(index, p)}</p>"
        }
        val quote = if (index % 4 == 0) {
            "<blockquote>A page is not a thing a reflowable book has. " +
                "It is a thing a reader's screen makes.</blockquote>"
        } else {
            ""
        }
        return "<h1 id=\"start\">Chapter $n</h1>$quote$paragraphs"
    }

    /** Filler with enough variation that pages are visibly different. */
    private fun sentence(chapter: Int, paragraph: Int): String {
        val words = listOf(
            "The reader opens the book somewhere in the middle and expects a page.",
            "Nothing about a chapter's text tells you which page it lands on.",
            "Change the font size and every page boundary in the book moves.",
            "So a position worth saving is a place in the text, not a page number.",
            "This paragraph exists to take up room and to wrap onto several lines.",
            "Chapter ${chapter + 1}, paragraph ${paragraph + 1}, saying very little at some length.",
        )
        return (0 until 5).joinToString(" ") { words[(chapter * 7 + paragraph * 3 + it) % words.size] }
    }

    private fun build(chapters: Int): ByteArray {
        val container = """<?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>"""

        val items = (0 until chapters).joinToString("") {
            """<item id="c${it + 1}" href="Text/chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val refs = (0 until chapters).joinToString("") { """<itemref idref="c${it + 1}"/>""" }
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="uid">urn:uuid:kitepdf-sample-book</dc:identifier>
                <dc:title>A Book About Pages</dc:title>
                <dc:creator>The KitePDF sample</dc:creator>
                <dc:language>en</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                <item id="css" href="Styles/book.css" media-type="text/css"/>
                $items
              </manifest>
              <spine>$refs</spine>
            </package>"""

        val navLinks = (0 until chapters).joinToString("") {
            """<li><a href="Text/chapter${it + 1}.xhtml#start">Chapter ${it + 1}</a></li>"""
        }
        val nav = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>Contents</title></head>
              <body><nav epub:type="toc"><h1>Contents</h1><ol>$navLinks</ol></nav></body>
            </html>"""

        val chapterFiles = (0 until chapters).map { i ->
            val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
                  <head>
                    <title>Chapter ${i + 1}</title>
                    <link rel="stylesheet" type="text/css" href="../Styles/book.css"/>
                  </head>
                  <body>${chapterBody(i)}</body>
                </html>"""
            "OEBPS/Text/chapter${i + 1}.xhtml" to xhtml.encodeToByteArray()
        }

        return storedZip(
            listOf(
                // The mimetype entry goes first and uncompressed, per the OCF spec.
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/nav.xhtml" to nav.encodeToByteArray(),
                "OEBPS/Styles/book.css" to CSS.encodeToByteArray(),
            ) + chapterFiles,
        )
    }

    /** A STORED (uncompressed) zip. Enough for an OCF container, and no deflate to write. */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }

        class Central(val name: ByteArray, val offset: Int, val size: Int)
        val central = ArrayList<Central>()
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val offset = out.size
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)
            u32(0L); u32(data.size.toLong()); u32(data.size.toLong())
            u16(nameBytes.size); u16(0)
            raw(nameBytes); raw(data)
            central.add(Central(nameBytes, offset, data.size))
        }
        val start = out.size
        for (c in central) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0)
            u16(0); u16(0); u32(0L)
            u32(c.size.toLong()); u32(c.size.toLong())
            u16(c.name.size); u16(0); u16(0)
            u16(0); u16(0); u32(0L)
            u32(c.offset.toLong())
            raw(c.name)
        }
        val size = out.size - start
        u32(0x06054b50L); u16(0); u16(0)
        u16(central.size); u16(central.size)
        u32(size.toLong()); u32(start.toLong()); u16(0)
        return out.toByteArray()
    }
}
