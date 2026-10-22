package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.plugins.tiff.BaselineTIFFTagSet
import javax.imageio.plugins.tiff.TIFFDirectory
import javax.imageio.plugins.tiff.TIFFField
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Scanned books for the render benchmark (#462). Each page is one image of a printed page, at
 * 300 dpi unless the list says otherwise, in an encoding that scanners and OCR tools write:
 *
 * - `scan-g4`: a bilevel Letter page, CCITTFaxDecode Group 4, as most black-and-white scans are.
 * - `scan-gray-jpeg`: a greyscale A4 page, DCTDecode.
 * - `scan-gray-flate`: a greyscale Letter page, FlateDecode with PNG predictors, as img2pdf writes one.
 * - `scan-mrc`: an A4 page split as mixed raster content: a 150 dpi colour JPEG background under a
 *   Group 4 text layer. The text layer is an /ImageMask on the first and last page, and the /Mask
 *   of a small ink image on the middle page.
 * - `scan-color-jpeg`: a colour A4 page, DCTDecode, as a phone or a sheet-fed scanner makes one.
 * - `scan-g4-600`: `scan-g4` at 600 dpi, four times the pixels. It is the most expensive book.
 *
 * Every page carries an invisible OCR text layer, and the middle page of each book has a photograph,
 * which the bilevel scan halftones. The text is strokes shaped like letters, drawn from a fixed seed,
 * so the files are the same on every run and owe nothing to a real book.
 */
object ScannedPdfs {

    /** Pages of each book. The middle one has a photograph. */
    private const val PAGES = 3

    /** Every book, made once per test run: a book takes one to three seconds to make. */
    fun all(): List<SyntheticPdfs.Fixture> = books

    private val books: List<SyntheticPdfs.Fixture> by lazy {
        listOf(
            SyntheticPdfs.Fixture("scan-g4", book(LETTER, ::g4Page)),
            SyntheticPdfs.Fixture("scan-gray-jpeg", book(A4, ::grayJpegPage)),
            SyntheticPdfs.Fixture("scan-gray-flate", book(LETTER, ::grayFlatePage)),
            SyntheticPdfs.Fixture("scan-mrc", book(A4, ::mrcPage)),
            SyntheticPdfs.Fixture("scan-color-jpeg", book(A4, ::colorJpegPage)),
            SyntheticPdfs.Fixture("scan-g4-600", book(PageSize(612, 792, 600), ::g4Page)),
        )
    }

    /** A page size in points, and in pixels at the scan resolution [dpi]. */
    private class PageSize(val widthPt: Int, val heightPt: Int, val dpi: Int = 300) {
        val width = (widthPt * dpi / 72.0).roundToInt()
        val height = (heightPt * dpi / 72.0).roundToInt()
    }

    private val LETTER = PageSize(612, 792)
    private val A4 = PageSize(595, 842)

    /** One image XObject: its dictionary entries without /Length, its data, and the stencil of its /Mask. */
    private class XImage(val name: String, val dict: String, val data: ByteArray, val mask: XImage? = null)

    /** The images of a scanned page and the content that draws them. */
    private class Scan(val images: List<XImage>, val content: String)

    /** A word of the text, for the OCR layer: its letters and its baseline start in pixels. */
    private class Word(val text: String, val x: Double, val baseline: Double)

    /**
     * A printed page at the resolution of its [size]: [gray] holds 255 for paper and less where
     * there is ink or the photograph. [photo] is the photograph's box, or null.
     */
    private class Print(val index: Int, val size: PageSize, val gray: ByteArray, val photo: Rectangle?, val words: List<Word>)

    private fun book(size: PageSize, scan: (Print) -> Scan): ByteArray {
        val pdf = SyntheticPdfs.Pdf()
        pdf.obj("<< /Type /Catalog /Pages 2 0 R >>")
        val pages = pdf.reserve()
        val font = pdf.obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val kids = ArrayList<Int>()
        for (index in 0 until PAGES) {
            val print = print(size, index)
            val page = scan(print)
            val names = StringBuilder()
            for (image in page.images) {
                val mask = image.mask?.let { pdf.stream(it.dict, it.data) }
                val number = pdf.stream(image.dict + (mask?.let { " /Mask $it 0 R" } ?: ""), image.data)
                names.append("/${image.name} $number 0 R ")
            }
            val content = pdf.stream("", (page.content + ocrLayer(print)).encodeToByteArray())
            kids += pdf.obj(
                "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 ${size.widthPt} ${size.heightPt}] " +
                    "/Resources << /XObject << $names>> /Font << /F1 $font 0 R >> >> /Contents $content 0 R >>",
            )
        }
        pdf.set(pages, "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count ${kids.size} >>")
        return pdf.build(1)
    }

    /** The unit square of the page, scaled to the whole page. */
    private fun fullPage(size: PageSize) = "${size.widthPt} 0 0 ${size.heightPt} 0 0 cm"

    private fun g4Page(print: Print): Scan {
        val size = print.size
        val bits = bilevel(print, text = true, photo = true, border = true)
        val dict = "/Type /XObject /Subtype /Image /Width ${size.width} /Height ${size.height} /ColorSpace /DeviceGray " +
            "/BitsPerComponent 1 /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns ${size.width} /Rows ${size.height} >>"
        return Scan(listOf(XImage("Im1", dict, ccittG4(bits, size.width, size.height))), "q ${fullPage(size)} /Im1 Do Q\n")
    }

    private fun grayJpegPage(print: Print): Scan {
        val size = print.size
        val image = grayImage(grayScan(print), size.width, size.height)
        val dict = "/Type /XObject /Subtype /Image /Width ${size.width} /Height ${size.height} /ColorSpace /DeviceGray " +
            "/BitsPerComponent 8 /Filter /DCTDecode"
        return Scan(listOf(XImage("Im1", dict, jpeg(image, 0.75f))), "q ${fullPage(size)} /Im1 Do Q\n")
    }

    private fun colorJpegPage(print: Print): Scan {
        val size = print.size
        val dict = "/Type /XObject /Subtype /Image /Width ${size.width} /Height ${size.height} /ColorSpace /DeviceRGB " +
            "/BitsPerComponent 8 /Filter /DCTDecode"
        return Scan(listOf(XImage("Im1", dict, jpeg(colorScan(print), 0.85f))), "q ${fullPage(size)} /Im1 Do Q\n")
    }

    private fun grayFlatePage(print: Print): Scan {
        val size = print.size
        val image = grayImage(grayScan(print), size.width, size.height)
        val dict = "/Type /XObject /Subtype /Image /Width ${size.width} /Height ${size.height} /ColorSpace /DeviceGray " +
            "/BitsPerComponent 8 /Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 8 /Columns ${size.width} >>"
        return Scan(listOf(XImage("Im1", dict, pngIdat(image))), "q ${fullPage(size)} /Im1 Do Q\n")
    }

    private fun mrcPage(print: Print): Scan {
        val size = print.size
        val background = background(print)
        val bg = XImage(
            "Bg",
            "/Type /XObject /Subtype /Image /Width ${background.width} /Height ${background.height} /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode",
            jpeg(background, 0.5f),
        )
        val bits = ccittG4(bilevel(print, text = true, photo = false, border = false), size.width, size.height)
        val stencil = "/Type /XObject /Subtype /Image /Width ${size.width} /Height ${size.height} /ImageMask true " +
            "/BitsPerComponent 1 /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns ${size.width} /Rows ${size.height} >>"
        val page = fullPage(size)
        if (print.index != PAGES / 2) {
            return Scan(listOf(bg, XImage("Fg", stencil, bits)), "q $page /Bg Do Q q 0.12 0.1 0.09 rg $page /Fg Do Q\n")
        }
        // The ink at an eighth of the resolution, its shape from the full-resolution stencil.
        val inkWidth = size.width / 8
        val inkHeight = size.height / 8
        val noise = Random(print.index + 7)
        val ink = ByteArray(inkWidth * inkHeight * 3) { (24 + noise.nextInt(12) + it % 3 * 3).toByte() }
        val fg = XImage(
            "Fg",
            "/Type /XObject /Subtype /Image /Width $inkWidth /Height $inkHeight /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /FlateDecode",
            deflate(ink),
            mask = XImage("", stencil, bits),
        )
        return Scan(listOf(bg, fg), "q $page /Bg Do Q q $page /Fg Do Q\n")
    }

    /** Invisible text over each word, as OCR software writes it (ISO 32000-1, 9.3.6, render mode 3). */
    private fun ocrLayer(print: Print): String {
        val out = StringBuilder("BT 3 Tr /F1 11 Tf\n")
        val scale = 72.0 / print.size.dpi
        for (word in print.words) {
            val x = word.x * scale
            val y = (print.size.height - word.baseline) * scale
            out.append(String.format(Locale.ROOT, "1 0 0 1 %.2f %.2f Tm (%s) Tj\n", x, y, word.text))
        }
        return out.append("ET\n").toString()
    }

    /* ─── The printed page ─────────────────────────────────────────────── */

    /** Sizes of one text style in pixels. */
    private class Type(sizePt: Double, dpi: Int) {
        val em = sizePt / 72 * dpi
        val xHeight = 0.46 * em
        val ascender = 0.7 * em
        val descender = 0.22 * em
        val stroke = BasicStroke((0.085 * em).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val dot = 0.13 * em
    }

    private fun print(size: PageSize, index: Int): Print {
        val w = size.width
        val h = size.height
        val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        // A scanned page is never quite straight.
        g.rotate(Math.toRadians(if (index % 2 == 0) 0.3 else -0.2), w / 2.0, h / 2.0)
        val random = Random(462 + 31 * index + w)
        val dpi = size.dpi
        val margin = dpi.toDouble()
        val left = margin
        val right = w - margin
        val body = Type(11.0, dpi)
        val leading = 13.5 / 72 * dpi
        val photo = if (index == PAGES / 2) {
            Rectangle(left.toInt(), (margin + 12 * leading).toInt(), (right - left).toInt(), ((h - 2 * margin) * 0.38).toInt())
        } else {
            null
        }
        photo?.let { drawPhoto(g, it, dpi) }
        g.color = Color.BLACK
        val words = ArrayList<Word>()
        // The running head and the page number.
        val head = Type(8.0, dpi)
        drawLine(g, random, head, w / 2.0 - 14 * head.em, margin - 0.45 * dpi, 28 * head.em, justify = false, capital = true, words = null)
        val folio = Type(9.0, dpi)
        g.stroke = folio.stroke
        drawLetter(g, 1, w / 2.0 - folio.em / 2, h - margin + 0.4 * dpi, folio.em / 2, folio)
        drawLetter(g, 0, w / 2.0, h - margin + 0.4 * dpi, folio.em / 2, folio)
        var baseline = margin + body.ascender
        var lineInParagraph = 0
        var paragraphLines = 4 + random.nextInt(9)
        while (baseline + body.descender < h - margin) {
            if (photo != null && baseline + body.descender > photo.y - leading / 2 &&
                baseline - body.ascender < photo.y + photo.height + leading
            ) {
                // The caption, a line below the photograph, then the text goes on.
                baseline = photo.y + photo.height + leading * 1.2
                val caption = Type(9.0, dpi)
                drawLine(g, random, caption, left + dpi * 0.7, baseline, right - left - dpi * 1.4, justify = false, capital = true, words = words)
                baseline += leading * 1.5
                continue
            }
            val indent = if (lineInParagraph == 0) 1.5 * body.em else 0.0
            val last = lineInParagraph == paragraphLines - 1
            val width = if (last) (right - left - indent) * (0.3 + 0.6 * random.nextDouble()) else right - left - indent
            drawLine(g, random, body, left + indent, baseline, width, justify = !last, capital = lineInParagraph == 0, words = words)
            baseline += leading
            lineInParagraph++
            if (last) {
                lineInParagraph = 0
                paragraphLines = 4 + random.nextInt(9)
            }
        }
        g.dispose()
        return Print(index, size, (image.raster.dataBuffer as DataBufferByte).data, photo, words)
    }

    /** One line of words in [type], from [x] for [width] pixels, spread to the full width when [justify]. */
    private fun drawLine(
        g: Graphics2D, random: Random, type: Type, x: Double, baseline: Double, width: Double,
        justify: Boolean, capital: Boolean, words: MutableList<Word>?,
    ) {
        val space = 0.25 * type.em
        // Each word as its letters' advances.
        val line = ArrayList<DoubleArray>()
        var used = 0.0
        while (true) {
            val letters = 1 + random.nextInt(4) + random.nextInt(4) + random.nextInt(3)
            val advances = DoubleArray(letters) { type.em * (0.42 + 0.2 * random.nextDouble()) }
            val wordWidth = advances.sum()
            if (line.isNotEmpty() && used + space + wordWidth > width) break
            if (line.isEmpty() && wordWidth > width) return
            used += (if (line.isEmpty()) 0.0 else space) + wordWidth
            line += advances
        }
        val gap = if (justify && line.size > 1) space + (width - used) / (line.size - 1) else space
        g.stroke = type.stroke
        var pen = x
        for ((i, advances) in line.withIndex()) {
            val text = StringBuilder()
            val start = pen
            for ((j, advance) in advances.withIndex()) {
                val upper = j == 0 && (capital && i == 0 || random.nextInt(20) == 0)
                val shape = if (upper) CAPITAL else random.nextInt(CAPITAL)
                drawLetter(g, shape, pen, baseline, advance, type)
                val letter = "etaoinshrdlucmfwypvbgkq"[random.nextInt(23)]
                text.append(if (upper) letter.uppercaseChar() else letter)
                pen += advance
            }
            words?.add(Word(text.toString(), start, baseline))
            pen += gap
        }
    }

    /** Shapes 0 until [CAPITAL] are lower-case letters. */
    private const val CAPITAL = 11

    private fun drawLetter(g: Graphics2D, shape: Int, x: Double, base: Double, advance: Double, t: Type) {
        val l = x + 0.14 * advance
        val r = x + advance - 0.14 * advance
        val mid = (l + r) / 2
        val top = base - t.xHeight
        val asc = base - t.ascender
        val bowl = Ellipse2D.Double(l, top, r - l, t.xHeight)
        val arch = Arc2D.Double(l, top, r - l, t.xHeight * 0.9, 0.0, 180.0, Arc2D.OPEN)
        when (shape) {
            0 -> g.draw(bowl) // o
            1 -> g.draw(Line2D.Double(mid, asc, mid, base)) // l
            2 -> { g.draw(Line2D.Double(l, top, l, base)); g.draw(arch); g.draw(Line2D.Double(r, top + t.xHeight * 0.45, r, base)) } // n
            3 -> { g.draw(bowl); g.draw(Line2D.Double(l, top + t.xHeight / 2, r, top + t.xHeight / 2)) } // e
            4 -> { g.draw(Line2D.Double(l, top, mid, base)); g.draw(Line2D.Double(mid, base, r, top)) } // v
            5 -> { g.draw(Line2D.Double(l, top, l, base + t.descender)); g.draw(bowl) } // p
            6 -> g.draw(Arc2D.Double(l, top, r - l, t.xHeight, 45.0, 270.0, Arc2D.OPEN)) // c
            7 -> { g.draw(Line2D.Double(mid, top, mid, base)); g.fill(Ellipse2D.Double(mid - t.dot / 2, asc, t.dot, t.dot)) } // i
            8 -> { g.draw(Line2D.Double(l, asc, l, base)); g.draw(arch); g.draw(Line2D.Double(r, top + t.xHeight * 0.45, r, base)) } // h
            9 -> { // s
                g.draw(Arc2D.Double(l, top, r - l, t.xHeight / 2, 0.0, 270.0, Arc2D.OPEN))
                g.draw(Arc2D.Double(l, top + t.xHeight / 2, r - l, t.xHeight / 2, 180.0, 270.0, Arc2D.OPEN))
            }
            10 -> { g.draw(Line2D.Double(mid, asc + t.dot, mid, base)); g.draw(Line2D.Double(l, top, r, top)) } // t
            else -> { // H
                g.draw(Line2D.Double(l, asc, l, base))
                g.draw(Line2D.Double(r, asc, r, base))
                g.draw(Line2D.Double(l, (asc + base) / 2, r, (asc + base) / 2))
            }
        }
    }

    /** A photograph of soft shapes, in tones from 25 to 235. */
    private fun drawPhoto(g: Graphics2D, box: Rectangle, dpi: Int) {
        val k = 300.0 / dpi
        val photo = BufferedImage(box.width, box.height, BufferedImage.TYPE_BYTE_GRAY)
        val data = (photo.raster.dataBuffer as DataBufferByte).data
        var noise = 0x2545F491
        for (y in 0 until box.height) {
            for (x in 0 until box.width) {
                noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                val v = 130 + 60 * sin(k * x * 0.011 + 0.7 * sin(k * y * 0.004)) * sin(k * y * 0.009 + 1.0) +
                    35 * sin(k * (x - y) * 0.0035) + (noise and 15) - 8
                data[y * box.width + x] = v.toInt().coerceIn(25, 235).toByte()
            }
        }
        g.drawImage(photo, box.x, box.y, null)
    }

    /* ─── Scans of the printed page ───────────────────────────────────── */

    /** A 4 by 4 ordered dither: the halftone that a bilevel scan makes of a photograph. */
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /**
     * The page as packed bits, 1 for black, the first pixel in the high bit. Ink edges are ragged
     * as a scanner's threshold makes them. With [border], the binding side has the dark edge of
     * the book and the page carries specks of dust.
     */
    private fun bilevel(print: Print, text: Boolean, photo: Boolean, border: Boolean): ByteArray {
        val w = print.size.width
        val h = print.size.height
        val rowBytes = (w + 7) / 8
        val bits = ByteArray(rowBytes * h)
        val box = print.photo?.let { Rectangle(it.x - 16, it.y - 16, it.width + 32, it.height + 32) }
        var noise = 0x1F123BB5 + print.index
        for (y in 0 until h) {
            val edge = if (border) ((24 + 10 * sin(y * 1.5 / print.size.dpi)) * print.size.dpi / 300).toInt() else 0
            for (x in 0 until w) {
                val v = print.gray[y * w + x].toInt() and 0xFF
                val inPhoto = box != null && box.contains(x, y)
                val black = when {
                    inPhoto -> photo && v < BAYER[(y and 3) * 4 + (x and 3)] * 16 + 8
                    !text -> false
                    v == 255 -> false
                    v == 0 -> true
                    else -> {
                        noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                        v + (noise and 63) - 32 < 128
                    }
                }
                val binding = if (print.index % 2 == 0) x < edge else x >= w - edge
                val i = y * rowBytes + (x ushr 3)
                if (black || binding) bits[i] = (bits[i].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
        if (border) {
            val dust = Random(print.index + 99)
            repeat(400) {
                val x = dust.nextInt(w - 3)
                val y = dust.nextInt(h - 3)
                val r = 1 + dust.nextInt(3)
                for (dy in 0 until r) for (dx in 0 until r) {
                    val i = (y + dy) * rowBytes + ((x + dx) ushr 3)
                    bits[i] = (bits[i].toInt() or (0x80 ushr ((x + dx) and 7))).toByte()
                }
            }
        }
        return bits
    }

    /** The page as a greyscale scan: off-white paper with sensor noise, and the shadow of the binding. */
    private fun grayScan(print: Print): ByteArray {
        val w = print.size.width
        val h = print.size.height
        val out = ByteArray(w * h)
        var noise = 0x6B43A9B5 + print.index
        val reach = 0.73 * print.size.dpi
        for (y in 0 until h) {
            val light = 228 + 6 * sin(y / (3.0 * print.size.dpi))
            for (x in 0 until w) {
                val fromBinding = if (print.index % 2 == 0) x else w - 1 - x
                val shadow = if (fromBinding < reach) 90.0 * (1 - fromBinding / reach) * (1 - fromBinding / reach) else 0.0
                val paper = light - shadow
                val printed = (print.gray[y * w + x].toInt() and 0xFF) / 255.0
                noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                val v = 28 + (paper - 28) * printed + (noise and 7) + (noise ushr 8 and 7) - 7
                out[y * w + x] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** The page as a colour scan: cream paper, dark brown ink and a warm photograph. */
    private fun colorScan(print: Print): BufferedImage {
        val w = print.size.width
        val h = print.size.height
        val gray = grayScan(print)
        val image = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val data = (image.raster.dataBuffer as DataBufferByte).data
        for (i in 0 until w * h) {
            val v = gray[i].toInt() and 0xFF
            data[3 * i] = (v * 0.86).toInt().toByte()
            data[3 * i + 1] = (v * 0.96).toInt().toByte()
            data[3 * i + 2] = (v * 1.03 + 4).toInt().coerceAtMost(255).toByte()
        }
        return image
    }

    /** The MRC background at half the resolution: cream paper and the photograph in colour, without the text. */
    private fun background(print: Print): BufferedImage {
        val w = print.size.width / 2
        val h = print.size.height / 2
        val image = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val data = (image.raster.dataBuffer as DataBufferByte).data
        val photo = print.photo
        var noise = 0x3C6EF372 + print.index
        for (y in 0 until h) {
            for (x in 0 until w) {
                noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                val n = (noise and 7) - 4
                val o = (y * w + x) * 3
                if (photo != null && photo.contains(2 * x, 2 * y)) {
                    val v = print.gray[2 * y * print.size.width + 2 * x].toInt() and 0xFF
                    data[o] = (v * 0.7 + n).toInt().coerceIn(0, 255).toByte()
                    data[o + 1] = (v * 0.9 + n).toInt().coerceIn(0, 255).toByte()
                    data[o + 2] = (v + n).coerceIn(0, 255).toByte()
                } else {
                    data[o] = (220 + n).toByte()
                    data[o + 1] = (234 + n).toByte()
                    data[o + 2] = (240 + n).toByte()
                }
            }
        }
        return image
    }

    private fun grayImage(samples: ByteArray, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY).also {
            samples.copyInto((it.raster.dataBuffer as DataBufferByte).data)
        }

    /* ─── Encoders ─────────────────────────────────────────────────────── */

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use {
            writer.output = it
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** The zlib data of [image] as a PNG file holds it, row filters included, as img2pdf embeds a PNG. */
    private fun pngIdat(image: BufferedImage): ByteArray {
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val out = ByteArrayOutputStream()
        var i = 8
        while (i + 8 <= png.size) {
            val length = ((png[i].toInt() and 0xFF) shl 24) or ((png[i + 1].toInt() and 0xFF) shl 16) or
                ((png[i + 2].toInt() and 0xFF) shl 8) or (png[i + 3].toInt() and 0xFF)
            if (String(png, i + 4, 4, Charsets.US_ASCII) == "IDAT") out.write(png, i + 8, length)
            i += 12 + length
        }
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /**
     * Group 4 data (ITU-T T.6) of a bilevel image whose rows [bits] pack 1 for black. The JDK's
     * TIFF writer encodes it, as one strip, so the strip is one stream from the first row to the last.
     */
    internal fun ccittG4(bits: ByteArray, width: Int, height: Int): ByteArray {
        // Index 0 is white, so the writer stores the bits as WhiteIsZero, the fax sense, and does not invert them.
        val white = 0xFF.toByte()
        val model = IndexColorModel(1, 2, byteArrayOf(white, 0), byteArrayOf(white, 0), byteArrayOf(white, 0))
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY, model)
        bits.copyInto((image.raster.dataBuffer as DataBufferByte).data)
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionType = "CCITT T.6"
        }
        val directory = TIFFDirectory.createFromMetadata(
            writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param),
        )
        directory.addTIFFField(TIFFField(BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_ROWS_PER_STRIP), height.toLong()))
        val tiff = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(tiff).use {
            writer.output = it
            writer.write(null, IIOImage(image, null, directory.asMetadata), param)
        }
        writer.dispose()
        val bytes = tiff.toByteArray()
        val reader = ImageIO.getImageReadersByFormatName("tiff").next()
        val written = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use {
            reader.input = it
            TIFFDirectory.createFromMetadata(reader.getImageMetadata(0))
        }
        reader.dispose()
        val offsets = written.getTIFFField(BaselineTIFFTagSet.TAG_STRIP_OFFSETS)
        val counts = written.getTIFFField(BaselineTIFFTagSet.TAG_STRIP_BYTE_COUNTS)
        check(offsets.count == 1) { "the TIFF writer made ${offsets.count} strips" }
        val start = offsets.getAsLong(0).toInt()
        return bytes.copyOfRange(start, start + counts.getAsLong(0).toInt())
    }
}
