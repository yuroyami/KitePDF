package io.github.yuroyami.kitepdf.webview

import io.github.yuroyami.kitepdf.epub.EpubDocument

/**
 * An answer from the book to a web view's request: the file at a zip path, or nothing. Every
 * answer carries [headers] that keep the page to the book, so a web view that a platform
 * cannot stop from asking the network still loads nothing from it.
 */
internal class BookResponse(val status: Int, val mimeType: String, val body: ByteArray) {
    /** The text encoding of a text answer, which some platforms take apart from its type. */
    val charset: String? get() = if (BookFiles.isText(mimeType)) "utf-8" else null

    val headers: Map<String, String> get() = BookFiles.BOOK_HEADERS

    val reason: String get() = if (status == 200) "OK" else "Not Found"
}

/**
 * The files of [document], answered by zip path for a web view that shows its scripted content
 * (#41). An HTML or XHTML document gets the island script, [ISLAND_SCRIPT], at the top of its
 * head, so its links go to the viewer instead of replacing the island.
 */
internal class BookFiles(val document: EpubDocument) {

    /** The answer to a request for [path], a zip path with no query and no fragment. */
    fun respond(path: String): BookResponse {
        val bytes = document.resource(path) ?: return BookResponse(404, "text/plain", ByteArray(0))
        val type = document.resourceType(path)?.lowercase()?.substringBefore(';')?.trim() ?: typeOf(path)
        val body = if (type == XHTML || type == HTML) withIslandScript(bytes, xml = type == XHTML) else bytes
        return BookResponse(200, type, body)
    }

    companion object {
        const val XHTML = "application/xhtml+xml"
        const val HTML = "text/html"

        /** The media type of a file the manifest gives none, by its extension. */
        fun typeOf(path: String): String = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "xhtml", "xht" -> XHTML
            "html", "htm" -> HTML
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "xml", "opf", "ncx", "smil" -> "application/xml"
            "txt" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

        fun isText(type: String): Boolean = type.startsWith("text/") || type == XHTML || type == "application/json" ||
            type == "application/xml" || type == "image/svg+xml" || type == "application/javascript"

        /**
         * [bytes] with [ISLAND_SCRIPT] after the opening tag of the head, or of the root element
         * when there is no head. A document that is not UTF-8 goes as it is: injected text would
         * not decode in its encoding. In XHTML the script sits in a CDATA section.
         */
        fun withIslandScript(bytes: ByteArray, xml: Boolean): ByteArray {
            // A byte order mark of UTF-16 or UTF-32 starts with 0xFE, 0xFF or 0x00.
            if (bytes.isNotEmpty() && (bytes[0] == 0xFE.toByte() || bytes[0] == 0xFF.toByte() || bytes[0] == 0.toByte())) return bytes
            val text = bytes.decodeToString()
            if ('�' in text) return bytes
            val script = if (xml) "<script type=\"text/javascript\">//<![CDATA[\n$ISLAND_SCRIPT\n//]]></script>"
            else "<script>$ISLAND_SCRIPT</script>"
            val at = HEAD.find(text)?.range?.last?.plus(1)
                ?: ROOT.find(text)?.range?.last?.plus(1)
                ?: 0
            return (text.substring(0, at) + script + text.substring(at)).encodeToByteArray()
        }

        private val HEAD = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        private val ROOT = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

        /** The zip path that the URL path [encoded] names: percent escapes decoded, with no leading slash. */
        fun pathOf(encoded: String): String = percentDecode(encoded.substringBefore('?').substringBefore('#')).trimStart('/')

        /** [path], a zip path, as a URL path, each segment percent-encoded where a URL needs it. */
        fun urlPathOf(path: String): String = path.split('/').joinToString("/") { segment ->
            buildString {
                for (b in segment.encodeToByteArray()) {
                    val c = b.toInt() and 0xFF
                    if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~!$&'()*+,;=:@") append(c.toChar())
                    else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.add(v.toByte()); i += 3; continue }
                }
                for (b in c.toString().encodeToByteArray()) out.add(b)
                i++
            }
            return out.toByteArray().decodeToString()
        }

        /**
         * Keeps a page to the book: scripts, styles, fonts, pictures, media, frames and requests
         * of its own origin, and of `data:` and `blob:` URLs, and nothing from elsewhere.
         */
        const val CONTENT_SECURITY_POLICY: String =
            "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; form-action 'none'; base-uri 'self'"

        val BOOK_HEADERS: Map<String, String> = mapOf(
            "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}

/**
 * The script at the top of each document an island shows. A tap on a link of the document, or of
 * a frame inside it, goes to the host instead of replacing the island, except for a link to a
 * place in the same document, which scrolls there. A host bridge takes the link where the
 * platform gives one: `window.kitepdfHost.link(href)` on the desktop, or a message to the parent
 * window in a browser. Elsewhere the script follows the link, and the platform's web view hands
 * the navigation to the host. A page that handles a click itself, and cancels it, keeps it.
 */
internal const val ISLAND_SCRIPT: String = """(function () {
  if (window.kitepdf) return;
  var k = window.kitepdf = {};
  k.link = function (href) {
    try {
      href = String(href);
      if (window.kitepdfHost) { window.kitepdfHost.link(href); return; }
      if (window.parent !== window) { window.parent.postMessage({ kitepdf: 'link', href: href }, '*'); return; }
      window.location.href = href;
    } catch (e) {}
  };
  window.addEventListener('message', function (e) {
    var d = e.data;
    if (d && d.kitepdf === 'link' && typeof d.href === 'string') k.link(d.href);
  });
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0) return;
    var n = e.target;
    while (n && n.nodeType === 1) {
      var name = n.localName;
      if ((name === 'a' || name === 'area') && n.getAttribute('href') !== null) break;
      n = n.parentNode;
    }
    if (!n || n.nodeType !== 1) return;
    var href = n.href && n.href.baseVal !== undefined ? n.href.baseVal : n.href;
    if (!href || String(href).indexOf('javascript:') === 0) return;
    var to = String(new URL(href, document.baseURI));
    var here = String(location.href).split('#')[0];
    if (to.indexOf('#') >= 0 && to.split('#')[0] === here) return;
    e.preventDefault();
    k.link(to);
  }, false);
})();"""

/**
 * The URLs of a book's files under [base], an origin of the book's own followed by a path, with
 * a trailing slash: what a web view asks for, and what a link it opens names.
 */
internal class BookUrls(val base: String) {
    init {
        require(base.endsWith('/')) { "a base ends with a slash: $base" }
    }

    /** The URL of [path], a zip path, with its fragment kept. */
    fun urlOf(path: String): String {
        val fragment = path.substringAfter('#', "")
        return base + BookFiles.urlPathOf(path.substringBefore('#')) + if (fragment.isEmpty()) "" else "#$fragment"
    }

    /** The zip path, with its fragment, that [url] names, or null for a URL outside the book. */
    fun hrefOf(url: String): String? {
        if (!url.startsWith(base)) return null
        val rest = url.removePrefix(base)
        val fragment = rest.substringAfter('#', "")
        return BookFiles.pathOf(rest) + if (fragment.isEmpty()) "" else "#$fragment"
    }

    /** True when [url] and [other] name the same document, whatever their fragments. */
    fun sameDocument(url: String, other: String): Boolean = url.substringBefore('#') == other.substringBefore('#')
}

/** A token of [bytes] random bytes in hex, for an origin or a path no one else can guess. */
internal fun randomToken(bytes: Int = 16): String =
    kotlin.random.Random.nextBytes(bytes).joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
