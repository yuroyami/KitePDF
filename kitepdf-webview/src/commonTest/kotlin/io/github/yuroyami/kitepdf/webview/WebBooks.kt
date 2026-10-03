package io.github.yuroyami.kitepdf.webview

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings

/** Small books with scripted content, built in memory (#41). */
internal object WebBooks {

    /** The heading of [scriptedPage]: red until its button is pressed, then blue. */
    const val RED = "rgb(255, 0, 0)"
    const val BLUE = "rgb(0, 0, 255)"

    /**
     * A fixed-layout book of one scripted page, 300 by 200 CSS pixels: a red heading band across
     * the top 60 pixels, and a button at (10, 100), 120 by 40, whose script turns the band blue.
     * The script and the style are files of their own, so the page loads them from the book.
     */
    fun scriptedPage(): EpubDocument = book(
        metadata = """<meta property="rendition:layout">pre-paginated</meta>""",
        items = listOf(
            Item("page.xhtml", "application/xhtml+xml", properties = "scripted", spine = true),
            Item("page.js", "text/javascript"),
            Item("page.css", "text/css"),
        ),
        files = mapOf(
            "page.xhtml" to xhtml(
                head = """<meta name="viewport" content="width=300, height=200"/>""" +
                    """<link rel="stylesheet" type="text/css" href="page.css"/><script type="text/javascript" src="page.js"></script>""",
                body = """<h1 id="band">Band</h1><button id="go" type="button" onclick="paint()">Go</button>""",
            ),
            "page.js" to """function paint() { document.getElementById('band').style.background = '$BLUE'; }""",
            "page.css" to """body { margin: 0; background: white; }
                |h1 { margin: 0; height: 60px; background: $RED; color: $RED; }
                |#go { position: absolute; left: 10px; top: 100px; width: 120px; height: 40px; margin: 0; }""".trimMargin(),
        ),
    )

    /**
     * A reflowable chapter with a quiz in an inline frame of 200 by 100 CSS pixels between two
     * paragraphs. The quiz is green, loads a picture from the book by a relative path and one from
 * the root of the container, `/OEBPS/root.svg`, and asks for a picture at
     * [outside], which a web view must never fetch. A link in the quiz goes to the next chapter, [NEXT].
     */
    fun quizChapter(outside: String = "http://127.0.0.1:9/outside.png"): EpubDocument = book(
        items = listOf(
            Item("chapter.xhtml", "application/xhtml+xml", spine = true),
            Item("next.xhtml", "application/xhtml+xml", spine = true),
            Item("quiz.xhtml", "application/xhtml+xml", properties = "scripted"),
            Item("dot.svg", "image/svg+xml"),
            Item("root.svg", "image/svg+xml"),
        ),
        files = mapOf(
            "chapter.xhtml" to xhtml(
                body = """<p>Before the quiz.</p><iframe id="quiz" src="quiz.xhtml" width="200" height="100"></iframe><p>After the quiz.</p>""",
            ),
            "next.xhtml" to xhtml(body = """<p id="next">The next chapter.</p>"""),
            "quiz.xhtml" to xhtml(
                head = """<style type="text/css">html, body { margin: 0; height: 100%; background: rgb(0, 160, 0); }</style>""",
                body = """<img src="dot.svg" width="10" height="10" alt=""/><img src="$outside" width="10" height="10" alt=""/>""" +
                    """<img src="/OEBPS/root.svg" width="10" height="10" alt=""/>""" +
                    """<a id="next" href="next.xhtml#next" style="display: block; width: 100px; height: 40px;">Next</a>""",
            ),
            "dot.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="black"/></svg>""",
            "root.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10" fill="black"/></svg>""",
        ),
        settings = EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0),
    )

    /** Where the link in the quiz of [quizChapter] goes. */
    const val NEXT = "OEBPS/next.xhtml#next"

    class Item(val href: String, val type: String, val properties: String? = null, val spine: Boolean = false)

    fun xhtml(head: String = "", body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title>$head</head><body>$body</body></html>"""

    fun book(
        items: List<Item>,
        files: Map<String, String>,
        metadata: String = "",
        settings: EpubSettings = EpubSettings(),
    ): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val manifest = items.mapIndexed { i, item ->
            val props = item.properties?.let { """ properties="$it"""" }.orEmpty()
            """<item id="i$i" href="${item.href}" media-type="${item.type}"$props/>"""
        }.joinToString("")
        val spine = items.mapIndexedNotNull { i, item -> if (item.spine) """<itemref idref="i$i"/>""" else null }.joinToString("")
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">web-book</dc:identifier><dc:title>Web</dc:title>$metadata</metadata>
            <manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
        ) + files.map { (name, text) -> "OEBPS/$name" to text }
        return EpubDocument.open(storedZip(entries.map { (name, text) -> name to text.encodeToByteArray() }), settings)
    }

    /** A zip of [entries], each stored as it is. */
    fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        val central = ArrayList<Byte>()
        fun le(v: Long, n: Int, to: MutableList<Byte>) = repeat(n) { to.add(((v shr (8 * it)) and 0xFF).toByte()) }
        var count = 0
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val offset = out.size.toLong()
            le(0x04034b50, 4, out); le(20, 2, out); le(0, 2, out); le(0, 2, out); le(0, 4, out)
            le(crc, 4, out); le(data.size.toLong(), 4, out); le(data.size.toLong(), 4, out)
            le(nameBytes.size.toLong(), 2, out); le(0, 2, out)
            nameBytes.forEach(out::add); data.forEach(out::add)
            le(0x02014b50, 4, central); le(20, 2, central); le(20, 2, central); le(0, 2, central); le(0, 2, central); le(0, 4, central)
            le(crc, 4, central); le(data.size.toLong(), 4, central); le(data.size.toLong(), 4, central)
            le(nameBytes.size.toLong(), 2, central); le(0, 2, central); le(0, 2, central); le(0, 2, central); le(0, 2, central)
            le(0, 4, central); le(offset, 4, central)
            nameBytes.forEach(central::add)
            count++
        }
        val centralOffset = out.size.toLong()
        out.addAll(central)
        le(0x06054b50, 4, out); le(0, 2, out); le(0, 2, out); le(count.toLong(), 2, out); le(count.toLong(), 2, out)
        le(central.size.toLong(), 4, out); le(centralOffset, 4, out); le(0, 2, out)
        return out.toByteArray()
    }

    private fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            crc = crc xor (b.toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
        }
        return crc xor 0xFFFFFFFFL
    }
}
