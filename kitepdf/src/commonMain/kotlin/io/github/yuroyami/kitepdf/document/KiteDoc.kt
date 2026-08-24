package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.cbz.CbzDocument
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.core.zip.ZipReader

/** A document format KitePDF can read. */
public enum class KiteDocFormat {
    Pdf,
    Epub,
    Cbz,
}

/**
 * Opens a document without being told which format it is.
 *
 * [PdfDocument.open] and [EpubDocument.open] are still there and still the
 * direct route when you know what you have. This is for the case where you
 * don't: a file picker, a download, a folder scan. It reads the format out of
 * the bytes and hands back a [KiteDocument], which is all the Compose viewer
 * and the shared search / selection / outline APIs ever need.
 *
 * ```kotlin
 * val doc = KiteDoc.open(bytes)          // PdfDocument or EpubDocument
 * KiteDocView(doc, Modifier.fillMaxSize())
 * ```
 *
 * Lives in the `kitepdf` umbrella artifact, the only one that sees both
 * handlers. Depending on a single handler still gets you that handler's own
 * entry points.
 */
public object KiteDoc {

    /**
     * The format [bytes] are in, or null when they are none of them.
     *
     * PDF is a `%PDF-` marker in the first kilobyte (leading junk is allowed,
     * as it is in real files). EPUB is a ZIP whose first entry is the OCF
     * `mimetype`, falling back to looking for `META-INF/container.xml` in the
     * central directory for books that got the first entry wrong. CBZ is any
     * other ZIP whose real entries are all images (packaging noise like
     * `ComicInfo.xml` and `Thumbs.db` is ignored), so a photo backup with a
     * readme inside stays unrecognized.
     *
     * PDF and EPUB read only the header; the CBZ check reads the ZIP central
     * directory. Still cheap enough to run over a folder.
     */
    public fun formatOf(bytes: ByteArray): KiteDocFormat? = when {
        looksLikeZip(bytes) -> when {
            looksLikeEpub(bytes) -> KiteDocFormat.Epub
            looksLikeCbz(bytes) -> KiteDocFormat.Cbz
            else -> null
        }
        findPdfHeader(bytes) -> KiteDocFormat.Pdf
        else -> null
    }

    /**
     * Reads [bytes] as whichever format they are.
     *
     * @param password for an encrypted PDF; ignored for EPUB and CBZ.
     * @param epubSettings page size, font size and margins for an EPUB;
     *   ignored for PDF and CBZ. Re-flow later with
     *   [EpubDocument.withSettings] instead of re-opening.
     * @throws KiteFormatException when the bytes are no known format, or are
     *   a known format but unreadable. Unrecognised bytes get one last try as
     *   a damaged PDF first, because the PDF reader can rebuild a file whose
     *   header was lost.
     * @throws io.github.yuroyami.kitepdf.core.KiteWrongPasswordException when
     *   a PDF is encrypted and [password] does not authenticate.
     */
    public fun open(
        bytes: ByteArray,
        password: String = "",
        epubSettings: EpubSettings = EpubSettings(),
    ): KiteDocument = when (formatOf(bytes)) {
        KiteDocFormat.Pdf -> PdfDocument.open(bytes, password)
        KiteDocFormat.Epub -> EpubDocument.open(bytes, epubSettings)
        KiteDocFormat.Cbz -> CbzDocument.open(bytes)
        null -> PdfDocument.openOrNull(bytes, password.encodeToByteArray())
            ?: throw KiteFormatException(
                "not a readable PDF, EPUB or CBZ (${bytes.size} bytes, starting ${headerPreview(bytes)})"
            )
    }

    /** [open], but null instead of an exception on anything unreadable. */
    public fun openOrNull(
        bytes: ByteArray,
        password: String = "",
        epubSettings: EpubSettings = EpubSettings(),
    ): KiteDocument? = try {
        open(bytes, password, epubSettings)
    } catch (_: Exception) {
        null
    }

    /**
     * [open] over Base64 text.
     *
     * Takes a bare Base64 payload or a whole `data:` URI
     * (`data:application/pdf;base64,JVBERi0...`), in the standard or the
     * URL-safe alphabet, with or without padding, and ignores any line breaks
     * inside it. That covers what a JSON API, an `<embed>` tag or a clipboard
     * paste will hand you.
     *
     * @throws KiteFormatException when the text is not valid Base64, or
     *   decodes to something that is neither format.
     */
    public fun openBase64(
        text: String,
        password: String = "",
        epubSettings: EpubSettings = EpubSettings(),
    ): KiteDocument = open(decodeBase64(text), password, epubSettings)

