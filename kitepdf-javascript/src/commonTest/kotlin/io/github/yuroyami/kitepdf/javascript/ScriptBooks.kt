package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings

/** Small scripted books, built in memory (#41). */
internal object ScriptBooks {

    /** The heading band of [buttonPage], before and after its button's script. */
    const val RED = "rgb(255, 0, 0)"
    const val BLUE = "rgb(0, 0, 255)"

    /**
     * A fixed-layout book of one scripted page, 300 by 200 CSS pixels, so 225 by 150 points: a
     * red heading band 60 pixels tall across the top, and a button at (10, 100), 120 by 40, whose
     * centre is at (52.5, 90) points. [script] runs at the end of the body; `page.js`, loaded
     * from the book, defines `paint()`, which turns the band blue.
     */
    fun buttonPage(
        button: String = """<button id="go" type="button" onclick="paint()">Go</button>""",
        script: String = "",
        css: String = "",
        head: String = "",
    ): EpubDocument = book(
        metadata = """<meta property="rendition:layout">pre-paginated</meta>""",
        items = listOf(
            Item("page.xhtml", "application/xhtml+xml", properties = "scripted", spine = true),
            Item("next.xhtml", "application/xhtml+xml", spine = true),
            Item("page.js", "text/javascript"),
            Item("page.css", "text/css"),
        ),
        files = mapOf(
            "page.xhtml" to xhtml(
                head = """<meta name="viewport" content="width=300, height=200"/>""" +
                    """<link rel="stylesheet" type="text/css" href="page.css"/><script type="text/javascript" src="page.js"></script>$head""",
                body = """<h1 id="band">Band</h1>$button""" + (if (script.isEmpty()) "" else "<script>$script</script>"),
            ),
            "next.xhtml" to xhtml(head = """<meta name="viewport" content="width=300, height=200"/>""", body = """<p id="n">Next page.</p>"""),
            "page.js" to """function paint() { document.getElementById('band').style.background = '$BLUE'; }""",
            "page.css" to """body { margin: 0; }
                |h1 { margin: 0; height: 60px; background: $RED; color: $RED; }
                |#go { display: block; position: absolute; left: 10px; top: 100px; width: 120px; height: 40px; margin: 0; }
                |$css""".trimMargin(),
        ),
    )

    /** A reflowable book of one chapter whose body is [body], on pages 300 by 200 points. */
    fun chapter(body: String, extraFiles: Map<String, String> = emptyMap()): EpubDocument = book(
        items = listOf(Item("chapter.xhtml", "application/xhtml+xml", properties = "scripted", spine = true)) +
            extraFiles.keys.map { Item(it, if (it.endsWith(".js")) "text/javascript" else "application/xhtml+xml", spine = it.endsWith(".xhtml")) },
        files = mapOf("chapter.xhtml" to xhtml(body = body)) + extraFiles,
        settings = EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0),
    )

    class Item(val href: String, val type: String, val properties: String? = null, val spine: Boolean = false)

    fun xhtml(head: String = "", body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Scripted</title>$head</head><body>$body</body></html>"""

    fun book(
        items: List<Item>,
        files: Map<String, String>,
        metadata: String = "",
        settings: EpubSettings = EpubSettings(),
        identifier: String = "scripted",
    ): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val manifest = items.mapIndexed { i, item ->
            val props = item.properties?.let { """ properties="$it"""" }.orEmpty()
            """<item id="i$i" href="${item.href}" media-type="${item.type}"$props/>"""
        }.joinToString("")
        val spine = items.mapIndexedNotNull { i, item -> if (item.spine) """<itemref idref="i$i"/>""" else null }.joinToString("")
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$identifier</dc:identifier><dc:title>Scripted</dc:title>$metadata</metadata>
            <manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
        ) + files.map { (name, text) -> "OEBPS/$name" to text }
        return EpubDocument.open(storedZip(entries.map { (name, text) -> name to text.encodeToByteArray() }), settings)
    }

    /** A zip of [entries], each stored as it is. */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
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
