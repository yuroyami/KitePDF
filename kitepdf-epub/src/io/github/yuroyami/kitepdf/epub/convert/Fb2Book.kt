package io.github.yuroyami.kitepdf.epub.convert

import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.text.TextEncoding
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/**
 * A FictionBook 2 book (#946) as an EPUB package: `description/title-info` gives the metadata,
 * each top-level `section` of the main body a chapter, the section titles the table of contents,
 * `binary` elements the images, and a `body name="notes"` the footnotes. Lenient as the other
 * readers: an unknown element keeps its text, and a broken `binary` is left out with a warning.
 */
internal object Fb2Book {

    /** Whether [bytes] are an FB2 file: XML whose root is `FictionBook`. */
    fun isFb2(bytes: ByteArray): Boolean {
        val head = bytes.copyOf(minOf(bytes.size, 2048)).decodeToString()
        return head.contains("<FictionBook")
    }

    /** The FB2 file inside a ZIP, as `.fb2.zip` books ship, or null. */
    fun fromZip(bytes: ByteArray): ByteArray? = runCatching {
        val zip = ZipReader(bytes)
        val name = zip.names.firstOrNull { it.lowercase().endsWith(".fb2") } ?: return null
        zip.read(name)
    }.getOrNull()

    private class Meta(var title: String = "Untitled", var lang: String = "en", val authors: MutableList<String> = ArrayList(), var annotation: String? = null, var cover: String? = null)

