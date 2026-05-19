package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.PdfPage
import com.yuroyami.kitepdf.content.ContentStreamParser
import com.yuroyami.kitepdf.content.Operation
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfString

/**
 * Page-level renderer. Walks the content-stream operations and translates each
 * PDF graphics operator to a sequence of [PdfCanvas] calls.
 *
 * Session-1 status: the operator dispatch is wired up for the path-construction,
 * path-painting, graphics-state, color, and text-showing operators — these
 * forward straight to [PdfCanvas]. Operators that need a real graphics-state
 * stack (`q`/`Q`, `cm`, text matrices) are forwarded but the canvas
 * implementation is responsible for the actual stack semantics.
 *
 * What's NOT here yet (Session-2):
 *   - Shadings (`sh`), patterns
 *   - XObject Form/Image (`Do` operator)
 *   - Clipping (`W`, `W*`)
 *   - Color-space switches beyond gray and RGB (`cs`, `CS`, `scn`, `SCN`)
 *   - Font glyph rendering — `showText` gets the raw bytes; the canvas
 *     impl has to map them to glyphs via font/encoding
 */
class PageRenderer(private val canvas: PdfCanvas) {

    fun render(page: PdfPage) {
        val ops = ContentStreamParser.parse(page.contentBytes)
        execute(ops)
    }

    fun execute(ops: List<Operation>) {
        for (op in ops) {
            dispatch(op)
        }
    }

    private fun dispatch(op: Operation) {
        val a = op.operands
        when (op.operator) {
            // Graphics state stack
            "q" -> canvas.saveState()
            "Q" -> canvas.restoreState()
            "cm" -> canvas.concatMatrix(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
            "w" -> canvas.setLineWidth(num(a, 0))

            // Colors
            "g" -> canvas.setFillColorGray(num(a, 0))
            "G" -> canvas.setStrokeColorGray(num(a, 0))
            "rg" -> canvas.setFillColorRgb(num(a, 0), num(a, 1), num(a, 2))
            "RG" -> canvas.setStrokeColorRgb(num(a, 0), num(a, 1), num(a, 2))

            // Path construction
            "m" -> canvas.moveTo(num(a, 0), num(a, 1))
            "l" -> canvas.lineTo(num(a, 0), num(a, 1))
            "c" -> canvas.curveTo(
                num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5),
            )
            "v" -> canvas.curveTo(
                num(a, 0), num(a, 1), num(a, 0), num(a, 1), num(a, 2), num(a, 3),
            )
            "y" -> canvas.curveTo(
                num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 2), num(a, 3),
            )
            "h" -> canvas.closePath()
            "re" -> canvas.rectangle(num(a, 0), num(a, 1), num(a, 2), num(a, 3))

            // Path painting
            "S" -> canvas.strokePath()
            "s" -> { canvas.closePath(); canvas.strokePath() }
            "f", "F" -> canvas.fillPath(evenOdd = false)
            "f*" -> canvas.fillPath(evenOdd = true)
            "B" -> canvas.fillAndStrokePath(evenOdd = false)
            "B*" -> canvas.fillAndStrokePath(evenOdd = true)
            "b" -> { canvas.closePath(); canvas.fillAndStrokePath(evenOdd = false) }
            "b*" -> { canvas.closePath(); canvas.fillAndStrokePath(evenOdd = true) }
            "n" -> canvas.newPath()

            // Text
            "BT" -> canvas.beginText()
            "ET" -> canvas.endText()
            "Tf" -> {
                val fontName = (a.getOrNull(0) as? PdfName)?.value ?: return
                canvas.setFont(fontName, num(a, 1))
            }
            "Tm" -> canvas.setTextMatrix(
                num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5),
            )
            "Td", "TD" -> canvas.textTranslate(num(a, 0), num(a, 1))
            "Tj" -> (a.firstOrNull() as? PdfString)?.let { canvas.showText(it.bytes) }
            // TODO(session-2): TJ, ', ", T*, plus full text-state operators
            else -> { /* unrecognized: ignore for now */ }
        }
    }

    private fun num(list: List<PdfObject>, idx: Int): Double {
        return when (val v = list.getOrNull(idx)) {
            is PdfInt -> v.value.toDouble()
            is PdfReal -> v.value
            else -> 0.0
        }
    }
}
