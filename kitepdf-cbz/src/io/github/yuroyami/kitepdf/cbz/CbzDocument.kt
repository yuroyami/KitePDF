package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteByteSource
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteMetadata
import io.github.yuroyami.kitepdf.core.KiteOutlineItem
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteImageIdentity
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/**
 * Entry extensions that count as comic pages: what the image decoders read, TIFF, AVIF and JPEG XL
 * included (#755). `KiteDoc.looksLikeCbz` keeps the same set.
 */
internal val CBZ_IMAGE_EXTENSIONS: Set<String> =
    setOf("jpg", "jpeg", "jfif", "png", "gif", "bmp", "webp", "tif", "tiff", "avif", "jxl")

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

    /** The comic's decoded scans, kept for a page drawn again (#386). */
    private val decoded = CbzImageCache(DECODED_IMAGE_BYTES)

    /** Lightweight source keys outlive decoded scans without retaining the archive (#371). */
    private val imageIdentities = KiteImageIdentity()

    override val pages: List<KitePage> = entryNames.map { name ->
        CbzPage(
            { zip.read(name) }, name, readHeader = { zip.readPrefix(name, HEADER_BYTES) },
            decoded = decoded, imageIdentity = imageIdentities.child(name),
        )
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
        @Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class)
        public fun open(bytes: ByteArray): CbzDocument {
            // A CBR or a CB7 is another archive format, and says so, rather than a ZIP without images (#952).
            archiveKindOf(bytes)?.let { throw KiteFormatException("$it archives are not supported: a comic opens from a ZIP archive (CBZ)") }
            val zip = runCatching { ZipReader(bytes) }.getOrNull()
                ?: throw KiteFormatException("not a readable ZIP archive (${bytes.size} bytes)")
            return open(zip)
        }

        /**
         * [open] from a [KiteByteSource], holding only the archive's directory and the pages that
         * are drawn: each image is read from [source] when its page needs it, so a comic larger
         * than the heap opens (#906). The caller keeps [source] open while it uses the document,
         * and closes it after.
         *
         * @throws KiteFormatException as [open] does.
         */
        @Throws(io.github.yuroyami.kitepdf.core.KiteFormatException::class)
        public fun open(source: KiteByteSource): CbzDocument {
            val head = ByteArray(minOf(source.size, 8L).toInt())
            var got = 0
            while (got < head.size) {
                val n = runCatching { source.read(got.toLong(), head, got, head.size - got) }.getOrDefault(-1)
                if (n <= 0) break
                got += n
            }
            archiveKindOf(head.copyOf(got))?.let { throw KiteFormatException("$it archives are not supported: a comic opens from a ZIP archive (CBZ)") }
            val zip = runCatching { ZipReader(source) }.getOrNull()
                ?: throw KiteFormatException("not a readable ZIP archive (${source.size} bytes)")
            return open(zip)
        }

        /** [open] from a [KiteByteSource], but null instead of an exception on anything unreadable. */
        public fun openOrNull(source: KiteByteSource): CbzDocument? =
            try {
                open(source)
            } catch (_: Exception) {
                null
            }

        private fun open(zip: ZipReader): CbzDocument {
            val names = zip.names
                .filter { !isJunkEntry(it) }
                .filter { it.substringAfterLast('.').lowercase() in CBZ_IMAGE_EXTENSIONS }
                .sortedWith(NaturalOrder)
            if (names.isEmpty()) throw KiteFormatException("ZIP archive holds no image entries")
            return CbzDocument(zip, names)
        }

        /**
         * "RAR" for a RAR 4 or RAR 5 archive, "7z" for a 7z archive, the containers of `.cbr` and
         * `.cb7` comics, by their signatures; null for any other bytes (#952).
         */
        public fun archiveKindOf(bytes: ByteArray): String? {
            fun starts(vararg sig: Int) = bytes.size >= sig.size && sig.indices.all { (bytes[it].toInt() and 0xFF) == sig[it] }
            return when {
                starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) || starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) -> "RAR"
                starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7z"
                else -> null
            }
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

/**
 * How much of an entry the size read inflates: every header that ImageDims reads, and the
 * metadata that a JPEG usually puts before its size. A header past it falls back to the whole entry.
 */
private const val HEADER_BYTES = 128 * 1024

/** The decoded scans a comic keeps: 64 MB, one or two large pages. */
private const val DECODED_IMAGE_BYTES = 64L * 1024 * 1024
