package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.MathNode.Token
import io.github.yuroyami.kitepdf.epub.MathTokenKind.IDENTIFIER
import io.github.yuroyami.kitepdf.epub.MathTokenKind.NUMBER
import io.github.yuroyami.kitepdf.epub.MathTokenKind.OPERATOR
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Presentation MathML: the layout of each element, and formulas on a page (#32). */
class MathTest {

    private val size = 20.0
    private fun layout(node: MathNode, display: Boolean = false) = MathLayout(GenericFont.SERIF, size).layout(MathRoot(node, display, null))
    private fun mi(s: String) = Token(IDENTIFIER, s)
    private fun mn(s: String) = Token(NUMBER, s)
    private fun mo(s: String) = Token(OPERATOR, s)
    private fun glyphs(box: MathBox) = box.items.filterIsInstance<MathItem.Glyphs>()
    private fun glyph(box: MathBox, text: String) = glyphs(box).single { g -> g.glyphs.joinToString("") { it.text } == text }

    @Test
    fun aFractionSetsItsNumeratorAboveTheAxisAndItsDenominatorBelow() {
        val box = layout(MathNode.Fraction(mi("a"), mn("2"), null))
        val axis = MathLayout.AXIS * size
        val bar = box.items.filterIsInstance<MathItem.Rule>().single()
        assertEquals(-(axis + bar.height / 2), bar.y, 1e-9, "the bar sits on the axis")
        assertTrue(glyph(box, "a").y < -axis, "the numerator's baseline is above the bar")
        assertTrue(glyph(box, "2").y > 0.0, "the denominator's baseline is below the formula's")
        assertTrue(box.ascent > axis && box.descent > 0.0)
        // Inline, the parts shrink a script level; in display style they keep their size.
        assertEquals(size * 0.71, glyph(box, "a").fontSize, 1e-9)
        assertEquals(size, glyph(layout(MathNode.Fraction(mi("a"), mn("2"), null), display = true), "a").fontSize, 1e-9)
        assertTrue(layout(MathNode.Fraction(mi("a"), mn("2"), 0.0)).items.none { it is MathItem.Rule }, "a zero thickness draws no bar")
    }

    @Test
    fun scriptsShrinkAndMoveOffTheBaseline() {
        val box = layout(MathNode.Scripts(mi("x"), mn("1"), mn("2")))
        val sup = glyph(box, "2")
        val sub = glyph(box, "1")
        assertTrue(sup.y < 0.0, "the superscript is raised")
        assertTrue(sub.y > 0.0, "the subscript is lowered")
        assertEquals(size * 0.71, sup.fontSize, 1e-9)
        assertTrue(sup.x > glyph(box, "x").x && sub.x > glyph(box, "x").x)
        // Script levels shrink by 0.71 each, down to half the base size.
        var nested: MathNode = mn("9")
        repeat(4) { nested = MathNode.Scripts(mi("y"), null, nested) }
        assertEquals(size * 0.5, glyph(layout(nested), "9").fontSize, 1e-9)
    }

    @Test
    fun aRadicalDrawsItsSignAroundTheBody() {
        val body = layout(mi("x"))
        val box = layout(MathNode.Radical(mi("x"), null))
        val sign = box.items.filterIsInstance<MathItem.Stroke>().single()
        assertTrue(sign.points.size >= 4)
        val top = sign.points.minOf { it.second }
        assertTrue(top < -body.ascent, "the sign rises above the body")
        assertTrue(glyph(box, "x").x > 0.0, "the body sits right of the sign")
        assertTrue(box.ascent > body.ascent)
        // An index sits small above the tick.
        val rooted = layout(MathNode.Radical(mi("x"), mn("3")))
        assertTrue(glyph(rooted, "3").fontSize < size && glyph(rooted, "3").y < 0.0)
    }

    @Test
    fun aFenceStretchesToTheRowItEncloses() {
        val flat = layout(MathNode.Row(listOf(mo("("), mi("a"), mo(")"))))
        assertTrue(glyphs(flat).all { it.scaleY == 1.0 }, "a fence around one letter keeps its size")
        val tall = layout(MathNode.Row(listOf(mo("("), MathNode.Fraction(mi("a"), MathNode.Fraction(mi("b"), mi("c"), null), null), mo(")"))), display = true)
        val fences = glyphs(tall).filter { g -> g.glyphs.single().text in setOf("(", ")") }
        assertEquals(2, fences.size)
        assertTrue(fences.all { it.scaleY > 1.2 }, "fences around a nested fraction grow: ${fences.map { it.scaleY }}")
    }

    @Test
    fun operatorsTakeTheirSpaces() {
        val box = layout(MathNode.Row(listOf(mi("a"), mo("+"), mi("b"))))
        val a = glyph(box, "a")
        val plus = glyph(box, "+")
        val aWidth = a.glyphs.sumOf { it.advanceWidth } * a.fontSize / 1000
        assertEquals(size * 4 / 18, plus.x - (a.x + aWidth), 1e-9, "a binary operator is spaced by 4/18 em")
        // A leading minus is a prefix and takes no space.
        val prefix = layout(MathNode.Row(listOf(mo("−"), mi("b"))))
        assertEquals(0.0, glyph(prefix, "−").x, 1e-9)
    }

    @Test
    fun aTableCentresOnTheAxis() {
        val box = layout(MathNode.Table(listOf(listOf(mn("1"), mn("0")), listOf(mn("0"), mn("1"))), listOf("center")))
        val ones = glyphs(box).filter { g -> g.glyphs.single().text == "1" }.sortedBy { it.y }
        assertEquals(2, ones.size)
        assertTrue(ones[0].y < ones[1].y && ones[0].x < ones[1].x, "the diagonal runs down and right")
        val axis = MathLayout.AXIS * size
        assertEquals(axis, (box.ascent - box.descent) / 2, 1e-9, "the middle of the table is on the axis")
    }

    @Test
    fun aLargeOperatorTakesItsLimitsAboveAndBelowInDisplayOnly() {
        val sum = MathNode.UnderOver(Token(OPERATOR, "∑"), MathNode.Row(listOf(mi("i"), mo("="), mn("1"))), mi("n"), false, false)
        val display = layout(sum, display = true)
        val sigma = glyph(display, "∑")
        assertTrue(sigma.fontSize > size, "a large operator grows in display style")
        assertTrue(glyph(display, "n").y < sigma.y - size * 0.5 && abs(glyph(display, "n").x - sigma.x) < size, "the upper limit sits above")
        val inline = layout(sum)
        val sigmaInline = glyph(inline, "∑")
        assertEquals(size, sigmaInline.fontSize, 1e-9)
        val sigmaEnd = sigmaInline.x + sigmaInline.glyphs.sumOf { it.advanceWidth } * sigmaInline.fontSize / 1000
        assertTrue(glyph(inline, "n").x >= sigmaEnd - 1e-9, "inline, the limits move to script places after the operator")
    }

    @Test
    fun variantsPickTheirLetters() {
        assertEquals("ℝ", glyphs(layout(Token(IDENTIFIER, "R", variant = "double-struck"))).single().glyphs.single().text)
        assertTrue(glyphs(layout(mi("x"))).single().spec.italic, "a single letter is italic")
        assertTrue(!glyphs(layout(mi("sin"))).single().spec.italic, "a name is upright")
        assertTrue(glyphs(layout(Token(IDENTIFIER, "v", variant = "bold"))).single().spec.bold)
    }

    @Test
    fun aFormulaReadsAsLinearText() {
        val quadratic = MathNode.Row(listOf(
            mi("x"), mo("="),
            MathNode.Fraction(
                MathNode.Row(listOf(mo("−"), mi("b"), mo("±"), MathNode.Radical(MathNode.Row(listOf(MathNode.Scripts(mi("b"), null, mn("2")), mo("−"), mn("4"), mi("a"), mi("c"))), null))),
                MathNode.Row(listOf(mn("2"), mi("a"))),
                null,
            ),
        ))
        assertEquals("x=(−b±√(b^2−4ac))/(2a)", MathLinear.of(quadratic))
        assertEquals("the formula", MathRoot(quadratic, false, "the formula").readingText)
    }

    /* ── formulas on a page ─────────────────────────────────────────────────── */

    private fun open(body: String) = EpubDocument.open(
        EpubFixtures.epub(body),
        EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0),
    )

    private val ns = "xmlns=\"http://www.w3.org/1998/Math/MathML\""

    @Test
    fun anInlineFormulaDrawsOnItsLineAndReadsInPlace() {
        // A display-style fraction in running text: its full-size parts are taller than a line.
        val doc = open("<p>Take <math $ns><mstyle displaystyle=\"true\"><mfrac><mi>a</mi><mn>2</mn></mfrac></mstyle></math> of it.</p><p>Plain line.</p>")
        val page = doc.page(KiteLocation(0, 0))
        val calls = RecordingCanvas().also { page.renderTo(it) }.calls
        val texts = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
        assertTrue("a" in texts && "2" in texts, "the parts draw as glyphs: $texts")
        assertTrue(calls.any { it is RecordingCanvas.Call.Fill }, "the fraction bar draws")
        val text = page.textContent()!!.plainText
        assertTrue("Take a/2 of it." in text, "the formula reads in place: $text")
        val lines = page.textContent()!!.blocks.flatMap { it.lines }
        val formulaLine = lines.first { "a/2" in it.text }
        val plainLine = lines.first { "Plain" in it.text }
        assertTrue(formulaLine.bounds.top - formulaLine.bounds.bottom > plainLine.bounds.top - plainLine.bounds.bottom, "the fraction makes its line taller")
        assertEquals(true, page.drawsHostFontText)
    }

    @Test
    fun aDisplayFormulaTakesALineOfItsOwnCentred() {
        val doc = open("<p>Before <math $ns display=\"block\"><mi>x</mi><mo>=</mo><mn>1</mn></math> after.</p>")
        val page = doc.page(KiteLocation(0, 0))
        val lines = page.textContent()!!.blocks.flatMap { it.lines }
        val formula = lines.single { it.text == "x=1" }
        val middle = (formula.bounds.left + formula.bounds.right) / 2
        assertEquals(200.0, middle, 5.0, "the formula is centred on the page")
        val before = lines.single { it.text.startsWith("Before") }
        assertTrue(lines.any { it.text.startsWith("after") })
        // Display bounds are y down with the smaller y in bottom: the formula starts half an em below.
        assertTrue(formula.bounds.bottom - before.bounds.top >= 12.0 * 0.5 - 0.01, "the formula has room above it")
    }

    @Test
    fun theAltTextAndTheAnnotationsAreRead() {
        val doc = open(
            "<p><math $ns alttext=\"one half\"><semantics><mfrac><mn>1</mn><mn>2</mn></mfrac>" +
                "<annotation encoding=\"application/x-tex\">\\frac12</annotation></semantics></math></p>",
        )
        val page = doc.page(KiteLocation(0, 0))
        val texts = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
        assertTrue("\\frac12" !in texts.joinToString(""), "an annotation does not draw")
        assertTrue("one half" in page.textContent()!!.plainText)
        assertEquals(true, page.drawsHostFontText, "a page of a formula alone draws host-font text")
        // Content markup kept beside the presentation draws nothing either.
        val stray = open("<p><math $ns><mi>x</mi><annotation-xml encoding=\"MathML-Content\"><mi>z</mi></annotation-xml></math></p>")
        val strayTexts = RecordingCanvas().also { stray.page(KiteLocation(0, 0)).renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
        assertTrue("x" in strayTexts && "z" !in strayTexts, "an annotation does not draw: $strayTexts")
    }

    @Test
    fun mfencedAndMtableAndMencloseDraw() {
        val doc = open(
            "<p><math $ns><mfenced><mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>0</mn></mtd></mtr>" +
                "<mtr><mtd><mn>0</mn></mtd><mtd><mn>1</mn></mtd></mtr></mtable></mfenced>" +
                "<menclose notation=\"box\"><mi>q</mi></menclose></math></p>",
        )
        val calls = RecordingCanvas().also { doc.page(KiteLocation(0, 0)).renderTo(it) }.calls
        val texts = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.text }
        assertEquals(2, texts.count { it == "1" })
        assertTrue("(" in texts && ")" in texts, "the fences draw: $texts")
        assertTrue(calls.count { it is RecordingCanvas.Call.Fill } >= 4, "the box draws its four sides")
    }

    @Test
    fun verticalTextKeepsTheLinearForm() {
        val doc = open("<body style=\"writing-mode: vertical-rl\"><p>縦<math $ns><msup><mi>x</mi><mn>2</mn></msup></math></p></body>")
        val page = doc.page(KiteLocation(0, 0))
        assertTrue("x^2" in page.textContent()!!.plainText)
        val drawn = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString("") { it.text }
        assertTrue("x^2" in drawn, "the linear text draws as text: $drawn")
    }
}
