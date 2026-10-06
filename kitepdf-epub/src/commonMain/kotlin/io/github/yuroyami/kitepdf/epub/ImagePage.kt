package io.github.yuroyami.kitepdf.epub

/**
 * The page of an image that the spine lists in place of a document (#614). A reading system that
 * can show the image shows it, and the fallback document is for one that cannot (EPUB 3.3,
 * manifest fallbacks). The page is an XHTML document that holds the image alone, at its
 * own size, so a fixed-layout or roll book gives it the image's aspect.
 */
internal object ImagePage {

    /** The image types the spine can show this way: the ones every target decodes. */
    val TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif")

    /**
     * The XHTML of the page for the image named [name] in its folder, [width] by [height] pixels
     * when known, with [alt] as its alternative text.
     */
    fun document(name: String, width: Int?, height: Int?, alt: String): String {
        val viewport = if (width != null && height != null) """<meta name="viewport" content="width=$width, height=$height"/>""" else ""
        return """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$viewport<title></title></head>""" +
            """<body style="margin: 0"><img src="${escape(encodePath(name))}" alt="${escape(alt)}" """ +
            """style="display: block; margin: 0 auto; max-width: 100%; max-height: 100vh"/></body></html>"""
    }

    /** The width and height in pixels that the header of a PNG, GIF or JPEG file [bytes] gives, or null. */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        val n = bytes.size
        // PNG: the signature, then the IHDR chunk with the width and the height.
        if (n >= 24 && u8(0) == 0x89 && u8(1) == 'P'.code && u8(2) == 'N'.code && u8(3) == 'G'.code) {
            return (be32(16) to be32(20)).takeIf { it.first > 0 && it.second > 0 }
        }
        // GIF: the logical screen size after the six bytes of the signature.
        if (n >= 10 && u8(0) == 'G'.code && u8(1) == 'I'.code && u8(2) == 'F'.code) {
            return (le16(6) to le16(8)).takeIf { it.first > 0 && it.second > 0 }
        }
        // JPEG: walk the markers to the first start of frame, which holds the height and the width.
        if (n >= 4 && u8(0) == 0xFF && u8(1) == 0xD8) {
            var i = 2
            while (i + 9 < n) {
                if (u8(i) != 0xFF) return null
                val marker = u8(i + 1)
                if (marker == 0xFF) { i++; continue }
                val frame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (frame) return (be16(i + 7) to be16(i + 5)).takeIf { it.first > 0 && it.second > 0 }
                if (marker == 0xD9 || marker == 0xDA) return null
                i += 2 + be16(i + 2)
            }
        }
        return null
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** [name] as a relative URL: the characters that would end or split it are percent-encoded. */
    private fun encodePath(name: String): String = buildString {
        for (c in name) when (c) {
            '%' -> append("%25")
            ' ' -> append("%20")
            '#' -> append("%23")
            '?' -> append("%3F")
            else -> append(c)
        }
    }
}
