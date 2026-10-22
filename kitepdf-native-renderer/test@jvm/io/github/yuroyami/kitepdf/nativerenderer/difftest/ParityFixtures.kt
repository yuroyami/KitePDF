package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.random.Random

/**
 * Pages for the PDFium parity check, one feature each, in areas where a reader must do
 * work that the page does not spell out: annotations and form fields without an
 * appearance stream, colour spaces other than the device ones, text modes, page
 * geometry, optional content and damaged files. Each is written by hand, so the bytes
 * say exactly what the feature needs.
 */
object ParityFixtures {

    fun all(): List<SyntheticPdfs.Fixture> =
        annotations() + icons() + forms() + colourSpaces() + groups() + text() + geometry() + damaged() + written()

    private const val HELV = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

    private fun annotations(): List<SyntheticPdfs.Fixture> {
        val words = "BT /F1 18 Tf 40 200 Td (Marked words on a line) Tj ET"
        val fonts = "/Font << /F1 $HELV >>"
        fun annot(name: String, dict: String, content: String = "", resources: String = "") =
            fixture("parity-annot-$name", onePage(content, resources, annots = listOf("<< /Type /Annot $dict /F 4 >>")))
        return listOf(
            annot("square", "/Subtype /Square /Rect [40 40 260 160] /C [1 0 0] /IC [0 0 1] /BS << /W 4 >>"),
            annot("circle", "/Subtype /Circle /Rect [60 60 240 240] /C [0 0.6 0] /BS << /W 3 >>"),
            annot("line", "/Subtype /Line /Rect [20 20 280 280] /L [30 30 270 270] /C [0 0 1] /BS << /W 3 >>"),
            annot("ink", "/Subtype /Ink /Rect [30 30 270 270] /InkList [[40 40 80 150 140 60 200 220 260 120]] /C [1 0 0] /BS << /W 3 >>"),
            annot("highlight", "/Subtype /Highlight /Rect [36 192 250 222] /QuadPoints [38 220 248 220 38 194 248 194] /C [1 1 0]", words, fonts),
            annot("underline", "/Subtype /Underline /Rect [36 192 250 222] /QuadPoints [38 220 248 220 38 194 248 194] /C [0 0 1]", words, fonts),
            annot("strikeout", "/Subtype /StrikeOut /Rect [36 192 250 222] /QuadPoints [38 220 248 220 38 194 248 194] /C [1 0 0]", words, fonts),
            annot("squiggly", "/Subtype /Squiggly /Rect [36 192 250 222] /QuadPoints [38 220 248 220 38 194 248 194] /C [0 0.5 0]", words, fonts),
            annot("polygon", "/Subtype /Polygon /Rect [30 30 270 270] /Vertices [50 50 250 80 200 250 70 200] /C [0.5 0 0.5] /IC [1 0.8 0.2] /BS << /W 2 >>"),
            annot("polyline", "/Subtype /PolyLine /Rect [30 30 270 270] /Vertices [50 50 250 80 200 250 70 200] /C [0 0.4 0.8] /BS << /W 3 >>"),
            annot("freetext", "/Subtype /FreeText /Rect [30 120 270 180] /Contents (Free text note) /DA (/Helv 16 Tf 0 0 1 rg)"),
            fixture("parity-annot-square-with-appearance", onePageWithAppearance()),
        )
    }

    /** A square annotation whose own appearance stream paints a green bar, which wins over /C. */
    private fun onePageWithAppearance(): ByteArray {
        val d = Doc()
        d.add("<< /Type /Catalog /Pages 2 0 R >>")
        d.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        d.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> /Contents 4 0 R /Annots [5 0 R] >>")
        d.stream("", "".encodeToByteArray())
        d.add("<< /Type /Annot /Subtype /Square /Rect [40 40 260 160] /C [1 0 0] /F 4 /AP << /N 6 0 R >> >>")
        d.stream("/Type /XObject /Subtype /Form /BBox [0 0 220 120]", "0 0.7 0 rg 10 10 200 100 re f".encodeToByteArray())
        return d.build(1)
    }

