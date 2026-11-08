package io.github.yuroyami.kitepdf.epub.convert

import io.github.yuroyami.kitepdf.core.text.TextEncoding

/**
 * A plain text file as a book (#947): decoded by its BOM, then as UTF-8, then by the WHATWG
 * fallback, each run of blank lines starting a paragraph. Other line breaks are kept, or joined
 * by a space with [joinLines]. A long file splits into chapters of about [CHAPTER_CHARS]
 * characters at paragraph ends, so a reader lays out its first page without the whole file.
 */
internal object TextBook {

    const val CHAPTER_CHARS = 100_000

    fun convert(bytes: ByteArray, title: String, joinLines: Boolean): ByteArray {
        val text = TextEncoding.decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = text.split(Regex("\n[ \t]*\n+")).map { it.trim('\n') }.filter { it.isNotBlank() }
        val book = BookPackager(title)
        val current = StringBuilder()
        var count = 0
        fun flush() {
            if (current.isEmpty() && count > 0) return
            val file = "text${count + 1}.xhtml"
            book.chapter(BookPackager.Chapter(file, xhtml(title, current.toString())))
            count++
            current.clear()
        }
        for (p in paragraphs) {
            val body = if (joinLines) BookPackager.text(p.replace(Regex("[ \t]*\n[ \t]*"), " ")) else p.split('\n').joinToString("<br/>") { BookPackager.text(it) }
            current.append("<p>").append(body).append("</p>\n")
            if (current.length >= CHAPTER_CHARS) flush()
        }
        if (current.isNotEmpty() || count == 0) flush()
        return book.build()
    }

    private fun xhtml(title: String, body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${BookPackager.text(title)}</title>""" +
            """<style>p{margin:0 0 0.8em 0;white-space:pre-wrap}</style></head><body>$body</body></html>"""
}

/**
 * An HTML or XHTML file as a book of one chapter (#947), parsed as the EPUB engine parses a
 * `text/html` chapter. The style sheets and images it names by a relative URL come through
 * [resources], which gets the URL as written and gives the bytes or null; without it they are left out.
 */
internal object HtmlBook {

    fun convert(bytes: ByteArray, title: String?, resources: ((String) -> ByteArray?)?): ByteArray {
        val text = TextEncoding.decode(bytes)
        val xhtml = !text.trimStart().startsWith("<!doctype html", ignoreCase = true) && text.contains("http://www.w3.org/1999/xhtml")
        val name = title ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled"
        val book = BookPackager(name)
        val file = if (xhtml) "index.xhtml" else "index.html"
        book.chapter(BookPackager.Chapter(file, text, if (xhtml) "application/xhtml+xml" else "text/html"))
        if (resources != null) {
            for (m in URL.findAll(text)) {
                val url = m.groupValues[2].trim()
                if (url.isEmpty() || url.startsWith("#") || url.contains("://") || url.startsWith("data:") || url.startsWith("/")) continue
                val path = url.substringBefore('#').substringBefore('?')
                val data = runCatching { resources(url) }.getOrNull() ?: continue
                book.resource(path, data, mediaTypeOf(path))
            }
        }
        return book.build()
    }

    private val URL = Regex("""\b(src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun mediaTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "js" -> "text/javascript"
        else -> "application/octet-stream"
    }
}