    /** [openBase64], but null instead of an exception. */
    public fun openBase64OrNull(
        text: String,
        password: String = "",
        epubSettings: EpubSettings = EpubSettings(),
    ): KiteDocument? = try {
        openBase64(text, password, epubSettings)
    } catch (_: Exception) {
        null
    }

    /* ── sniffing ─────────────────────────────────────────────────────────── */

    private const val PDF_SCAN_WINDOW = 1024

    private fun findPdfHeader(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size - 5, PDF_SCAN_WINDOW)
        var i = 0
        while (i <= limit) {
            if (bytes[i] == '%'.code.toByte() &&
                bytes[i + 1] == 'P'.code.toByte() &&
                bytes[i + 2] == 'D'.code.toByte() &&
                bytes[i + 3] == 'F'.code.toByte() &&
                bytes[i + 4] == '-'.code.toByte()
            ) return true
            i++
        }
        return false
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private const val EPUB_MIMETYPE = "application/epub+zip"

    private fun looksLikeEpub(bytes: ByteArray): Boolean {
        // Conformant OCF: "mimetype" is the first entry, stored, so its content
        // sits at a fixed offset behind the local file header. Cheapest check.
        if (readAscii(bytes, 30, 8) == "mimetype") {
            val nameLen = readU16(bytes, 26)
            val extraLen = readU16(bytes, 28)
            if (readAscii(bytes, 30 + nameLen + extraLen, EPUB_MIMETYPE.length) == EPUB_MIMETYPE) return true
        }
        // Books that ordered the archive wrong still have to carry the OCF
        // container, so fall back to the central directory.
        return runCatching {
            val zip = ZipReader(bytes)
            "META-INF/container.xml" in zip.names &&
                (zip.readText("mimetype")?.trim() == EPUB_MIMETYPE || zip.names.any { it.endsWith(".opf") })
        }.getOrDefault(false)
    }

    private val cbzImageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    /** Every real entry is an image: the strict CBZ definition, so a photo backup stays unrecognized. */
    private fun looksLikeCbz(bytes: ByteArray): Boolean = runCatching {
        val names = ZipReader(bytes).names.filterNot { name ->
            val base = name.substringAfterLast('/')
            name.endsWith("/") || base.startsWith(".") ||
                base.equals("Thumbs.db", ignoreCase = true) ||
                base.equals("desktop.ini", ignoreCase = true) ||
                base.equals("ComicInfo.xml", ignoreCase = true)
        }
        names.isNotEmpty() && names.all { it.substringAfterLast('.').lowercase() in cbzImageExtensions }
    }.getOrDefault(false)

    private fun readU16(bytes: ByteArray, at: Int): Int =
        if (at + 1 >= bytes.size) 0
        else (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readAscii(bytes: ByteArray, at: Int, length: Int): String? {
        if (at < 0 || length < 0 || at + length > bytes.size) return null
        return buildString(length) { for (i in 0 until length) append(bytes[at + i].toInt().toChar()) }
    }

    private fun headerPreview(bytes: ByteArray): String =
        bytes.take(8).joinToString(" ") { b ->
            (b.toInt() and 0xFF).toString(16).padStart(2, '0')
        }.ifEmpty { "(empty)" }

    /* ── Base64 ───────────────────────────────────────────────────────────── */

    /**
     * Tolerant Base64: strips a `data:` prefix and any whitespace, accepts both
     * the standard (`+/`) and URL-safe (`-_`) alphabets, and does not require
     * padding. Hand-rolled rather than using `kotlin.io.encoding.Base64` so the
     * behaviour is identical on every target and needs no opt-in.
     */
    private fun decodeBase64(text: String): ByteArray {
        val payload = text.substringAfter("base64,", text)
        val out = ArrayList<Byte>(payload.length * 3 / 4 + 3)
        var acc = 0
        var bits = 0
        for (c in payload) {
            if (c == '=') break
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> throw KiteFormatException("not Base64: unexpected character '$c'")
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        if (out.isEmpty()) throw KiteFormatException("not Base64: decoded to nothing")
        return out.toByteArray()
    }
}
