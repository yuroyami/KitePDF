package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.script.WhatwgMimeType
import io.github.yuroyami.kitepdf.epub.script.WhatwgUrl
import kotlin.random.Random

/** True for a `blob:` URL, whatever the case of its scheme (#533). */
internal fun isBlobUrl(href: String): Boolean = href.regionMatches(0, "blob:", 0, 5, ignoreCase = true)

/**
 * The blob URL store of a book (#533): the blobs its scripts named with `URL.createObjectURL`,
 * by URL, for the reader's own loading, so an image, a style sheet or a font at a blob URL loads
 * as a file of the book does. Every document over the book shares it.
 *
 * An entry lives until the script revokes it or its chapter's engine closes, as a browser
 * revokes a document's blob URLs when it unloads. What a chapter's tree names when its scripts'
 * changes land stays with that tree, as a browser keeps an image it loaded in the element: a
 * chapter laid out again, at another font size or by another document, still finds the bytes
 * after the script revoked the URL, and so does a script that revokes a URL right after it set
 * it, as many do. The store is read from layout threads and written on the script thread, so it
 * swaps whole maps.
 */
internal class BlobUrlStore {

    class Entry(val bytes: ByteArray, val type: String, val chapter: Int) {
        /** The type without its parameters, in lower case, or null when it is no MIME type. */
        val essence: String? by lazy { WhatwgMimeType.parse(type)?.essence }
    }

    private val lock = KiteLock()

    @kotlin.concurrent.Volatile
    private var live: Map<String, Entry> = emptyMap()

    /** Entries revoked since the last [settle], which the change that names them may still need. */
    @kotlin.concurrent.Volatile
    private var revoked: Map<String, Entry> = emptyMap()

    /** Per chapter, the entries its tree named when it last landed. */
    @kotlin.concurrent.Volatile
    private var pinned: Map<Int, Map<String, Entry>> = emptyMap()

    /** Puts a new entry for [bytes] of [type] in the store, made by a script of [chapter], and answers its URL. */
    fun create(origin: String, chapter: Int, bytes: ByteArray, type: String): String {
        val url = "blob:$origin/" + uuid()
        lock.withLock { live = live + (url to Entry(bytes, type, chapter)) }
        return url
    }

    /** Takes the entry of [url] out of the store, as `URL.revokeObjectURL` does. */
    fun revoke(url: String) {
        val key = keyOf(url) ?: return
        lock.withLock {
            val entry = live[key] ?: return
            live = live - key
            revoked = revoked + (key to entry)
        }
    }

    /** Whether the store holds an entry for [url] that no script revoked: what the URL parser resolves. */
    fun isLive(url: String): Boolean = keyOf(url)?.let { it in live } == true

    /** The entry that [url] names for the reader's loading, or null. */
    fun resolve(url: String): Entry? {
        if (live.isEmpty() && revoked.isEmpty() && pinned.isEmpty()) return null
        val key = keyOf(url) ?: return null
        live[key]?.let { return it }
        revoked[key]?.let { return it }
        for (entries in pinned.values) entries[key]?.let { return it }
        return null
    }

    /**
     * Ends a call into [chapter]'s scripts: when its changes landed as [tree], the entries the
     * tree names stay with the chapter, and the entries revoked during the call go.
     */
    fun settle(chapter: Int, tree: KiteXmlNode.Element?) {
        if (live.isEmpty() && revoked.isEmpty() && pinned.isEmpty()) return
        lock.withLock {
            if (tree != null) {
                val kept = HashMap<String, Entry>()
                val before = pinned[chapter].orEmpty()
                if (live.isNotEmpty() || revoked.isNotEmpty() || before.isNotEmpty()) {
                    for (url in urlsIn(tree)) {
                        val key = keyOf(url) ?: continue
                        (live[key] ?: revoked[key] ?: before[key])?.let { kept[key] = it }
                    }
                }
                pinned = if (kept.isEmpty()) pinned - chapter else pinned + (chapter to kept)
            }
            revoked = emptyMap()
        }
    }

    /** Revokes the entries that [chapter]'s scripts made, as its engine closes. */
    fun closeChapter(chapter: Int) {
        lock.withLock {
            if (live.values.any { it.chapter == chapter }) live = live.filterValues { it.chapter != chapter }
        }
    }

    private companion object {
        /** The URL without its fragment, as the store keys it, or null for no blob URL. */
        fun keyOf(url: String): String? {
            if (!isBlobUrl(url.trim())) return null
            return WhatwgUrl.parse(url)?.takeIf { it.scheme == "blob" }?.href(excludeFragment = true)
        }

        /** Each blob URL that an attribute or a text of [tree] holds: a `src`, a `srcset`, a `url()` of a style. */
        fun urlsIn(tree: KiteXmlNode.Element): List<String> {
            val out = ArrayList<String>()
            fun scan(text: String) {
                var at = text.indexOf("blob:", ignoreCase = true)
                while (at >= 0) {
                    var end = at + 5
                    while (end < text.length && text[end] !in URL_END) end++
                    out += text.substring(at, end)
                    at = text.indexOf("blob:", end, ignoreCase = true)
                }
            }
            fun walk(node: KiteXmlNode) {
                when (node) {
                    is KiteXmlNode.Element -> {
                        for (value in node.attrs.values) scan(value)
                        for (child in node.children) walk(child)
                    }
                    is KiteXmlNode.Text -> scan(node.text)
                    is KiteXmlNode.Comment -> {}
                }
            }
            walk(tree)
            return out
        }

        /** What ends a URL inside an attribute or a style: white space, a quote, a parenthesis, a comma. */
        const val URL_END = " \t\n\r\u000C\"'(),<>"

        /** A version 4 UUID, as the File API names a blob URL. */
        fun uuid(): String {
            val b = Random.nextBytes(16)
            b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
                hex.substring(16, 20) + "-" + hex.substring(20)
        }
    }
}
