package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteMetadata
import io.github.yuroyami.kitepdf.core.KiteOutlineItem
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/** Entry extensions that count as comic pages. */
internal val CBZ_IMAGE_EXTENSIONS: Set<String> =
    setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

/** Directory markers, hidden files and packaging noise: never pages. */
internal fun isJunkEntry(name: String): Boolean {
    if (name.endsWith("/")) return true
    val base = name.substringAfterLast('/')
    if (base.startsWith(".")) return true
    return base.equals("Thumbs.db", ignoreCase = true) ||
        base.equals("desktop.ini", ignoreCase = true) ||
        base.equals("ComicInfo.xml", ignoreCase = true)
}

/**
 * A CBZ comic archive: a ZIP of images, one page per image, in natural
 * filename order. Open with [open]; the umbrella artifact's `KiteDoc` sniffs
 * and opens it too.
 */
public class CbzDocument private constructor(
    private val zip: ZipReader,
    internal val entryNames: List<String>,
) : KiteDocument {

    override val pages: List<KitePage> = entryNames.map { name ->
        CbzPage({ zip.read(name) }, name)
    }

    override val pageCount: Int get() = pages.size

    /** Optional ComicInfo.xml declarations, parsed without decoding page images. */
    public val comicMetadata: CbzMetadata? by lazy {
        // ComicInfo belongs at the ZIP root. A nested file may describe an
        // unrelated bundled archive and must not replace the book metadata.
        val name = zip.names.firstOrNull { it.equals("ComicInfo.xml", ignoreCase = true) }
        name?.let { runCatching { zip.read(it)?.let { bytes -> parseComicInfo(bytes, pageCount) } }.getOrNull() }
    }

    override val metadata: KiteMetadata
        get() = comicMetadata?.let {
            KiteMetadata(it.title, it.writers, it.language, it.rightToLeft)
        } ?: KiteMetadata()

    /** ComicInfo page bookmarks form the navigation list, in page order. */
    override val outline: List<KiteOutlineItem>
        get() = comicMetadata?.pages?.sortedBy { it.image }?.mapNotNull { page ->
            page.bookmark?.let { KiteOutlineItem(it, page.image, target = KiteBookmark.Page(page.image)) }
        } ?: emptyList()

    public companion object {

        /**
         * Reads [bytes] as a comic archive.
         *
         * @throws KiteFormatException when the bytes are not a readable ZIP or
         *   the archive holds no image entries.
         */
        public fun open(bytes: ByteArray): CbzDocument {
            val zip = runCatching { ZipReader(bytes) }.getOrNull()
                ?: throw KiteFormatException("not a readable ZIP archive (${bytes.size} bytes)")
            val names = zip.names
                .filter { !isJunkEntry(it) }
                .filter { it.substringAfterLast('.').lowercase() in CBZ_IMAGE_EXTENSIONS }
                .sortedWith(NaturalOrder)
            if (names.isEmpty()) throw KiteFormatException("ZIP archive holds no image entries")
            return CbzDocument(zip, names)
        }

        /** [open], but null instead of an exception on anything unreadable. */
        public fun openOrNull(bytes: ByteArray): CbzDocument? =
            try {
                open(bytes)
            } catch (_: Exception) {
                null
            }
    }
}