    /** Annotations that a reader draws as an icon when they have no appearance stream. */
    private fun icons(): List<SyntheticPdfs.Fixture> {
        val notes = listOf("Comment", "Note", "Help", "Key", "Insert", "Paragraph", "NewParagraph")
        val annots = notes.mapIndexed { i, name ->
            val x = 30 + (i % 4) * 65
            val y = 220 - (i / 4) * 80
            "<< /Type /Annot /Subtype /Text /Name /$name /Rect [$x $y ${x + 24} ${y + 24}] /C [1 0.9 0.3] /F 4 >>"
        }
        return listOf(
            fixture("parity-annot-text-icons", onePage("", annots = annots)),
            fixture(
                "parity-annot-stamp",
                onePage("", annots = listOf("<< /Type /Annot /Subtype /Stamp /Name /Approved /Rect [40 120 260 190] /C [0 0.5 0] /F 4 >>")),
            ),
            fixture(
                "parity-annot-caret-attachment",
                onePage(
                    "",
                    annots = listOf(
                        "<< /Type /Annot /Subtype /Caret /Rect [40 120 70 150] /C [0 0 1] /F 4 >>",
                        "<< /Type /Annot /Subtype /FileAttachment /Name /PushPin /Rect [150 120 170 150] /C [1 0 0] /F 4 >>",
                    ),
                ),
            ),
        )
    }

    private fun forms(): List<SyntheticPdfs.Fixture> {
        val dr = "/DR << /Font << /Helv $HELV /ZaDb << /Type /Font /Subtype /Type1 /BaseFont /ZapfDingbats >> >> >>"
        fun field(name: String, widget: String, needAppearances: Boolean = true) = fixture(
            "parity-form-$name",
            onePage(
                content = "",
                annots = listOf("<< /Type /Annot /Subtype /Widget /F 4 /P 3 0 R $widget >>"),
                acroForm = "<< /Fields [5 0 R] ${if (needAppearances) "/NeedAppearances true" else ""} $dr /DA (/Helv 0 Tf 0 g) >>",
            ),
        )
        return listOf(
            field("text", "/FT /Tx /T (name) /V (Hello form) /DA (/Helv 18 Tf 0 0 0.6 rg) /Rect [30 120 270 160] /MK << /BC [0 0 0] /BG [0.9 0.9 1] >>"),
            field("checkbox", "/FT /Btn /T (agree) /V /Yes /AS /Yes /DA (/ZaDb 0 Tf 0 g) /Rect [120 120 180 180] /MK << /CA (4) /BC [0 0 0] >>"),
            field("combo", "/FT /Ch /Ff 131072 /T (pick) /Opt [(One) (Two) (Three)] /V (Two) /DA (/Helv 16 Tf 0 g) /Rect [30 120 270 160] /MK << /BC [0 0 0] >>"),
            field("text-without-need", "/FT /Tx /T (name) /V (No appearance) /DA (/Helv 18 Tf 0 g) /Rect [30 120 270 160]", needAppearances = false),
        )
    }

    private fun colourSpaces(): List<SyntheticPdfs.Fixture> {
        fun swatches(space: String, colours: List<String>, resources: String = ""): ByteArray {
            val content = StringBuilder("/CS0 cs\n")
            for ((i, c) in colours.withIndex()) {
                content.append("$c sc ${20 + (i % 4) * 68} ${200 - (i / 4) * 90} 60 80 re f\n")
            }
            return onePage(content.toString(), "/ColorSpace << /CS0 $space >> $resources")
        }
        return listOf(
            fixture(
                "parity-cs-lab",
                swatches(
                    "[/Lab << /WhitePoint [0.9505 1 1.089] /Range [-100 100 -100 100] >>]",
                    listOf("50 60 40", "70 -40 50", "30 20 -60", "90 0 0", "60 -20 -30", "40 50 50", "80 10 80", "20 0 0"),
                ),
            ),
            fixture(
                "parity-cs-calrgb",
                swatches(
                    "[/CalRGB << /WhitePoint [0.9505 1 1.089] /Gamma [1.8 1.8 1.8] /Matrix [0.4497 0.2446 0.0252 0.3163 0.672 0.1412 0.1845 0.0833 0.9227] >>]",
                    listOf("1 0 0", "0 1 0", "0 0 1", "1 1 0", "0.5 0.5 0.5", "0.2 0.6 0.9", "0.9 0.3 0.1", "0.1 0.1 0.1"),
                ),
            ),
            fixture(
                "parity-cs-separation",
                swatches(
                    "[/Separation /Spot /DeviceCMYK << /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0.8 0.9 0] /N 1 >>]",
                    listOf("1", "0.8", "0.6", "0.4", "0.3", "0.2", "0.1", "0"),
                ),
            ),
            fixture("parity-cs-devicen", deviceN()),
            fixture(
                "parity-cs-indexed",
                swatches(
                    "[/Indexed /DeviceRGB 7 <FF0000 00FF00 0000FF FFFF00 FF00FF 00FFFF 808080 000000>]",
                    listOf("0", "1", "2", "3", "4", "5", "6", "7"),
                ),
            ),
        )
    }

