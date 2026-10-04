package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import kotlin.math.max
import kotlin.math.pow

/**
 * Presentation MathML (MathML Core, the subset that EPUB 3.3 requires): the element tree of a
 * `<math>` element, and the layout that turns it into glyphs and rules (#32). A math element
 * is one inline atom: it has a width, a height above its baseline and a depth below it.
 */
internal class MathRoot(
    val body: MathNode,
    /** True for `display="block"`: centred on a line of its own, in display style. */
    val display: Boolean,
    /** The `alttext`, which the reading order prefers to the linearised form. */
    val alt: String?,
) {
    /** The words a reader hears for this formula: the `alttext`, or a linear form of the tree. */
    val readingText: String get() = alt ?: MathLinear.of(body)
}

internal enum class MathTokenKind { IDENTIFIER, NUMBER, OPERATOR, TEXT, STRING }

internal sealed class MathNode {
    /** `mi`, `mn`, `mo`, `mtext` or `ms`: a run of characters. */
    class Token(
        val kind: MathTokenKind,
        val text: String,
        val variant: String? = null,
        val stretchy: Boolean? = null,
        val largeOp: Boolean? = null,
        val movableLimits: Boolean? = null,
        val form: String? = null,
    ) : MathNode()

    /** `mspace`, [width] in ems. */
    class Space(val width: Double) : MathNode()

    class Row(val children: List<MathNode>) : MathNode()

    /** `mfrac`; [thickness] in ems, or null for the default rule. */
    class Fraction(val numerator: MathNode, val denominator: MathNode, val thickness: Double?) : MathNode()

    /** `msub`, `msup`, `msubsup`, and the post-scripts of `mmultiscripts`. */
    class Scripts(val base: MathNode, val sub: MathNode?, val sup: MathNode?) : MathNode()

    /** `munder`, `mover`, `munderover`. */
    class UnderOver(val base: MathNode, val under: MathNode?, val over: MathNode?, val accent: Boolean, val accentUnder: Boolean) : MathNode()

    /** `msqrt` without an index, `mroot` with one. */
    class Radical(val body: MathNode, val index: MathNode?) : MathNode()

    /** `mtable`: rows of cells, with the alignment of each column. */
    class Table(val rows: List<List<MathNode>>, val columnAlign: List<String>) : MathNode()

    /** `mstyle`: display style, script level (`+1`, `-1` or a number) and variant for its content. */
    class Style(val child: MathNode, val displayStyle: Boolean?, val scriptLevel: String?, val variant: String?) : MathNode()

    /** `mphantom`: takes its room, draws nothing. */
    class Phantom(val child: MathNode) : MathNode()

    /** `menclose` with the box and strike notations. */
    class Enclose(val child: MathNode, val notations: Set<String>) : MathNode()
}

/** Reads the presentation markup of a `<math>` element into a [MathNode] tree. */
internal object MathParser {

    private const val MAX_DEPTH = 64

