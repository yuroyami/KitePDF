package io.github.yuroyami.kitepdf.epub

/** Shared test helpers: build a minimal single-document EPUB from a `<body>` string. */
internal object EpubFixtures {

    /** Wrap [bodyHtml] (an entire `<body>...</body>` or just its inner markup) in an EPUB. */
    fun epub(
        bodyHtml: String,
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
        uniqueId: String? = null,
        language: String? = null,
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
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">$body</html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ) + extraEntries,
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

    /** Build a STORED (uncompressed) zip. CRCs are left zero; [ZipReader] does not verify them. */
    fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }

        data class Cd(val name: ByteArray, val offset: Int, val size: Int)
        val cds = ArrayList<Cd>()
        for ((name, data) in entries) {
            val nb = name.encodeToByteArray()
            val offset = out.size
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)
            u32(0L); u32(data.size.toLong()); u32(data.size.toLong())
            u16(nb.size); u16(0)
            raw(nb); raw(data)
            cds.add(Cd(nb, offset, data.size))
        }
        val cdStart = out.size
        for (cd in cds) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0)
            u16(0); u16(0); u32(0L)
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
