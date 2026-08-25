package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.zip.Crc32
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/** Shared test helpers: build a minimal single-document EPUB from a `<body>` string. */
internal object EpubFixtures {

    /** Wrap [bodyHtml] (an entire `<body>...</body>` or just its inner markup) in an EPUB. */
    fun epub(
        bodyHtml: String,
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
        uniqueId: String? = null,
        language: String? = null,
        spineDirection: String? = null,
        primaryWritingMode: String? = null,
        /** Replace the chapter's bytes wholesale, e.g. to store it in another encoding. */
        chapterBytes: ByteArray? = null,
    ): ByteArray {
        val body = if (bodyHtml.trimStart().startsWith("<body")) bodyHtml else "<body>$bodyHtml</body>"
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val metaItems = buildString {
            if (uniqueId != null) append("""<dc:identifier id="uid">$uniqueId</dc:identifier>""")
            if (language != null) append("""<dc:language>$language</dc:language>""")
            if (primaryWritingMode != null) append("""<meta property="primary-writing-mode">$primaryWritingMode</meta>""")
        }
        val metadata = if (metaItems.isNotEmpty()) {
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metaItems</metadata>"""
        } else {
            ""
        }
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              $metadata
              <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine${spineDirection?.let { """ page-progression-direction="$it"""" } ?: ""}><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">$body</html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to (chapterBytes ?: chapter.encodeToByteArray()),
            ) + extraEntries,
        )
    }

    /** Multi-spine book with an EPUB 3 nav document pointing at each chapter. */
    fun epubWithToc(chapters: Int = 4): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val items = (0 until chapters).joinToString("") {
            """<item id="c${it + 1}" href="chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val refs = (0 until chapters).joinToString("") { """<itemref idref="c${it + 1}"/>""" }
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">toc</dc:identifier></metadata>
              <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>$items</manifest>
              <spine>$refs</spine>
            </package>"""
        val links = (0 until chapters).joinToString("") {
            """<li><a href="chapter${it + 1}.xhtml#head$it">Chapter ${it + 1}</a></li>"""
        }
        val nav = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>$links</ol></nav></body></html>"""
        val chapterFiles = (0 until chapters).map { c ->
            val body = "<h1 id=\"head$c\">Chapter ${c + 1}</h1>" +
                (0 until 20).joinToString("") { "<p>Chapter ${c + 1} paragraph $it with a few words on it.</p>" }
            "OEBPS/chapter${c + 1}.xhtml" to
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""".encodeToByteArray()
        }
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/nav.xhtml" to nav.encodeToByteArray(),
            ) + chapterFiles,
        )
    }

    /** Multi-spine variant: one `chapterN.xhtml` per body, all on the spine in order. */
    fun epubMultiSpine(bodies: List<String>): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val items = bodies.indices.joinToString("") {
            """<item id="c${it + 1}" href="chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val refs = bodies.indices.joinToString("") { """<itemref idref="c${it + 1}"/>""" }
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <manifest>$items</manifest>
              <spine>$refs</spine>
            </package>
        """.trimIndent()
        val chapters = bodies.mapIndexed { i, raw ->
            val body = if (raw.trimStart().startsWith("<body")) raw else "<body>$raw</body>"
            "OEBPS/chapter${i + 1}.xhtml" to
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">$body</html>""".encodeToByteArray()
        }
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ) + chapters,
        )
    }

    /**
     * A book laid out in folders the way real EPUBs are: documents under
     * `OEBPS/Text/`, stylesheets under `OEBPS/Styles/`, everything else where the
     * caller puts it. Every chapter links every sheet in [sheets], in order.
     *
     * The folders matter: a `url()` in `Styles/book.css` resolves against
     * `OEBPS/Styles`, not against the chapter's `OEBPS/Text`.
     */
    fun epubFoldered(
        bodies: List<String>,
        sheets: List<Pair<String, String>> = emptyList(),
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
        missingSpineItems: List<String> = emptyList(),
    ): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val docItems = bodies.indices.joinToString("") {
            """<item id="c${it + 1}" href="Text/chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val ghostItems = missingSpineItems.joinToString("") {
            """<item id="$it" href="Text/$it.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val sheetItems = sheets.joinToString("") { (name, _) ->
            """<item id="css-${name.substringBefore('.')}" href="Styles/$name" media-type="text/css"/>"""
        }
        val refs = (bodies.indices.map { "c${it + 1}" } + missingSpineItems)
            .joinToString("") { """<itemref idref="$it"/>""" }
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">folder</dc:identifier></metadata>
              <manifest>$docItems$ghostItems$sheetItems</manifest>
              <spine>$refs</spine>
            </package>"""
        val links = sheets.joinToString("") { (name, _) ->
            """<link rel="stylesheet" type="text/css" href="../Styles/$name"/>"""
        }
        val chapters = bodies.mapIndexed { i, raw ->
            val body = if (raw.trimStart().startsWith("<body")) raw else "<body>$raw</body>"
            "OEBPS/Text/chapter${i + 1}.xhtml" to
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$links</head>$body</html>"""
                    .encodeToByteArray()
        }
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ) + chapters +
                sheets.map { (name, css) -> "OEBPS/Styles/$name" to css.encodeToByteArray() } +
                extraEntries,
        )
    }


    /** A fully valid 2x1 24-bit BMP: red pixel, blue pixel. Decodes for real. */
    fun bmp2x1(): ByteArray {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return h + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    /** Build a STORED (uncompressed) zip, CRCs included so [ZipReader] verifies clean. */
    fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }

        data class Cd(val name: ByteArray, val offset: Int, val size: Int, val crc: Long)
        val cds = ArrayList<Cd>()
        for ((name, data) in entries) {
            val nb = name.encodeToByteArray()
            val offset = out.size
            val crc = Crc32.of(data)
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)
            u32(crc); u32(data.size.toLong()); u32(data.size.toLong())
            u16(nb.size); u16(0)
            raw(nb); raw(data)
            cds.add(Cd(nb, offset, data.size, crc))
        }
        val cdStart = out.size
        for (cd in cds) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0)
            u16(0); u16(0); u32(cd.crc)
            u32(cd.size.toLong()); u32(cd.size.toLong())
            u16(cd.name.size); u16(0); u16(0)
            u16(0); u16(0); u32(0L)
            u32(cd.offset.toLong())
            raw(cd.name)
        }
        val cdSize = out.size - cdStart
        u32(0x06054b50L); u16(0); u16(0)
        u16(cds.size); u16(cds.size)
        u32(cdSize.toLong()); u32(cdStart.toLong()); u16(0)
        return out.toByteArray()
    }
}
