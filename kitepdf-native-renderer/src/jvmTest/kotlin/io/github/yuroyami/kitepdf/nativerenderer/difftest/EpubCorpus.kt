package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.nativerenderer.AwtCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.zip.Crc32
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Shared EPUB test fixtures + AWT rasterisation for the raster and differential
 * gates. Self-contained: the epub-module's own `EpubFixtures` is `internal` to
 * that module's test source, so the harness builds its own OCF zips here.
 */
object EpubCorpus {

    /** Render an [EpubPage] to a BufferedImage (y-up user space → y-down device). */
    fun rasterize(page: EpubPage, scale: Double = 1.0, background: Color = Color.WHITE): BufferedImage {
        val w = (page.displayWidth * scale).toInt().coerceAtLeast(1)
        val h = (page.displayHeight * scale).toInt().coerceAtLeast(1)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = background; g.fillRect(0, 0, w, h)
            page.renderTo(AwtCanvas(g), KiteMatrix(scale, 0.0, 0.0, -scale, 0.0, page.displayHeight * scale))
        } finally {
            g.dispose()
        }
        return img
    }

    /** Named synthetic books covering the common surface. */
    fun synthetic(): List<Pair<String, ByteArray>> = listOf(
        "novel" to epub("<h1>Chapter One</h1><p>" + "The quick brown fox jumps over the lazy dog. ".repeat(40) + "</p>"),
        "styled" to epub("<style>p{color:#204080;text-align:justify}h2{color:#800000}</style><h2>A Heading</h2><p>" + "lorem ipsum dolor sit amet consectetur ".repeat(30) + "</p>"),
        "boxed" to epub("""<div style="background-color:#eeeeff;border:2px solid #444466;padding:8px"><p>a callout with background and border</p></div><ul><li>first item</li><li>second item</li></ul>"""),
        "table" to epub("<table><tr><td>Alpha</td><td>Beta</td></tr><tr><td>Gamma</td><td>Delta</td></tr></table>"),
        "image" to epub("<p>a figure follows:</p><img src=\"pic.png\"/>", listOf("OEBPS/pic.png" to redPng())),
        "inline-decoration" to epub("""
            <p><a href="chapter1.xhtml">A linked phrase</a>, <ins>an insertion</ins>, and <del>a deletion</del>.</p>
            <p><span style="text-decoration:underline line-through">Both lines <span style="text-decoration:none">continue here</span></span>.</p>
        """.trimIndent()),
        "inline-background" to epub("""
            <p>Plain text, <mark>a highlighted <em>nested phrase</em></mark>, and plain text again.</p>
            <p><span style="background-color:#99ddff">A blue span</span><mark>next to a yellow span</mark>.</p>
            <p style="width:150pt"><mark>A highlight that wraps across several lines, including the spaces between its words.</mark></p>
        """.trimIndent()),
        "vertical-images" to epub("""
            <style>html{writing-mode:vertical-rl}body,p{margin:0;padding:0;text-indent:0}</style>
            <p><img src="quadrants.png" style="width:80pt;height:40pt"/><img src="quadrants.png" style="width:40pt;height:80pt"/></p>
            <img src="quadrants.png" style="display:block;width:100pt;height:50pt"/>
        """.trimIndent(), listOf("OEBPS/quadrants.png" to quadrantPng())),
        "vertical-cover" to epub("""
            <style>html{writing-mode:vertical-lr}body,p{margin:0;padding:0;text-indent:0}</style>
            <p><img src="bands.png" style="width:60pt;height:60pt;object-fit:cover"/></p>
            <img src="bands.png" style="display:block;width:50pt;height:80pt;object-fit:cover"/>
        """.trimIndent(), listOf("OEBPS/bands.png" to bandPng())),
    )

    /** Non-square orientation fixture: red/green above blue/white. */
    fun quadrantPng(): ByteArray = rgbPng(40, 20) { x, y ->
        when {
            y < 10 && x < 20 -> 0xff0000
            y < 10 -> 0x00ff00
            x < 20 -> 0x0000ff
            else -> 0xffffff
        }
    }

    /** Three equal bands; a centred square cover crop keeps only the green band. */
    fun bandPng(tall: Boolean = false): ByteArray = rgbPng(if (tall) 30 else 90, if (tall) 90 else 30) { x, y ->
        when ((if (tall) y else x) / 30) { 0 -> 0xff0000; 1 -> 0x00ff00; else -> 0x0000ff }
    }

    /** Deterministic RGB PNG with one stored-deflate block, valid CRCs and Adler-32. */
    private fun rgbPng(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.encodeToByteArray() + data
            return be32(data.size) + body + be32(Crc32.of(body).toInt())
        }
        val scan = ByteArray(height * (width * 3 + 1))
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixel(x, y)
            val i = y * (width * 3 + 1) + 1 + x * 3
            scan[i] = (color ushr 16).toByte(); scan[i + 1] = (color ushr 8).toByte(); scan[i + 2] = color.toByte()
        }
        require(scan.size < 65536)
        var a = 1; var b = 0
        for (v in scan) { a = (a + (v.toInt() and 255)) % 65521; b = (b + a) % 65521 }
        val nlen = scan.size.inv()
        val zlib = byteArrayOf(0x78, 1, 1, scan.size.toByte(), (scan.size ushr 8).toByte(), nlen.toByte(), (nlen ushr 8).toByte()) +
            scan + be32((b shl 16) or a)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) +
            chunk("IHDR", be32(width) + be32(height) + byteArrayOf(8, 2, 0, 0, 0)) +
            chunk("IDAT", zlib) + chunk("IEND", byteArrayOf())
    }

    fun epub(bodyHtml: String, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val body = if (bodyHtml.trimStart().startsWith("<body")) bodyHtml else "<body>$bodyHtml</body>"
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">$body</html>"""
        return storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ) + extra,
        )
    }

    /** 2x2 solid-red truecolor PNG (STORED deflate, valid CRCs and Adler-32). */
    fun redPng(): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.encodeToByteArray() + data
            var crc = -1
            for (byte in body) {
                crc = crc xor (byte.toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 != 0) 0xEDB88320.toInt() xor (crc ushr 1) else crc ushr 1
                }
            }
            return be32(data.size) + body + be32(crc.inv())
        }
        fun adler32(data: ByteArray): ByteArray {
            var a = 1
            var b = 0
            for (byte in data) {
                a = (a + (byte.toInt() and 0xFF)) % 65521
                b = (b + a) % 65521
            }
            return be32((b shl 16) or a)
        }
        val row = byteArrayOf(0, 255.toByte(), 0, 0, 255.toByte(), 0, 0)
        val scan = row + row
        val nlen = scan.size.inv() and 0xFFFF
        val zlib = byteArrayOf(
            0x78, 0x01, 0x01,
            (scan.size and 0xFF).toByte(), ((scan.size ushr 8) and 0xFF).toByte(),
            (nlen and 0xFF).toByte(), ((nlen ushr 8) and 0xFF).toByte(),
        ) + scan + adler32(scan)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return sig + chunk("IHDR", be32(2) + be32(2) + byteArrayOf(8, 2, 0, 0, 0)) + chunk("IDAT", zlib) + chunk("IEND", ByteArray(0))
    }

    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v ushr 8) and 0xFF).toByte()) }
        fun u32(v: Long) { var s = 0; while (s < 32) { out.add(((v ushr s) and 0xFF).toByte()); s += 8 } }
        fun raw(b: ByteArray) { for (x in b) out.add(x) }
        data class Cd(val name: ByteArray, val offset: Int, val size: Int)
        val cds = ArrayList<Cd>()
        for ((name, data) in entries) {
            val nb = name.encodeToByteArray(); val offset = out.size
            u32(0x04034b50L); u16(20); u16(0); u16(0); u16(0); u16(0)
            u32(0L); u32(data.size.toLong()); u32(data.size.toLong()); u16(nb.size); u16(0)
            raw(nb); raw(data); cds.add(Cd(nb, offset, data.size))
        }
        val cdStart = out.size
        for (cd in cds) {
            u32(0x02014b50L); u16(20); u16(20); u16(0); u16(0); u16(0); u16(0); u32(0L)
            u32(cd.size.toLong()); u32(cd.size.toLong()); u16(cd.name.size); u16(0); u16(0); u16(0); u16(0); u32(0L)
            u32(cd.offset.toLong()); raw(cd.name)
        }
        val cdSize = out.size - cdStart
        u32(0x06054b50L); u16(0); u16(0); u16(cds.size); u16(cds.size); u32(cdSize.toLong()); u32(cdStart.toLong()); u16(0)
        return out.toByteArray()
    }
}