    /** Transparency groups whose flags change the result: knockout, and a non-isolated blend over the page. */
    private fun groups(): List<SyntheticPdfs.Fixture> {
        fun group(name: String, groupDict: String, groupContent: String, pageContent: String, gs: String) = fixture(
            "parity-group-$name",
            onePage(
                "$pageContent /Fm0 Do",
                "/XObject << /Fm0 5 0 R >> /ExtGState << $gs >>",
                extraObjects = listOf(formXObject("/BBox [0 0 300 300] /Group $groupDict /Resources << /ExtGState << $gs >> >>", groupContent)),
            ),
        )
        return listOf(
            group(
                "knockout",
                "<< /S /Transparency /CS /DeviceRGB /I true /K true >>",
                "/GA gs 1 0 0 rg 40 40 140 140 re f 0 0 1 rg 120 120 140 140 re f",
                "",
                "/GA << /ca 0.5 >>",
            ),
            group(
                "non-isolated-multiply",
                "<< /S /Transparency /CS /DeviceRGB /I false >>",
                "/GM gs 1 1 0 rg 60 60 180 180 re f",
                "0 1 0 rg 20 20 260 260 re f",
                "/GM << /BM /Multiply >>",
            ),
            group(
                "non-isolated-multiply-alpha",
                "<< /S /Transparency /CS /DeviceRGB /I false >>",
                "/GM gs 1 1 0 rg 60 60 180 180 re f",
                "0 1 0 rg 20 20 260 260 re f /GA gs",
                "/GM << /BM /Multiply >> /GA << /ca 0.6 >>",
            ),
            group(
                "isolated-multiply",
                "<< /S /Transparency /CS /DeviceRGB /I true >>",
                "/GM gs 1 1 0 rg 60 60 180 180 re f",
                "0 1 0 rg 20 20 260 260 re f",
                "/GM << /BM /Multiply >>",
            ),
        )
    }

    /** A form XObject with the entries [dict] and the content [content], as a stream object body. */
    private fun formXObject(dict: String, content: String): String =
        "<< /Type /XObject /Subtype /Form $dict /Length ${content.length} >>\nstream\n$content\nendstream"

    /** Cyan and magenta as DeviceN, through a PostScript tint transform that is object 5. */
    private fun deviceN(): ByteArray {
        val content = StringBuilder("/CS0 cs\n")
        val colours = listOf("1 0", "0 1", "1 1", "0.5 0.5", "0.2 0.8", "0.8 0.2", "0.1 0.1", "0 0")
        for ((i, c) in colours.withIndex()) content.append("$c sc ${20 + (i % 4) * 68} ${200 - (i / 4) * 90} 60 80 re f\n")
        val d = Doc()
        d.add("<< /Type /Catalog /Pages 2 0 R >>")
        d.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        d.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /ColorSpace << /CS0 [/DeviceN [/Cyan /Magenta] /DeviceCMYK 5 0 R] >> >> /Contents 4 0 R >>")
        d.stream("", content.toString().encodeToByteArray())
        d.stream("/FunctionType 4 /Domain [0 1 0 1] /Range [0 1 0 1 0 1 0 1]", "{ 0 0 }".encodeToByteArray())
        return d.build(1)
    }