    fun convert(bytes: ByteArray): ByteArray {
        val root = KiteXml.parse(TextEncoding.decode(bytes)).let { r -> if (local(r.tag) == "fictionbook") r else r.find("fictionbook") ?: r }
        val meta = Meta()
        root.child("description")?.child("title-info")?.let { info ->
            info.child("book-title")?.let { meta.title = textOf(it).trim().ifEmpty { meta.title } }
            info.child("lang")?.let { meta.lang = textOf(it).trim().ifEmpty { meta.lang } }
            for (a in info.children("author")) {
                val name = listOf("first-name", "middle-name", "last-name").mapNotNull { a.child(it)?.let(::textOf)?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                (name.ifEmpty { a.child("nickname")?.let(::textOf)?.trim().orEmpty() }).takeIf { it.isNotEmpty() }?.let(meta.authors::add)
            }
            info.child("annotation")?.let { meta.annotation = textOf(it).trim() }
            info.child("coverpage")?.child("image")?.let { meta.cover = hrefOf(it)?.removePrefix("#") }
        }
        val book = BookPackager(meta.title, meta.lang)
        book.authors = meta.authors
        book.description = meta.annotation
        // The images: base64 inside the file.
        val images = HashMap<String, String>()
        for (bin in root.children("binary")) {
            val id = bin.attrs["id"] ?: continue
            val type = bin.attrs["content-type"] ?: "image/jpeg"
            val data = decodeBase64(textOf(bin))
            if (data == null) { kiteWarn { "fb2: binary $id does not decode and is left out" }; continue }
            val path = "images/" + safe(id)
            book.resource(path, data, type)
            images[id] = path
        }
        meta.cover?.let { images[it] }?.let { book.coverImage = it }

        val bodies = root.children("body")
        val main = bodies.firstOrNull { it.attrs["name"] != "notes" } ?: bodies.firstOrNull()
        val notes = bodies.firstOrNull { it.attrs["name"] == "notes" && it !== main }
        // Where each id lives, so a link anywhere goes to the right file.
        val fileOf = HashMap<String, String>()
        val chapters = ArrayList<Pair<String, List<KiteXmlNode.Element>>>()
        val sections = main?.children("section").orEmpty()
        val before = main?.children?.filterIsInstance<KiteXmlNode.Element>()?.filter { local(it.tag) != "section" }.orEmpty()
        if (sections.isEmpty()) chapters += "ch1.xhtml" to (main?.children?.filterIsInstance<KiteXmlNode.Element>().orEmpty())
        else sections.forEachIndexed { i, s -> chapters += "ch${i + 1}.xhtml" to (if (i == 0) before + s else listOf(s)) }
        for ((file, els) in chapters) for (e in els) collectIds(e, file, fileOf)
        notes?.let { collectIds(it, "notes.xhtml", fileOf) }

        val conv = Converter(images, fileOf)
        for ((file, els) in chapters) {
            val body = StringBuilder()
            for (e in els) conv.block(e, body, 1)
            book.chapter(BookPackager.Chapter(file, page(meta.title, body.toString())))
        }
        if (notes != null) {
            val body = StringBuilder()
            for (c in notes.children) if (c is KiteXmlNode.Element) conv.block(c, body, 1)
            book.chapter(BookPackager.Chapter("notes.xhtml", page(meta.title, body.toString())))
        }
        // The table of contents from the titles of the sections, nested as they are.
        fun navOf(section: KiteXmlNode.Element, file: String): BookPackager.NavEntry? {
            val label = section.child("title")?.let(::textOf)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val id = section.attrs["id"]
            val kids = section.children("section").mapNotNull { navOf(it, file) }
            return BookPackager.NavEntry(label, if (id != null) "$file#$id" else file, kids)
        }
        sections.forEachIndexed { i, s ->
            navOf(s, "ch${i + 1}.xhtml")?.let(book::nav) ?: book.nav(BookPackager.NavEntry("Chapter ${i + 1}", "ch${i + 1}.xhtml"))
        }
        if (notes != null) book.nav(BookPackager.NavEntry("Notes", "notes.xhtml"))
        return book.build()
    }

    private fun page(title: String, body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${BookPackager.text(title)}</title>""" +
            "<style>$CSS</style></head><body>$body</body></html>"

    private const val CSS = "p{margin:0;text-indent:1.5em}.subtitle{text-align:center;font-weight:bold;text-indent:0}" +
        ".poem{margin:1em 2em}.stanza{margin:0 0 1em 0}.v{text-indent:0}.epigraph{margin:1em 0 1em 30%;font-style:italic}" +
        ".author{text-align:right;text-indent:0;font-style:italic}.empty{height:1em}h1,h2,h3,h4,h5,h6{text-align:center}"

    private class Converter(val images: Map<String, String>, val fileOf: Map<String, String>) {
        fun block(e: KiteXmlNode.Element, out: StringBuilder, depth: Int) {
            val id = e.attrs["id"]?.let { """ id="${BookPackager.attr(it)}"""" } ?: ""
            when (local(e.tag)) {
                "section" -> { out.append("<section$id>"); for (c in e.children) if (c is KiteXmlNode.Element) block(c, out, depth + 1) else inlineText(c, out); out.append("</section>") }
                "title" -> { val h = "h${depth.coerceIn(1, 6)}"; out.append("<$h$id>"); for (c in e.children) if (c is KiteXmlNode.Element && local(c.tag) == "p") { inline(c, out); out.append(" ") } else inlineNode(c, out); out.append("</$h>") }
                "p" -> { out.append("<p$id>"); inline(e, out); out.append("</p>") }
                "subtitle" -> { out.append("<p class=\"subtitle\"$id>"); inline(e, out); out.append("</p>") }
                "empty-line" -> out.append("<div class=\"empty\"></div>")
                "poem" -> wrap(e, "div class=\"poem\"", "div", out, depth, id)
                "stanza" -> wrap(e, "div class=\"stanza\"", "div", out, depth, id)
                "v" -> { out.append("<p class=\"v\"$id>"); inline(e, out); out.append("</p>") }
                "epigraph" -> wrap(e, "blockquote class=\"epigraph\"", "blockquote", out, depth, id)
                "cite" -> wrap(e, "blockquote", "blockquote", out, depth, id)
                "annotation" -> wrap(e, "div", "div", out, depth, id)
                "text-author" -> { out.append("<p class=\"author\"$id>"); inline(e, out); out.append("</p>") }
                "image" -> image(e, out, block = true)
                "table" -> {
                    out.append("<table$id>")
                    for (tr in e.children) if (tr is KiteXmlNode.Element && local(tr.tag) == "tr") {
                        out.append("<tr>")
                        for (cell in tr.children) if (cell is KiteXmlNode.Element) {
                            val tag = if (local(cell.tag) == "th") "th" else "td"
                            val spans = listOf("colspan", "rowspan").mapNotNull { a -> cell.attrs[a]?.let { " $a=\"${BookPackager.attr(it)}\"" } }.joinToString("")
                            out.append("<$tag$spans>"); inline(cell, out); out.append("</$tag>")
                        }
                        out.append("</tr>")
                    }
                    out.append("</table>")
                }
                // An unknown element keeps its text, as a paragraph.
                else -> { out.append("<div$id>"); for (c in e.children) if (c is KiteXmlNode.Element) block(c, out, depth) else inlineText(c, out); out.append("</div>") }
            }
        }

        private fun wrap(e: KiteXmlNode.Element, open: String, close: String, out: StringBuilder, depth: Int, id: String) {
            out.append("<$open$id>")
            for (c in e.children) if (c is KiteXmlNode.Element) block(c, out, depth) else inlineText(c, out)
            out.append("</$close>")
        }

        fun inline(e: KiteXmlNode.Element, out: StringBuilder) { for (c in e.children) inlineNode(c, out) }

        private fun inlineText(n: KiteXmlNode, out: StringBuilder) {
            if (n is KiteXmlNode.Text && n.text.isNotBlank()) out.append(BookPackager.text(n.text))
        }

        fun inlineNode(n: KiteXmlNode, out: StringBuilder) {
            when (n) {
                is KiteXmlNode.Text -> out.append(BookPackager.text(n.text))
                is KiteXmlNode.Element -> {
                    val tag = when (local(n.tag)) {
                        "emphasis" -> "em"
                        "strong" -> "strong"
                        "strikethrough" -> "del"
                        "sub" -> "sub"
                        "sup" -> "sup"
                        "code" -> "code"
                        "style" -> "span"
                        "a" -> null
                        "image" -> { image(n, out, block = false); return }
                        else -> "span"
                    }
                    if (tag == null) {
                        val href = hrefOf(n) ?: ""
                        val target = if (href.startsWith("#")) (fileOf[href.removePrefix("#")]?.let { "$it$href" } ?: href) else href
                        val note = if (n.attrs["type"] == "note") """ epub:type="noteref"""" else ""
                        out.append("""<a href="${BookPackager.attr(target)}"$note>"""); inline(n, out); out.append("</a>")
                    } else {
                        out.append("<$tag>"); inline(n, out); out.append("</$tag>")
                    }
                }
                else -> {}
            }
        }

        private fun image(e: KiteXmlNode.Element, out: StringBuilder, block: Boolean) {
            val path = hrefOf(e)?.removePrefix("#")?.let { images[it] } ?: return
            val alt = BookPackager.attr(e.attrs["alt"] ?: "")
            if (block) out.append("""<p style="text-align:center;text-indent:0"><img src="$path" alt="$alt"/></p>""")
            else out.append("""<img src="$path" alt="$alt"/>""")
        }
    }

    private fun collectIds(e: KiteXmlNode.Element, file: String, into: MutableMap<String, String>) {
        e.attrs["id"]?.let { into.putIfAbsent(it, file) }
        for (c in e.children) if (c is KiteXmlNode.Element) collectIds(c, file, into)
    }

    /** `l:href`, `xlink:href` or `href`: the parser may keep the prefix or drop it. */
    private fun hrefOf(e: KiteXmlNode.Element): String? = e.attrs["l:href"] ?: e.attrs["xlink:href"] ?: e.attrs["href"]
        ?: e.attrs.entries.firstOrNull { it.key.endsWith(":href") }?.value

    private fun local(tag: String): String = tag.substringAfter(':').lowercase()

    private fun KiteXmlNode.Element.child(name: String): KiteXmlNode.Element? =
        children.firstOrNull { it is KiteXmlNode.Element && local(it.tag) == name } as? KiteXmlNode.Element

    private fun KiteXmlNode.Element.children(name: String): List<KiteXmlNode.Element> =
        children.filterIsInstance<KiteXmlNode.Element>().filter { local(it.tag) == name }

    private fun KiteXmlNode.Element.find(name: String): KiteXmlNode.Element? {
        if (local(tag) == name) return this
        for (c in children) if (c is KiteXmlNode.Element) c.find(name)?.let { return it }
        return null
    }

    private fun textOf(el: KiteXmlNode.Element): String = buildString {
        for (c in el.children) when (c) {
            is KiteXmlNode.Text -> append(c.text)
            is KiteXmlNode.Element -> append(textOf(c))
            else -> {}
        }
    }

    private fun safe(id: String): String = id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")

    /** Base64 of RFC 4648, white space ignored, or null when it does not decode. */
    private fun decodeBase64(s: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = io.github.yuroyami.kitepdf.core.ByteArrayBuilder(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in s) {
            if (ch.isWhitespace() || ch == '=') continue
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.append(((acc ushr bits) and 0xFF).toByte()) }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
