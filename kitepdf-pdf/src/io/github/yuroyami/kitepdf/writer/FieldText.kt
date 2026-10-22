package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.PdfString

/** A run of a text field's value in one style: a standard font, its size, a colour operator, and an underline (#204). */
internal data class FieldRun(
    val text: String,
    val font: StandardFont,
    val size: Double,
    val color: String,
    val underline: Boolean = false,
) {
    val width: Double get() = font.stringWidth(text, size)
}

/** One paragraph of a text field's value: its runs, and its alignment, 0 left, 1 centre, 2 right. */
internal class FieldParagraph(val runs: List<FieldRun>, val align: Int)

/**
 * Lays a text field's value out and writes it as content (ISO 32000-1, 12.7.3.3). A multi-line
 * field wraps its paragraphs at its width and starts at its top. A single-line field draws one
 * line, in its middle. Each line aligns on its own.
 */
internal object FieldText {

    /** One line: its runs, their width, and the size of its largest text. */
    class Line(val runs: List<FieldRun>, val width: Double, val size: Double, val align: Int)

    /** The lines of [paragraphs] in [room] points, wrapped at spaces when [wrap], else all on one line. */
    fun lines(paragraphs: List<FieldParagraph>, room: Double, wrap: Boolean): List<Line> {
        if (!wrap) {
            val runs = ArrayList<FieldRun>()
            for ((i, p) in paragraphs.withIndex()) {
                if (i > 0 && runs.isNotEmpty()) runs += runs.last().copy(text = " ", underline = false)
                runs += p.runs.filter { it.text.isNotEmpty() }
            }
            return listOf(line(runs, paragraphs.firstOrNull()?.align ?: 0))
        }
        val out = ArrayList<Line>()
        for (p in paragraphs) {
            // A word may change style inside it, so it is the run pieces up to the next space.
            val words = ArrayList<List<FieldRun>>()
            var word = ArrayList<FieldRun>()
            for (run in p.runs) {
                var start = 0
                for (i in run.text.indices) if (run.text[i] == ' ') {
                    word += run.copy(text = run.text.substring(start, i + 1))
                    words += word
                    word = ArrayList()
                    start = i + 1
                }
                if (start < run.text.length) word += run.copy(text = run.text.substring(start))
            }
            if (word.isNotEmpty()) words += word
            var current = ArrayList<FieldRun>()
            var used = 0.0
            for (w in words) {
                val width = w.sumOf { it.width }
                // The space a word ends with may hang past the edge.
                val fitted = width - (w.lastOrNull()?.takeIf { it.text.endsWith(' ') }?.font?.stringWidth(" ", w.last().size) ?: 0.0)
                if (current.isNotEmpty() && used + fitted > room) {
                    out += line(current, p.align)
                    current = ArrayList()
                    used = 0.0
                }
                current.addAll(w)
                used += width
            }
            out += line(current, p.align, size = p.runs.maxOfOrNull { it.size })
        }
        return out
    }

    private fun line(runs: List<FieldRun>, align: Int, size: Double? = null): Line {
        // The spaces a line ends with take no room when it is aligned.
        val trimmed = runs.toMutableList()
        while (trimmed.isNotEmpty() && trimmed.last().text.isBlank()) trimmed.removeAt(trimmed.lastIndex)
        if (trimmed.isNotEmpty()) trimmed[trimmed.lastIndex] = trimmed.last().let { it.copy(text = it.text.trimEnd()) }
        return Line(trimmed, trimmed.sumOf { it.width }, trimmed.maxOfOrNull { it.size } ?: size ?: 0.0, align)
    }

    /**
     * Writes [lines] in a box [width] by [height], [pad] in from each edge, into [out]. [nameOf]
     * gives the resource name of each font the lines use.
     */
    fun write(
        lines: List<Line>, width: Double, height: Double, pad: Double, multiline: Boolean,
        out: ByteArrayBuilder, nameOf: (StandardFont) -> String,
    ) {
        if (lines.none { it.runs.isNotEmpty() }) return
        val room = (width - 2 * pad).coerceAtLeast(0.0)
        val underlines = ArrayList<String>()
        out.ascii("BT\n")
        var top = height - pad
        for ((index, line) in lines.withIndex()) {
            val size = line.size.takeIf { it > 0.0 } ?: continue
            // One line sits in the middle of a single-line field; a multi-line field fills from its top.
            val baseline = if (!multiline) ((height - size) / 2.0 + size * 0.2).coerceAtLeast(pad) else top - size * ASCENT
            if (multiline && index > 0 && baseline < 0.0) break
            val x = pad + when (line.align) {
                1 -> ((room - line.width) / 2.0).coerceAtLeast(0.0)
                2 -> (room - line.width).coerceAtLeast(0.0)
                else -> 0.0
            }
            out.ascii("1 0 0 1 ${fmt(x)} ${fmt(baseline)} Tm\n")
            var at = x
            for (run in line.runs) {
                if (run.text.isEmpty()) continue
                out.ascii("/${nameOf(run.font)} ${fmt(run.size)} Tf\n${run.color}\n")
                PdfObjectWriter.writeObject(PdfString(PdfText.encodeContentString(run.text)), out)
                out.ascii(" Tj\n")
                if (run.underline) {
                    val y = baseline - run.size * 0.1
                    underlines += "${stroking(run.color)} ${fmt(run.size * 0.05)} w ${fmt(at)} ${fmt(y)} m ${fmt(at + run.width)} ${fmt(y)} l S"
                }
                at += run.width
            }
            top -= size * LEADING
        }
        out.ascii("ET\n")
        for (u in underlines) out.ascii("$u\n")
    }

    /** The stroking form of a filling colour operator, so a line draws in the text's colour. */
    private fun stroking(fill: String): String = when {
        fill.endsWith(" rg") -> fill.dropLast(3) + " RG"
        fill.endsWith(" g") -> fill.dropLast(2) + " G"
        fill.endsWith(" k") -> fill.dropLast(2) + " K"
        else -> fill
    }

    /** The conventional resource name of each standard font, as form producers write them. */
    fun conventionalName(font: StandardFont): String = when (font) {
        StandardFont.Helvetica -> "Helv"
        StandardFont.HelveticaBold -> "HeBo"
        StandardFont.HelveticaOblique -> "HeOb"
        StandardFont.HelveticaBoldOblique -> "HeBO"
        StandardFont.TimesRoman -> "TiRo"
        StandardFont.TimesBold -> "TiBo"
        StandardFont.TimesItalic -> "TiIt"
        StandardFont.TimesBoldItalic -> "TiBI"
        StandardFont.Courier -> "Cour"
        StandardFont.CourierBold -> "CoBo"
        StandardFont.CourierOblique -> "CoOb"
        StandardFont.CourierBoldOblique -> "CoBO"
        StandardFont.Symbol -> "Symb"
        StandardFont.ZapfDingbats -> "ZaDb"
    }

    /** How far a line's text reaches above its baseline, and the distance between two baselines, in em. */
    private const val ASCENT = 0.8
    private const val LEADING = 1.15

    private fun fmt(d: Double): String = PdfObjectWriter.formatReal(d)

    private fun ByteArrayBuilder.ascii(s: String) = append(s.encodeToByteArray())
}