    private fun text(): List<SyntheticPdfs.Fixture> {
        val fonts = "/Font << /F1 $HELV /F2 << /Type /Font /Subtype /Type1 /BaseFont /Symbol >> /F3 << /Type /Font /Subtype /Type1 /BaseFont /ZapfDingbats >> /F4 << /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >> >>"
        val modes = StringBuilder("0 0 1 RG 1 w 1 0 0 rg\n")
        for (mode in 0..7) {
            modes.append("q BT /F4 24 Tf $mode Tr ${20 + (mode % 2) * 140} ${250 - (mode / 2) * 60} Td (Mode $mode) Tj ET ")
            // Modes 4 to 7 clip; a green bar shows the clip.
            if (mode >= 4) modes.append("0 0.6 0 rg ${20 + (mode % 2) * 140} ${245 - (mode / 2) * 60} 130 30 re f 1 0 0 rg ")
            modes.append("Q\n")
        }
        return listOf(
            fixture("parity-text-render-modes", onePage(modes.toString(), fonts)),
            fixture(
                "parity-text-spacing",
                onePage(
                    "BT /F1 16 Tf 20 250 Td 3 Tc (Character spacing) Tj 0 Tc 0 -40 Td 12 Tw (Word spacing here) Tj 0 Tw " +
                        "0 -40 Td 150 Tz (Wide scale) Tj 100 Tz 0 -40 Td 6 Ts (Rise) Tj -6 Ts ( fall) Tj 0 Ts " +
                        "0 -40 Td 22 TL (Leading one) Tj T* (Leading two) Tj ET",
                    fonts,
                ),
            ),
            fixture(
                "parity-text-symbol-dingbats",
                onePage(
                    "BT /F2 22 Tf 20 240 Td (abgdpqS\\245\\263) Tj /F3 22 Tf 0 -60 Td (\\064\\065\\154\\156\\253\\336) Tj ET",
                    fonts,
                ),
            ),
            fixture(
                "parity-text-accents",
                onePage("BT /F4 20 Tf 20 240 Td (Caf\\351 na\\357ve \\374ber \\361and\\372) Tj 0 -40 Td (\\251 \\256 \\260 \\261 \\327 \\367 \\275) Tj ET", fonts),
            ),
        )
    }

    private fun geometry(): List<SyntheticPdfs.Fixture> {
        val marks = "1 0 0 rg 0 0 60 60 re f 0 0 1 rg 240 240 60 60 re f 0 0.6 0 rg 120 0 60 30 re f"
        return listOf(
            fixture("parity-rotate-180", onePage(marks, pageExtra = "/Rotate 180")),
            fixture("parity-rotate-270-crop", onePage(marks, pageExtra = "/Rotate 270 /CropBox [20 10 280 290]")),
            fixture("parity-userunit", onePage(marks, pageExtra = "/UserUnit 1.5")),
            fixture(
                "parity-optional-content",
                onePage(
                    "/OC /Off BDC 1 0 0 rg 20 20 260 260 re f EMC /OC /On BDC 0 0 1 rg 60 60 180 180 re f EMC",
                    "/Properties << /Off 5 0 R /On 6 0 R >>",
                    catalogExtra = "/OCProperties << /OCGs [5 0 R 6 0 R] /D << /OFF [5 0 R] /ON [6 0 R] >> >>",
                    extraObjects = listOf("<< /Type /OCG /Name (Hidden) >>", "<< /Type /OCG /Name (Shown) >>"),
                ),
            ),
        )
    }

    private fun damaged(): List<SyntheticPdfs.Fixture> {
        val good = onePage("0 0 1 rg 40 40 220 220 re f 1 0 0 rg 100 100 100 100 re f")
        val text = good.decodeToString(throwOnInvalidSequence = false)
        // Every object offset in the xref is wrong by 7 bytes, so a reader has to repair.
        val shifted = Regex("(\\d{10}) 00000 n").replace(text) { m ->
            (m.groupValues[1].toLong() + 7).toString().padStart(10, '0') + " 00000 n"
        }
        // No xref table and no startxref at all: a reader has to scan for objects.
        val noXref = text.substring(0, text.indexOf("xref")) + "trailer\n<< /Root 1 0 R >>\n%%EOF\n"
        return listOf(
            fixture("parity-damaged-xref-offsets", shifted.encodeToByteArray()),
            fixture("parity-damaged-no-xref", noXref.encodeToByteArray()),
        )
    }

    /** Files that KitePDF's own writer makes: an embedded TrueType font, and AES-256 encryption. */
    private fun written(): List<SyntheticPdfs.Fixture> {
        val out = ArrayList<SyntheticPdfs.Fixture>()
        systemTrueType()?.let { bytes ->
            val font = EmbeddedFont.load(bytes)
            out += fixture(
                "parity-embedded-truetype",
                PdfBuilder().page(300.0, 300.0) {
                    text(font, 20.0, 20.0, 240.0, "Embedded TrueType")
                    text(font, 20.0, 20.0, 200.0, "Café naïve über ñandú")
                    text(font, 20.0, 20.0, 160.0, "© ® ° ± × ÷ ½ €")
                }.build(),
            )
        }
        out += fixture(
            "parity-encrypted-aes256",
            PdfBuilder().encrypt(userPassword = "", ownerPassword = "owner", random = Random(7)).page(300.0, 300.0) {
                setFillRgb(0.1, 0.4, 0.8)
                rectangle(30.0, 30.0, 240.0, 120.0)
                fill()
                text(StandardFont.Helvetica, 20.0, 30.0, 200.0, "Encrypted with AES-256")
            }.build(),
        )
        out += fixture("parity-xref-stream", xrefStream(onePage("0 0.5 0 rg 30 30 240 240 re f 1 1 1 rg 80 80 140 140 re f")))
        return out
    }