    fun parse(el: KiteXmlNode.Element): MathRoot = MathRoot(
        body = row(el, 0),
        display = el.attrs["display"] == "block" || el.attrs["mode"] == "display",
        alt = el.attrs["alttext"]?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun elements(el: KiteXmlNode.Element): List<KiteXmlNode.Element> = el.children.filterIsInstance<KiteXmlNode.Element>()

    private fun row(el: KiteXmlNode.Element, depth: Int): MathNode {
        val children = elements(el).mapNotNull { node(it, depth + 1) }
        return children.singleOrNull() ?: MathNode.Row(children)
    }

    /** The `i`-th element child as a node, or an empty row when it is missing. */
    private fun arg(el: KiteXmlNode.Element, i: Int, depth: Int): MathNode =
        elements(el).getOrNull(i)?.let { node(it, depth + 1) } ?: MathNode.Row(emptyList())

    private fun argOrNull(el: KiteXmlNode.Element, i: Int, depth: Int): MathNode? =
        elements(el).getOrNull(i)?.takeIf { it.tag != "none" }?.let { node(it, depth + 1) }

    private fun node(el: KiteXmlNode.Element, depth: Int): MathNode? {
        if (depth > MAX_DEPTH) return null
        val a = el.attrs
        return when (el.tag) {
            "mi" -> token(el, MathTokenKind.IDENTIFIER)
            "mn" -> token(el, MathTokenKind.NUMBER)
            "mo" -> token(el, MathTokenKind.OPERATOR)
            "mtext" -> token(el, MathTokenKind.TEXT)
            "ms" -> MathNode.Token(MathTokenKind.STRING, (a["lquote"] ?: "\"") + text(el) + (a["rquote"] ?: "\""), a["mathvariant"])
            "mspace" -> MathNode.Space(MathLengths.em(a["width"]) ?: 0.0)
            "mfrac" -> MathNode.Fraction(arg(el, 0, depth), arg(el, 1, depth), a["linethickness"]?.let(MathLengths::thickness))
            "msqrt" -> MathNode.Radical(row(el, depth), null)
            "mroot" -> MathNode.Radical(arg(el, 0, depth), arg(el, 1, depth))
            "msub" -> MathNode.Scripts(arg(el, 0, depth), argOrNull(el, 1, depth), null)
            "msup" -> MathNode.Scripts(arg(el, 0, depth), null, argOrNull(el, 1, depth))
            "msubsup" -> MathNode.Scripts(arg(el, 0, depth), argOrNull(el, 1, depth), argOrNull(el, 2, depth))
            // Only the first pair of post-scripts: pre-scripts are rare in books.
            "mmultiscripts" -> MathNode.Scripts(arg(el, 0, depth), argOrNull(el, 1, depth), argOrNull(el, 2, depth))
            "munder" -> MathNode.UnderOver(arg(el, 0, depth), argOrNull(el, 1, depth), null, false, a["accentunder"] == "true")
            "mover" -> MathNode.UnderOver(arg(el, 0, depth), null, argOrNull(el, 1, depth), a["accent"] == "true", false)
            "munderover" -> MathNode.UnderOver(arg(el, 0, depth), argOrNull(el, 1, depth), argOrNull(el, 2, depth), a["accent"] == "true", a["accentunder"] == "true")
            "mtable" -> table(el, depth)
            "mstyle" -> MathNode.Style(row(el, depth), a["displaystyle"]?.let { it == "true" }, a["scriptlevel"], a["mathvariant"])
            "mphantom" -> MathNode.Phantom(row(el, depth))
            "menclose" -> MathNode.Enclose(row(el, depth), (a["notation"] ?: "longdiv").split(' ', ',').filter { it.isNotBlank() }.toSet())
            "mfenced" -> fenced(el, depth)
            // The presentation child of a semantics element; an annotation says nothing to draw.
            "semantics" -> elements(el).firstOrNull { it.tag != "annotation" && it.tag != "annotation-xml" }?.let { node(it, depth + 1) }
                ?: elements(el).firstOrNull { it.tag == "annotation-xml" && it.attrs["encoding"]?.contains("Presentation", ignoreCase = true) == true }
                    ?.let { row(it, depth) }
            "annotation", "annotation-xml", "none", "mprescripts" -> null
            "maction" -> elements(el).firstOrNull()?.let { node(it, depth + 1) }
            else -> row(el, depth) // mrow, mpadded, merror and unknown elements show their content
        }
    }

    private fun token(el: KiteXmlNode.Element, kind: MathTokenKind): MathNode.Token {
        val a = el.attrs
        return MathNode.Token(
            kind, text(el), a["mathvariant"],
            stretchy = a["stretchy"]?.let { it == "true" },
            largeOp = a["largeop"]?.let { it == "true" },
            movableLimits = a["movablelimits"]?.let { it == "true" },
            form = a["form"],
        )
    }

    /** A token's text: its character data with the white space at its ends dropped and runs of it collapsed (MathML 3, 2.1.7). */
    private fun text(el: KiteXmlNode.Element): String {
        val sb = StringBuilder()
        fun walk(n: KiteXmlNode) {
            when (n) {
                is KiteXmlNode.Text -> sb.append(n.text)
                is KiteXmlNode.Comment -> {}
                is KiteXmlNode.Element -> if (n.tag == "mglyph") n.attrs["alt"]?.let(sb::append) else n.children.forEach(::walk)
            }
        }
        el.children.forEach(::walk)
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun table(el: KiteXmlNode.Element, depth: Int): MathNode.Table {
        val rows = elements(el).map { r ->
            when (r.tag) {
                // A labelled row's first cell is its label, which a book numbers equations with.
                "mtr" -> elements(r).map { cell -> if (cell.tag == "mtd") row(cell, depth + 1) else node(cell, depth + 2) ?: MathNode.Row(emptyList()) }
                "mlabeledtr" -> elements(r).drop(1).map { row(it, depth + 1) }
                else -> listOf(node(r, depth + 1) ?: MathNode.Row(emptyList()))
            }
        }
        return MathNode.Table(rows, (el.attrs["columnalign"] ?: "center").split(' ').filter { it.isNotBlank() })
    }

    /** The legacy `mfenced`: a row with the fences and the separators as operators (MathML 3, 3.3.8). */
    private fun fenced(el: KiteXmlNode.Element, depth: Int): MathNode {
        val a = el.attrs
        val separators = (a["separators"] ?: ",").filter { !it.isWhitespace() }
        val items = elements(el).mapNotNull { node(it, depth + 1) }
        val out = ArrayList<MathNode>()
        (a["open"] ?: "(").takeIf { it.isNotEmpty() }?.let { out += MathNode.Token(MathTokenKind.OPERATOR, it, stretchy = true, form = "prefix") }
        items.forEachIndexed { i, item ->
            if (i > 0 && separators.isNotEmpty()) {
                out += MathNode.Token(MathTokenKind.OPERATOR, separators[minOf(i - 1, separators.length - 1)].toString(), form = "infix")
            }
            out += item
        }
        (a["close"] ?: ")").takeIf { it.isNotEmpty() }?.let { out += MathNode.Token(MathTokenKind.OPERATOR, it, stretchy = true, form = "postfix") }
        return MathNode.Row(out)
    }
}

/** MathML lengths in ems (MathML 3, 2.1.5.2): named spaces, `em`, `ex`, `pt` and `px`. */
internal object MathLengths {
    private val NAMED = mapOf(
        "veryverythinmathspace" to 1.0 / 18, "verythinmathspace" to 2.0 / 18, "thinmathspace" to 3.0 / 18,
        "mediummathspace" to 4.0 / 18, "thickmathspace" to 5.0 / 18, "verythickmathspace" to 6.0 / 18,
        "veryverythickmathspace" to 7.0 / 18,
    )

    fun em(value: String?): Double? {
        val v = value?.trim()?.lowercase() ?: return null
        NAMED[v]?.let { return it }
        val number = v.takeWhile { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: return null
        return when (v.substring(v.takeWhile { it.isDigit() || it == '.' || it == '-' }.length).trim()) {
            "em", "" -> number
            "ex" -> number * 0.43
            "pt" -> number / 10.0
            "px" -> number * 0.075
            "mu" -> number / 18.0
            else -> null
        }
    }

    /** A fraction's `linethickness`: a length, a multiple of the default rule, or `thin`, `medium` and `thick`. */
    fun thickness(value: String): Double? = when (value.trim().lowercase()) {
        "thin" -> MathLayout.RULE * 0.5
        "medium" -> MathLayout.RULE
        "thick" -> MathLayout.RULE * 2
        else -> em(value) ?: value.trim().toDoubleOrNull()?.times(MathLayout.RULE)
    }
}

/** What the layout of a formula draws, relative to its baseline; y grows down. */
internal sealed class MathItem {
    /** Glyphs whose baseline starts at ([x], [y]), drawn [scaleY] times as tall about the math axis. */
    class Glyphs(
        val x: Double,
        val y: Double,
        val glyphs: List<TextGlyph>,
        val fontSize: Double,
        val spec: FontSpec,
        val scaleY: Double = 1.0,
    ) : MathItem()

    /** A filled rectangle: a fraction bar, the top of a radical, or a side of a box. */
    class Rule(val x: Double, val y: Double, val width: Double, val height: Double) : MathItem()

    /** A stroked polyline, such as the sign of a radical or a strike. */
    class Stroke(val points: List<Pair<Double, Double>>, val width: Double) : MathItem()
}

/** A laid-out formula: [width], [ascent] above its baseline, [descent] below it, and what it draws. */
internal class MathBox(val width: Double, val ascent: Double, val descent: Double, val items: List<MathItem>)

/**
 * Lays out a [MathRoot] at a font size, with the metrics of TeX in ems: the math axis, the
 * fraction rule, the script shifts and the operator spacing (#32). Glyphs draw in the host
 * font, which the Standard 14 metrics measure, and the Symbol font's widths measure the symbols.
 */
internal class MathLayout(private val family: GenericFont, private val baseSize: Double) {

    companion object {
        /** The height of the math axis above the baseline, where fraction bars and operators centre. */
        const val AXIS = 0.25

        /** The default thickness of a fraction bar and of the top of a radical. */
        const val RULE = 0.066

        /** How much smaller each script level draws, and the smallest size of a script. */
        private const val SCRIPT_RATIO = 0.71
        private const val SCRIPT_FLOOR = 0.5

        private const val RELATION = 5.0 / 18
        private const val BINARY = 4.0 / 18
        private const val THIN = 3.0 / 18

        private val RELATIONS = "=<>≤≥≠≈≡∼≅∝∈∉∋⊂⊃⊆⊇⊊⊋→←↔⇒⇐⇔↦∥⊥≪≫≺≻⊢⊨∣"
        private val BINARIES = "+-−±∓×÷·∗∘⊕⊗⊖∪∩∧∨∖⋅"
        private val PUNCTUATION = ",;"
        private val FENCES = "()[]{}|‖⟨⟩⌈⌉⌊⌋"
        private val LARGE = "∑∏∐⋃⋂⨁⨂⨀∫∬∭∮⋁⋀"
        private val INTEGRALS = "∫∬∭∮"

        /** The Symbol font's glyph name for the math characters that the Standard 14 Latin faces lack. */
        private val SYMBOL_NAMES = mapOf(
            '∑' to "summation", '∏' to "product", '∫' to "integral", '√' to "radical", '≤' to "lessequal",
            '≥' to "greaterequal", '≠' to "notequal", '±' to "plusminus", '×' to "multiply", '÷' to "divide",
            '∞' to "infinity", '∂' to "partialdiff", '∇' to "gradient", '∈' to "element", '∉' to "notelement",
            '⊂' to "propersubset", '⊃' to "propersuperset", '⊆' to "reflexsubset", '⊇' to "reflexsuperset",
            '∩' to "intersection", '∪' to "union", '∧' to "logicaland", '∨' to "logicalor", '¬' to "logicalnot",
            '→' to "arrowright", '←' to "arrowleft", '↔' to "arrowboth", '⇒' to "arrowdblright", '⇐' to "arrowdblleft",
            '⇔' to "arrowdblboth", '≈' to "approxequal", '≡' to "equivalence", '∼' to "similar", '≅' to "congruent",
            '∝' to "proportional", '∀' to "universal", '∃' to "existential", '∅' to "emptyset", '∠' to "angle",
            '⊥' to "perpendicular", '⋅' to "dotmath", '−' to "minus", '′' to "minute", '″' to "second",
            '…' to "ellipsis", '⟨' to "angleleft", '⟩' to "angleright",
            'α' to "alpha", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta", 'ε' to "epsilon", 'ζ' to "zeta",
            'η' to "eta", 'θ' to "theta", 'ι' to "iota", 'κ' to "kappa", 'λ' to "lambda", 'μ' to "mu", 'ν' to "nu",
            'ξ' to "xi", 'ο' to "omicron", 'π' to "pi", 'ρ' to "rho", 'σ' to "sigma", 'ς' to "sigma1", 'τ' to "tau",
            'υ' to "upsilon", 'φ' to "phi", 'ϕ' to "phi1", 'χ' to "chi", 'ψ' to "psi", 'ω' to "omega",
            'ϑ' to "theta1", 'ϖ' to "omega1",
            'Γ' to "Gamma", 'Δ' to "Delta", 'Θ' to "Theta", 'Λ' to "Lambda", 'Ξ' to "Xi", 'Π' to "Pi",
            'Σ' to "Sigma", 'Υ' to "Upsilon", 'Φ' to "Phi", 'Ψ' to "Psi", 'Ω' to "Omega",
        )
    }

    /** How a part of the formula is set: its size, display style, script level and variant. */
    private class Ctx(val size: Double, val display: Boolean, val level: Int, val variant: String?) {
        fun em(v: Double) = v * size
    }

    /** A laid-out part: [width], [ascent], [descent] and its items, relative to its own baseline. */
    private class Laid(val width: Double, val ascent: Double, val descent: Double, val items: List<MathItem>, val italic: Boolean = false) {
        fun moved(dx: Double, dy: Double): List<MathItem> = items.map { item ->
            when (item) {
                is MathItem.Glyphs -> MathItem.Glyphs(item.x + dx, item.y + dy, item.glyphs, item.fontSize, item.spec, item.scaleY)
                is MathItem.Rule -> MathItem.Rule(item.x + dx, item.y + dy, item.width, item.height)
                is MathItem.Stroke -> MathItem.Stroke(item.points.map { (x, y) -> (x + dx) to (y + dy) }, item.width)
            }
        }
    }

    fun layout(root: MathRoot): MathBox {
        val laid = lay(root.body, Ctx(baseSize, root.display, 0, null))
        return MathBox(laid.width, laid.ascent, laid.descent, laid.items)
    }

    private fun sizeAt(level: Int): Double = baseSize * max(SCRIPT_RATIO.pow(level.coerceAtLeast(0)), SCRIPT_FLOOR)

    private fun scripted(c: Ctx, levels: Int = 1) = Ctx(sizeAt(c.level + levels), false, c.level + levels, c.variant)

    private fun lay(node: MathNode, c: Ctx): Laid = when (node) {
        is MathNode.Token -> token(node, c)
        is MathNode.Space -> Laid(c.em(node.width).coerceAtLeast(0.0), 0.0, 0.0, emptyList())
        is MathNode.Row -> row(node.children, c)
        is MathNode.Fraction -> fraction(node, c)
        is MathNode.Scripts -> scripts(node, c)
        is MathNode.UnderOver -> underOver(node, c)
        is MathNode.Radical -> radical(node, c)
        is MathNode.Table -> table(node, c)
        is MathNode.Style -> {
            val level = when {
                node.scriptLevel == null -> c.level
                node.scriptLevel.startsWith("+") || node.scriptLevel.startsWith("-") -> c.level + (node.scriptLevel.toIntOrNull() ?: 0)
                else -> node.scriptLevel.toIntOrNull() ?: c.level
            }.coerceAtLeast(0)
            lay(node.child, Ctx(sizeAt(level), node.displayStyle ?: c.display, level, node.variant ?: c.variant))
        }
        is MathNode.Phantom -> lay(node.child, c).let { Laid(it.width, it.ascent, it.descent, emptyList()) }
        is MathNode.Enclose -> enclose(node, c)
    }

    /* ── tokens ─────────────────────────────────────────────────────────────── */

    private fun token(t: MathNode.Token, c: Ctx, stretch: Double = 1.0): Laid {
        if (t.text.isEmpty()) return Laid(0.0, 0.0, 0.0, emptyList())
        // A single-letter identifier is italic unless a variant says otherwise (MathML 3, 3.2.3.2).
        val variant = t.variant ?: c.variant ?: if (t.kind == MathTokenKind.IDENTIFIER && codePointsOf(t.text).size == 1) "italic" else "normal"
        val text = mapVariant(t.text, variant)
        val bold = "bold" in variant
        val italic = variant == "italic" || variant == "bold-italic" || variant == "sans-serif-italic" || variant == "sans-serif-bold-italic"
        val fam = when {
            variant.startsWith("sans-serif") -> GenericFont.SANS
            variant == "monospace" -> GenericFont.MONO
            else -> family
        }
        val large = t.kind == MathTokenKind.OPERATOR && (t.largeOp ?: (t.text.length == 1 && t.text[0] in LARGE)) && c.display
        val size = if (large) c.size * (if (t.text.all { it in INTEGRALS }) 2.0 else 1.4) else c.size
        val (glyphs, width) = shape(text, size, bold, italic, fam)
        var ascent = 0.0
        var descent = 0.0
        for (cp in codePointsOf(text)) {
            val (a, d) = extent(cp)
            ascent = max(ascent, a)
            descent = max(descent, d)
        }
        val spec = FontSpec(fontFamily(fam), bold = bold, italic = italic)
        if (large) {
            // A large operator centres on the axis.
            val half = (ascent + descent) * size / 2
            val axis = c.em(AXIS)
            val y = (ascent * size - half) - axis
            return Laid(width, axis + half, half - axis, listOf(MathItem.Glyphs(0.0, y, glyphs, size, spec)))
        }
        if (stretch != 1.0) {
            // Drawn taller about the axis, as a stretched fence is.
            val axis = c.em(AXIS)
            val y = axis * (stretch - 1)
            return Laid(width, axis + (ascent * size - axis) * stretch, (descent * size + axis) * stretch - axis, listOf(MathItem.Glyphs(0.0, y, glyphs, size, spec, stretch)))
        }
        return Laid(width, ascent * size, descent * size, listOf(MathItem.Glyphs(0.0, 0.0, glyphs, size, spec)), italic)
    }

    private fun fontFamily(fam: GenericFont) = when (fam) {
        GenericFont.SANS -> KiteFontFamily.SansSerif
        GenericFont.MONO -> KiteFontFamily.Monospace
        GenericFont.SERIF -> KiteFontFamily.Serif
    }

    private fun shape(text: String, size: Double, bold: Boolean, italic: Boolean, fam: GenericFont): Pair<List<TextGlyph>, Double> {
        val glyphs = ArrayList<TextGlyph>()
        var width = 0.0
        for (cp in codePointsOf(text)) {
            val advance = symbolWidth(cp) ?: FontMetrics.advance1000(cp, bold, italic, fam).toDouble()
            glyphs += TextGlyph(
                byteOffset = 0, byteCount = 1, gid = -1, text = CharText.of(cp),
                advanceWidth = advance, outline = null, isWordSpace = cp == ' '.code,
            )
            width += advance * size / 1000.0
        }
        return glyphs to width
    }

    private fun symbolWidth(cp: Int): Double? =
        if (cp > 0xFFFF) null else SYMBOL_NAMES[cp.toChar()]?.let { Standard14Widths.widthOf("Symbol", it)?.toDouble() }

    /** The height and the depth of a character in ems, from its class: an estimate, since the host face is not known here. */
    private fun extent(cp: Int): Pair<Double, Double> {
        val ch = if (cp <= 0xFFFF) cp.toChar() else ' '
        return when {
            ch in "gjpqyβγζημξρςφχψ" -> 0.48 to 0.22
            ch in "acemnorsuvwxzαεικνοπστυω" -> 0.48 to 0.0
            ch in FENCES || ch == '/' -> 0.75 to 0.25
            ch in LARGE -> 0.75 to 0.25
            ch in "+=−±∓×÷<>≤≥≠≈≡∼" -> 0.58 to 0.08
            ch in ",;" -> 0.12 to 0.18
            ch == '.' -> 0.12 to 0.0
            else -> 0.72 to 0.0
        }
    }

    /** The characters of [text] in the Unicode letters of a MathML [variant] that has letters of its own. */
    private fun mapVariant(text: String, variant: String): String {
        val base = when (variant) {
            "double-struck" -> 0x1D538 to mapOf('C' to 'ℂ', 'H' to 'ℍ', 'N' to 'ℕ', 'P' to 'ℙ', 'Q' to 'ℚ', 'R' to 'ℝ', 'Z' to 'ℤ')
            "script" -> 0x1D49C to mapOf('B' to 'ℬ', 'E' to 'ℰ', 'F' to 'ℱ', 'H' to 'ℋ', 'I' to 'ℐ', 'L' to 'ℒ', 'M' to 'ℳ', 'R' to 'ℛ', 'e' to 'ℯ', 'g' to 'ℊ', 'o' to 'ℴ')
            "fraktur" -> 0x1D504 to mapOf('C' to 'ℭ', 'H' to 'ℌ', 'I' to 'ℑ', 'R' to 'ℜ', 'Z' to 'ℨ')
            else -> return text
        }
        val sb = StringBuilder()
        for (cp in codePointsOf(text)) {
            val ch = if (cp <= 0xFFFF) cp.toChar() else null
            val special = ch?.let { base.second[it] }
            when {
                special != null -> sb.append(special)
                ch != null && ch in 'A'..'Z' -> appendCodePoint(sb, base.first + (ch - 'A'))
                ch != null && ch in 'a'..'z' -> appendCodePoint(sb, base.first + 26 + (ch - 'a'))
                else -> appendCodePoint(sb, cp)
            }
        }
        return sb.toString()
    }

    /* ── rows and operators ─────────────────────────────────────────────────── */

    private fun isOperator(n: MathNode, chars: String): Boolean =
        n is MathNode.Token && n.kind == MathTokenKind.OPERATOR && n.text.length == 1 && n.text[0] in chars

    /** The space before and after operator [t] at position [i] of a row of [count] (MathML 3, 3.2.5.7, with TeX's values). */
    private fun spacing(t: MathNode.Token, i: Int, count: Int, c: Ctx): Pair<Double, Double> {
        if (t.kind != MathTokenKind.OPERATOR || t.text.isEmpty()) return 0.0 to 0.0
        val form = t.form ?: when {
            count == 1 -> "infix"
            i == 0 -> "prefix"
            i == count - 1 -> "postfix"
            else -> "infix"
        }
        val ch = if (t.text.length == 1) t.text[0] else null
        // Scripts set their operators tight, as TeX does.
        val scale = if (c.level > 0) 0.0 else 1.0
        return when {
            ch != null && ch in FENCES -> 0.0 to 0.0
            ch != null && ch in PUNCTUATION -> 0.0 to c.em(THIN)
            ch != null && ch in LARGE -> c.em(THIN) to c.em(THIN)
            form != "infix" -> 0.0 to 0.0
            ch != null && ch in RELATIONS -> c.em(RELATION * scale) to c.em(RELATION * scale)
            ch != null && ch in BINARIES -> c.em(BINARY * scale) to c.em(BINARY * scale)
            t.text.length > 1 -> c.em(THIN) to c.em(THIN) // named operators such as lim and sin
            else -> c.em(THIN * scale) to c.em(THIN * scale)
        }
    }

    private fun row(children: List<MathNode>, c: Ctx): Laid {
        if (children.isEmpty()) return Laid(0.0, 0.0, 0.0, emptyList())
        // Stretchy fences grow to the height of the rest of the row, about the axis.
        val stretchy = children.map { it is MathNode.Token && it.kind == MathTokenKind.OPERATOR && (it.stretchy ?: (it.text.length == 1 && it.text[0] in FENCES)) }
        val laidOthers = children.mapIndexed { i, n -> if (stretchy[i]) null else lay(n, c) }
        val axis = c.em(AXIS)
        val reach = laidOthers.filterNotNull().maxOfOrNull { max(it.ascent - axis, it.descent + axis) } ?: 0.0
        val laid = children.mapIndexed { i, n ->
            laidOthers[i] ?: run {
                val natural = 0.5 * c.size // the half height of a fence about the axis
                val stretch = if (reach > natural * 1.1) reach / natural else 1.0
                token(n as MathNode.Token, c, stretch)
            }
        }
        val items = ArrayList<MathItem>()
        var x = 0.0
        var ascent = 0.0
        var descent = 0.0
        for ((i, part) in laid.withIndex()) {
            val (before, after) = (children[i] as? MathNode.Token)?.let { spacing(it, i, children.size, c) } ?: (0.0 to 0.0)
            x += before
            items += part.moved(x, 0.0)
            x += part.width + after
            ascent = max(ascent, part.ascent)
            descent = max(descent, part.descent)
        }
        return Laid(x, ascent, descent, items, laid.lastOrNull()?.italic == true)
    }

    /* ── fractions, scripts, limits ─────────────────────────────────────────── */

    private fun fraction(f: MathNode.Fraction, c: Ctx): Laid {
        // Inside a fraction, display style ends; inline, the parts shrink a level (MathML 3, 3.3.2).
        val inner = if (c.display) Ctx(c.size, false, c.level, c.variant) else scripted(c)
        val num = lay(f.numerator, inner)
        val den = lay(f.denominator, inner)
        val t = c.em(f.thickness ?: RULE)
        val gap = c.em(if (c.display) 0.2 else 0.1)
        val axis = c.em(AXIS)
        val up = axis + t / 2 + gap + num.descent
        val down = -axis + t / 2 + gap + den.ascent
        val pad = c.em(0.1)
        val width = max(num.width, den.width) + 2 * pad
        val items = ArrayList<MathItem>()
        items += num.moved((width - num.width) / 2, -up)
        items += den.moved((width - den.width) / 2, down)
        if (t > 0) items += MathItem.Rule(pad / 2, -(axis + t / 2), width - pad, t)
        return Laid(width, up + num.ascent, down + den.descent, items)
    }

    private fun scripts(s: MathNode.Scripts, c: Ctx): Laid {
        val base = lay(s.base, c)
        val sc = scripted(c)
        val sub = s.sub?.let { lay(it, sc) }
        val sup = s.sup?.let { lay(it, sc) }
        var supUp = max(c.em(0.4), base.ascent - c.em(0.25))
        var subDown = max(c.em(0.2), base.descent + c.em(0.1))
        if (sup != null) supUp = max(supUp, sup.descent + c.em(0.25))
        if (sub != null) subDown = max(subDown, sub.ascent - c.em(0.4))
        if (sub != null && sup != null) {
            // Keep a gap of 0.2 em between the two scripts.
            val gap = (supUp - sup.descent) - (sub.ascent - subDown)
            if (gap < c.em(0.2)) subDown += c.em(0.2) - gap
        }
        val italicKern = if (base.italic) c.em(0.08) else 0.0
        val items = ArrayList<MathItem>(base.items)
        sup?.let { items += it.moved(base.width + italicKern, -supUp) }
        sub?.let { items += it.moved(base.width, subDown) }
        val width = base.width + max((sup?.width ?: 0.0) + italicKern, sub?.width ?: 0.0) + c.em(0.05)
        val ascent = max(base.ascent, sup?.let { supUp + it.ascent } ?: 0.0)
        val descent = max(base.descent, sub?.let { subDown + it.descent } ?: 0.0)
        return Laid(width, ascent, descent, items)
    }

    private fun underOver(u: MathNode.UnderOver, c: Ctx): Laid {
        val baseToken = u.base as? MathNode.Token
        val largeOp = baseToken != null && baseToken.kind == MathTokenKind.OPERATOR &&
            (baseToken.largeOp == true || (baseToken.text.length == 1 && baseToken.text[0] in LARGE) || baseToken.text in setOf("lim", "max", "min", "sup", "inf"))
        // Outside display style, the limits of a large operator move to script places (MathML 3, 3.4.4.2).
        if (largeOp && !c.display && baseToken?.movableLimits != false) return scripts(MathNode.Scripts(u.base, u.under, u.over), c)
        val base = lay(u.base, c)
        val over = u.over?.let { lay(it, if (u.accent) c else scripted(c)) }
        val under = u.under?.let { lay(it, if (u.accentUnder) c else scripted(c)) }
        val gap = c.em(0.1)
        val width = maxOf(base.width, over?.width ?: 0.0, under?.width ?: 0.0)
        val items = ArrayList<MathItem>()
        items += base.moved((width - base.width) / 2, 0.0)
        var ascent = base.ascent
        var descent = base.descent
        if (over != null) {
            val bar = overBar(u.over, width, c)
            if (bar != null) {
                items += MathItem.Rule(0.0, -(base.ascent + gap + bar), width, bar)
                ascent = base.ascent + gap + bar
            } else {
                val up = base.ascent + (if (u.accent) c.em(0.05) else gap) + over.descent
                items += over.moved((width - over.width) / 2, -up)
                ascent = up + over.ascent
            }
        }
        if (under != null) {
            val bar = overBar(u.under, width, c)
            if (bar != null) {
                items += MathItem.Rule(0.0, base.descent + gap, width, bar)
                descent = base.descent + gap + bar
            } else {
                val down = base.descent + gap + under.ascent
                items += under.moved((width - under.width) / 2, down)
                descent = down + under.descent
            }
        }
        return Laid(width, ascent, descent, items)
    }

    /** The thickness of a bar that an over or under line stretches to, or null when [node] is not a line. */
    private fun overBar(node: MathNode?, width: Double, c: Ctx): Double? {
        val t = node as? MathNode.Token ?: return null
        return if (t.text.length == 1 && t.text[0] in "¯‾_―─" && width > 0) c.em(RULE) else null
    }

    /* ── radicals, tables, enclosures ──────────────────────────────────────── */

    private fun radical(r: MathNode.Radical, c: Ctx): Laid {
        val body = lay(r.body, c)
        val t = c.em(RULE)
        val gap = c.em(if (c.display) 0.2 else 0.12)
        val top = body.ascent + gap + t
        val bottom = body.descent + c.em(0.05)
        val signWidth = c.em(0.55) + (top + bottom) * 0.08
        val index = r.index?.let { lay(it, scripted(c, 2)) }
        // The index sits above the tick of the sign; a wide one moves the sign right.
        val indexShift = index?.let { max(0.0, it.width - signWidth * 0.55) } ?: 0.0
        val x0 = indexShift
        val items = ArrayList<MathItem>()
        val tickY = -(top - bottom) * 0.3
        items += MathItem.Stroke(
            listOf(
                x0 to tickY,
                x0 + signWidth * 0.18 to tickY - c.em(0.06),
                x0 + signWidth * 0.45 to bottom,
                x0 + signWidth to -top + t / 2,
                x0 + signWidth + body.width + c.em(0.08) to -top + t / 2,
            ),
            t,
        )
        items += body.moved(x0 + signWidth + c.em(0.04), 0.0)
        var ascent = top
        index?.let {
            val up = (top + bottom) * 0.35 + it.descent - bottom
            items += it.moved(x0 + signWidth * 0.55 - it.width, -up)
            ascent = max(ascent, up + it.ascent)
        }
        return Laid(x0 + signWidth + body.width + c.em(0.12), ascent, bottom + t, items)
    }

    private fun table(tb: MathNode.Table, c: Ctx): Laid {
        val cellCtx = Ctx(c.size, false, c.level, c.variant)
        val cells = tb.rows.map { row -> row.map { lay(it, cellCtx) } }
        if (cells.isEmpty()) return Laid(0.0, 0.0, 0.0, emptyList())
        val columns = cells.maxOf { it.size }
        val widths = DoubleArray(columns) { col -> cells.maxOf { row -> row.getOrNull(col)?.width ?: 0.0 } }
        val colGap = c.em(0.8)
        val rowGap = c.em(0.5)
        val heights = cells.map { row -> (row.maxOfOrNull { it.ascent } ?: 0.0) to (row.maxOfOrNull { it.descent } ?: 0.0) }
        val total = heights.sumOf { it.first + it.second } + rowGap * (cells.size - 1)
        val axis = c.em(AXIS)
        // The middle of the table sits on the axis.
        var y = -(axis + total / 2)
        val items = ArrayList<MathItem>()
        for ((r, row) in cells.withIndex()) {
            val (a, d) = heights[r]
            val baseline = y + a
            var x = 0.0
            for (col in 0 until columns) {
                val cell = row.getOrNull(col)
                if (cell != null) {
                    val align = tb.columnAlign.getOrNull(col) ?: tb.columnAlign.lastOrNull() ?: "center"
                    val dx = when (align) {
                        "left" -> 0.0
                        "right" -> widths[col] - cell.width
                        else -> (widths[col] - cell.width) / 2
                    }
                    items += cell.moved(x + dx, baseline)
                }
                x += widths[col] + if (col < columns - 1) colGap else 0.0
            }
            y = baseline + d + rowGap
        }
        val width = widths.sum() + colGap * (columns - 1)
        return Laid(width, axis + total / 2, total / 2 - axis, items)
    }

    private fun enclose(e: MathNode.Enclose, c: Ctx): Laid {
        val body = lay(e.child, c)
        val t = c.em(RULE)
        val pad = if ("box" in e.notations || "roundedbox" in e.notations) c.em(0.2) else 0.0
        val w = body.width + 2 * pad
        val top = body.ascent + pad
        val bottom = body.descent + pad
        val items = ArrayList<MathItem>(body.moved(pad, 0.0))
        if ("box" in e.notations || "roundedbox" in e.notations) {
            items += MathItem.Rule(0.0, -top, w, t)
            items += MathItem.Rule(0.0, bottom - t, w, t)
            items += MathItem.Rule(0.0, -top, t, top + bottom)
            items += MathItem.Rule(w - t, -top, t, top + bottom)
        }
        if ("horizontalstrike" in e.notations) items += MathItem.Rule(0.0, -c.em(AXIS) - t / 2, w, t)
        if ("verticalstrike" in e.notations) items += MathItem.Rule(w / 2 - t / 2, -top, t, top + bottom)
        if ("updiagonalstrike" in e.notations) items += MathItem.Stroke(listOf(0.0 to bottom, w to -top), t)
        if ("downdiagonalstrike" in e.notations) items += MathItem.Stroke(listOf(0.0 to -top, w to bottom), t)
        return Laid(w, top, bottom, items)
    }
}

/** A formula as plain text, for the reading order and for search: `x=(-b±√(b²-4ac))/(2a)` style. */
internal object MathLinear {
    fun of(node: MathNode): String = when (node) {
        is MathNode.Token -> node.text
        is MathNode.Space -> " "
        is MathNode.Row -> node.children.joinToString("") { of(it) }
        is MathNode.Fraction -> "${group(node.numerator)}/${group(node.denominator)}"
        is MathNode.Scripts -> of(node.base) + (node.sub?.let { "_" + group(it) } ?: "") + (node.sup?.let { "^" + group(it) } ?: "")
        is MathNode.UnderOver -> of(node.base) + (node.under?.let { "_" + group(it) } ?: "") + (node.over?.let { "^" + group(it) } ?: "")
        is MathNode.Radical -> (node.index?.let { "root " + group(it) + " of " } ?: "√") + group(node.body)
        is MathNode.Table -> node.rows.joinToString("; ") { row -> row.joinToString(", ") { of(it) } }
        is MathNode.Style -> of(node.child)
        is MathNode.Phantom -> ""
        is MathNode.Enclose -> of(node.child)
    }

    private fun group(node: MathNode): String {
        val s = of(node)
        return if (codePointsOf(s).size <= 1) s else "($s)"
    }
}