    /** A TrueType font of the host: DejaVu Sans on Linux, Arial on macOS, or null. */
    private fun systemTrueType(): ByteArray? = listOf(
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/Library/Fonts/Arial.ttf",
    ).map(::File).firstOrNull { it.isFile }?.readBytes()

    /** [classic] rewritten with a cross-reference stream in place of its xref table (ISO 32000-1, 7.5.8). */
    private fun xrefStream(classic: ByteArray): ByteArray {
        val text = classic.decodeToString()
        val body = text.substring(0, text.indexOf("xref\n"))
        val offsets = Regex("(\\d{10}) 00000 n").findAll(text).map { it.groupValues[1].toInt() }.toList()
        val count = offsets.size + 2
        val rows = ByteArrayOutputStream()
        fun row(type: Int, field2: Int, field3: Int) {
            rows.write(type)
            rows.write(field2 ushr 24); rows.write(field2 ushr 16); rows.write(field2 ushr 8); rows.write(field2)
            rows.write(field3)
        }
        row(0, 0, 255)
        for (o in offsets) row(1, o, 0)
        val streamOffset = body.length
        row(1, streamOffset, 0)
        val deflater = Deflater().apply { setInput(rows.toByteArray()); finish() }
        val packed = ByteArray(4096).let { buf -> buf.copyOf(deflater.deflate(buf)) }
        val out = ByteArrayOutputStream()
        out.write(body.encodeToByteArray())
        out.write(
            ("${count - 1} 0 obj\n<< /Type /XRef /Size ${count} /W [1 4 1] /Root 1 0 R /Filter /FlateDecode /Length ${packed.size} >>\nstream\n")
                .encodeToByteArray(),
        )
        out.write(packed)
        out.write("\nendstream\nendobj\nstartxref\n$streamOffset\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    private fun fixture(name: String, bytes: ByteArray) = SyntheticPdfs.Fixture(name, bytes)

    /**
     * One 300 by 300 page drawing [content] with [resources]. [annots] become objects 5,
     * 6 and on, then [extraObjects]. An [acroForm] makes object 5 its only field.
     */
    private fun onePage(
        content: String,
        resources: String = "",
        annots: List<String> = emptyList(),
        pageExtra: String = "",
        acroForm: String? = null,
        catalogExtra: String = "",
        extraObjects: List<String> = emptyList(),
    ): ByteArray {
        val d = Doc()
        val annotRefs = annots.indices.joinToString(" ") { "${5 + it} 0 R" }
        d.add("<< /Type /Catalog /Pages 2 0 R ${acroForm?.let { "/AcroForm $it" } ?: ""} $catalogExtra >>")
        d.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        d.add(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << $resources >> /Contents 4 0 R " +
                (if (annots.isNotEmpty()) "/Annots [$annotRefs] " else "") + "$pageExtra >>",
        )
        d.stream("", content.encodeToByteArray())
        for (a in annots) d.add(a)
        for (o in extraObjects) d.add(o)
        return d.build(1)
    }

    /** A classic-xref PDF writer. Object numbers follow the insertion order, from 1. */
    private class Doc {
        private val objects = mutableListOf<ByteArray>()

        fun add(body: String): Int {
            objects += body.encodeToByteArray()
            return objects.size
        }

        fun stream(dict: String, data: ByteArray): Int {
            val b = ByteArrayOutputStream()
            b.write("<< $dict /Length ${data.size} >>\nstream\n".encodeToByteArray())
            b.write(data)
            b.write("\nendstream".encodeToByteArray())
            objects += b.toByteArray()
            return objects.size
        }

        fun build(root: Int): ByteArray {
            val out = ByteArrayOutputStream()
            fun w(s: String) = out.write(s.encodeToByteArray())
            w("%PDF-1.7\n")
            val offsets = IntArray(objects.size + 1)
            for (i in objects.indices) {
                offsets[i + 1] = out.size()
                w("${i + 1} 0 obj\n")
                out.write(objects[i])
                w("\nendobj\n")
            }
            val xref = out.size()
            w("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            for (i in 1..objects.size) w("${offsets[i].toString().padStart(10, '0')} 00000 n \n")
            w("trailer\n<< /Size ${objects.size + 1} /Root $root 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }
    }
}
