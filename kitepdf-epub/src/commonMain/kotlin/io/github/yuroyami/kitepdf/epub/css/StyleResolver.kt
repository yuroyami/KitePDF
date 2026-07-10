package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.epub.HtmlNode
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * The cascade. Given the UA sheet plus the document's author CSS, computes a
 * [ComputedStyle] for each element: match rules, pick a winner per property by
 * (origin/importance, specificity, source order), apply onto a style seeded with
 * the parent's inherited values, resolving lengths against the element's own
 * font size (so `em` and `%` mean the right thing).
 *
 * Cascade precedence (low→high): UA normal < author normal < author important <
 * UA important. Inline `style=""` counts as author CSS with above-selector
 * specificity.
 */
internal class StyleResolver(
    authorRules: List<StyleRule>,
    private val rootFontSizePt: Double,
    private val refWidthPt: Double,
    private val baseDirection: Direction = Direction.LTR,
) {
    private val rules: List<StyleRule> = UaStylesheet.rules + authorRules

    fun initial(): ComputedStyle = ComputedStyle.initial(rootFontSizePt, direction = baseDirection)

    /** [ancestors]: immediate parent first, outward to the root. [parent] = its computed style. */
    fun compute(el: HtmlNode.Element, ancestors: List<HtmlNode.Element>, parent: ComputedStyle): ComputedStyle {
        val bestWeight = HashMap<String, Long>()
        val value = HashMap<String, String>()
        fun offer(prop: String, v: String, weight: Long) {
            val prev = bestWeight[prop]
            if (prev == null || weight >= prev) { bestWeight[prop] = weight; value[prop] = v }
        }

        rules.forEachIndexed { order, rule ->
            var spec = -1
            // ::before/::after selectors style generated content ([computePseudo]),
            // never the element itself.
            for (sel in rule.selectors) {
                if (sel.pseudoElement == null && sel.matches(el, ancestors)) spec = maxOf(spec, sel.specificity)
            }
            if (spec < 0) return@forEachIndexed
            for (d in rule.declarations) offer(d.property, d.value, weight(rule.origin, d.important, spec, order))
        }
        el.attrs["style"]?.let { inline ->
            val decls = CssParser.parse("*{$inline}", Origin.INLINE).firstOrNull()?.declarations.orEmpty()
            for (d in decls) offer(d.property, d.value, weight(Origin.INLINE, d.important, INLINE_SPEC, rules.size + 1))
        }
        val cs = build(parent, value)
        // The HTML `dir` attribute wins over the CSS `direction` property.
        return when (el.attrs["dir"]?.lowercase()) {
            "rtl" -> cs.copy(direction = Direction.RTL)
            "ltr" -> cs.copy(direction = Direction.LTR)
            else -> cs
        }
    }

    /**
     * Generated content for [el]'s `::before`/`::after` ([side]), or null when
     * no rule with a usable `content:` applies. The pseudo's style inherits
     * from its originating element ([elementStyle]), per spec. A `counter()`,
     * `counters()` or `url()` content value makes the rule inert (we cannot
     * synthesize those), as do `none`/`normal`.
     */
    fun computePseudo(
        el: HtmlNode.Element,
        ancestors: List<HtmlNode.Element>,
        elementStyle: ComputedStyle,
        side: PseudoSide,
    ): PseudoContent? {
        val bestWeight = HashMap<String, Long>()
        val value = HashMap<String, String>()
        fun offer(prop: String, v: String, weight: Long) {
            val prev = bestWeight[prop]
            if (prev == null || weight >= prev) { bestWeight[prop] = weight; value[prop] = v }
        }
        rules.forEachIndexed { order, rule ->
            var spec = -1
            for (sel in rule.selectors) {
                if (sel.pseudoElement == side && sel.matches(el, ancestors)) spec = maxOf(spec, sel.specificity)
            }
            if (spec < 0) return@forEachIndexed
            for (d in rule.declarations) offer(d.property, d.value, weight(rule.origin, d.important, spec, order))
        }
        val raw = value.remove("content") ?: return null
        val content = parseContentValue(raw, el)?.takeIf { it.isNotEmpty() } ?: return null
        return PseudoContent(build(elementStyle, value), content)
    }

    /**
     * `content:` value → text: quoted strings (CSS escapes decoded) and
     * `attr(name)` concatenate; `none`/`normal` and anything with
     * `counter()`/`counters()`/`url()` return null (rule inert).
     */
    private fun parseContentValue(v: String, el: HtmlNode.Element): String? {
        val s = v.trim()
        val lower = s.lowercase()
        if (lower == "none" || lower == "normal") return null
        if ("counter(" in lower || "counters(" in lower || "url(" in lower) return null
        val sb = StringBuilder()
        var any = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < s.length && s[j] != c) { if (s[j] == '\\' && j + 1 < s.length) j++; j++ }
                    if (j >= s.length) return null // unterminated string
                    sb.append(cssUnescape(s.substring(i + 1, j))); any = true
                    i = j + 1
                }
                lower.startsWith("attr(", i) -> {
                    val close = s.indexOf(')', i)
                    if (close < 0) return null
                    val name = s.substring(i + 5, close).trim().trim('"', '\'').lowercase()
                    el.attrs[name]?.let(sb::append)
                    any = true
                    i = close + 1
                }
                else -> return null // unknown component: whole value inert
            }
        }
        return if (any) sb.toString() else null
    }

    /** Decode CSS string escapes: `\HHHHHH` (optional trailing space) and `\c` literals. */
    private fun cssUnescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            val d = s[i + 1]
            if (isHex(d)) {
                var j = i + 1
                val hex = StringBuilder()
                while (j < s.length && hex.length < 6 && isHex(s[j])) { hex.append(s[j]); j++ }
                if (j < s.length && s[j] == ' ') j++ // one terminating space is eaten
                val cp = hex.toString().toIntOrNull(16)
                if (cp != null && cp in 1..0x10FFFF) {
                    if (cp < 0x10000) sb.append(cp.toChar())
                    else {
                        val v = cp - 0x10000
                        sb.append(((v shr 10) + 0xD800).toChar()).append(((v and 0x3FF) + 0xDC00).toChar())
                    }
                }
                i = j
            } else {
                sb.append(d); i += 2
            }
        }
        return sb.toString()
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun build(parent: ComputedStyle, values: Map<String, String>): ComputedStyle {
        val b = Builder(parent)
        // font-size first: everything else's `em`/`%` resolves against the new size.
        values["font-size"]?.let { b.fontSizePt = resolveFontSize(it, parent.fontSizePt) }
        for ((prop, v) in values) {
            if (prop == "font-size") continue
            apply(b, prop, v)
        }
        return b.build()
    }

    private fun apply(b: Builder, prop: String, v: String) {
        fun len(ref: Double) = CssValues.length(v, b.fontSizePt, rootFontSizePt, ref)
        when (prop) {
            "display" -> parseDisplay(v)?.let { b.display = it }
            "font-weight" -> parseBold(v)?.let { b.bold = it }
            "font-style" -> b.italic = when (v.trim().lowercase()) {
                "italic", "oblique" -> true
                "normal" -> false
                else -> b.italic
            }
            "font-family" -> { b.fontFamily = parseFamily(v); b.fontFamilyName = firstSpecificFamily(v) }
            "color" -> CssValues.color(v)?.let { b.color = it }
            "background-color" -> b.backgroundColor = CssValues.color(v)
            "background" -> v.split(Regex("\\s+")).firstNotNullOfOrNull { CssValues.color(it) }?.let { b.backgroundColor = it }
            "text-align" -> parseAlign(v)?.let { b.textAlign = it }
            "text-indent" -> len(refWidthPt)?.let { b.textIndentPt = it }
            "line-height" -> resolveLineHeight(b, v)
            "margin-top" -> len(refWidthPt)?.let { b.marginTop = it }
            "margin-bottom" -> len(refWidthPt)?.let { b.marginBottom = it }
            "margin-left" -> if (v.trim().lowercase() == "auto") { b.marginLeftAuto = true; b.marginLeft = 0.0 } else len(refWidthPt)?.let { b.marginLeft = it; b.marginLeftAuto = false }
            "margin-right" -> if (v.trim().lowercase() == "auto") { b.marginRightAuto = true; b.marginRight = 0.0 } else len(refWidthPt)?.let { b.marginRight = it; b.marginRightAuto = false }
            "padding-top" -> len(refWidthPt)?.let { b.paddingTop = it }
            "padding-right" -> len(refWidthPt)?.let { b.paddingRight = it }
            "padding-bottom" -> len(refWidthPt)?.let { b.paddingBottom = it }
            "padding-left" -> len(refWidthPt)?.let { b.paddingLeft = it }
            "white-space" -> parseWhiteSpace(v)?.let { b.whiteSpace = it }
            "list-style-type" -> parseListType(v)?.let { b.listType = it }
            "vertical-align" -> when (v.trim().lowercase()) {
                "super" -> b.verticalAlign = CssVAlign.SUPER
                "sub" -> b.verticalAlign = CssVAlign.SUB
                "baseline", "middle", "top", "bottom" -> b.verticalAlign = CssVAlign.BASELINE
            }
            "text-decoration", "text-decoration-line" -> {
                val s = v.lowercase()
                if ("underline" in s) b.underline = true else if ("none" in s) b.underline = false
            }
            "border-top-width" -> borderW(b, v)?.let { b.borderTopW = it }
            "border-right-width" -> borderW(b, v)?.let { b.borderRightW = it }
            "border-bottom-width" -> borderW(b, v)?.let { b.borderBottomW = it }
            "border-left-width" -> borderW(b, v)?.let { b.borderLeftW = it }
            "border-top-style" -> b.borderTopVis = borderVisible(v)
            "border-right-style" -> b.borderRightVis = borderVisible(v)
            "border-bottom-style" -> b.borderBottomVis = borderVisible(v)
            "border-left-style" -> b.borderLeftVis = borderVisible(v)
            "border-top-color" -> CssValues.color(v)?.let { b.borderTopColor = it }
            "border-right-color" -> CssValues.color(v)?.let { b.borderRightColor = it }
            "border-bottom-color" -> CssValues.color(v)?.let { b.borderBottomColor = it }
            "border-left-color" -> CssValues.color(v)?.let { b.borderLeftColor = it }
            "width" -> b.widthPt = sizeValue(b, v, refWidthPt)
            "max-width" -> b.maxWidthPt = sizeValue(b, v, refWidthPt)
            "height" -> b.heightPt = if (v.trim().endsWith("%")) null else sizeValue(b, v, refWidthPt)
            "break-before", "page-break-before" -> b.breakBefore = forcesBreak(v)
            "break-after", "page-break-after" -> b.breakAfter = forcesBreak(v)
            "break-inside", "page-break-inside" -> b.breakInsideAvoid = v.trim().lowercase() == "avoid"
            "direction" -> when (v.trim().lowercase()) { "rtl" -> b.direction = Direction.RTL; "ltr" -> b.direction = Direction.LTR }
            "hyphens", "-webkit-hyphens", "-epub-hyphens" -> b.hyphensAuto = v.trim().lowercase() == "auto"
            "position" -> b.position = when (v.trim().lowercase()) {
                "absolute" -> CssPosition.ABSOLUTE; "fixed" -> CssPosition.FIXED
                "relative" -> CssPosition.RELATIVE; else -> CssPosition.STATIC
            }
            "left" -> b.leftPt = sizeValue(b, v, refWidthPt)
            "top" -> b.topPt = sizeValue(b, v, refWidthPt)
            "right" -> b.rightPt = sizeValue(b, v, refWidthPt)
            "bottom" -> b.bottomPt = sizeValue(b, v, refWidthPt)
            "object-fit" -> b.objectFit = when (v.trim().lowercase()) {
                "contain" -> ObjectFit.CONTAIN; "cover" -> ObjectFit.COVER; else -> ObjectFit.FILL
            }
            "writing-mode", "-webkit-writing-mode", "-epub-writing-mode" -> b.writingMode = when (v.trim().lowercase()) {
                "vertical-rl", "tb-rl", "tb" -> WritingMode.VERTICAL_RL
                "vertical-lr" -> WritingMode.VERTICAL_LR
                else -> WritingMode.HORIZONTAL
            }
            "text-transform" -> b.textTransform = when (v.trim().lowercase()) {
                "uppercase" -> TextTransform.UPPERCASE
                "lowercase" -> TextTransform.LOWERCASE
                "capitalize" -> TextTransform.CAPITALIZE
                "none" -> TextTransform.NONE
                else -> b.textTransform
            }
            "letter-spacing" -> b.letterSpacingPt =
                if (v.trim().lowercase() == "normal") 0.0 else len(b.fontSizePt) ?: b.letterSpacingPt
            "word-spacing" -> b.wordSpacingPt =
                if (v.trim().lowercase() == "normal") 0.0 else len(b.fontSizePt) ?: b.wordSpacingPt
            "font-variant", "font-variant-caps" -> when (v.trim().lowercase()) {
                "small-caps" -> b.smallCaps = true
                "normal", "none" -> b.smallCaps = false
            }
        }
    }

    private fun forcesBreak(v: String): Boolean =
        v.trim().lowercase() in setOf("always", "page", "left", "right", "recto", "verso")

    private fun borderW(b: Builder, v: String): Double? = when (v.trim().lowercase()) {
        "thin" -> 0.75
        "medium" -> 2.25
        "thick" -> 3.75
        else -> CssValues.length(v, b.fontSizePt, rootFontSizePt, refWidthPt)
    }

    private fun borderVisible(v: String): Boolean = v.trim().lowercase().let { it != "none" && it != "hidden" }

    private fun sizeValue(b: Builder, v: String, ref: Double): Double? = when (v.trim().lowercase()) {
        "auto", "none", "inherit" -> null
        else -> CssValues.length(v, b.fontSizePt, rootFontSizePt, ref)
    }

    private fun resolveFontSize(v: String, parentPt: Double): Double {
        val s = v.trim().lowercase()
        if (s == "inherit") return parentPt
        CssValues.fontSizeKeyword(s, parentPt, rootFontSizePt)?.let { return it }
        CssValues.length(s, parentPt, rootFontSizePt, parentPt)?.let { return it }
        return parentPt
    }

    private fun resolveLineHeight(b: Builder, v: String) {
        val s = v.trim().lowercase()
        when {
            s == "normal" -> b.lineHeightPt = null
            s.toDoubleOrNull() != null -> b.lineHeightPt = s.toDouble() * b.fontSizePt // unitless multiplier
            else -> CssValues.length(s, b.fontSizePt, rootFontSizePt, b.fontSizePt)?.let { b.lineHeightPt = it }
        }
    }

    private fun weight(origin: Origin, important: Boolean, spec: Int, order: Int): Long {
        val rank = when {
            origin == Origin.UA && !important -> 0
            origin != Origin.UA && !important -> 1
            origin != Origin.UA && important -> 2
            else -> 3
        }
        return (rank.toLong() shl 56) or ((spec.toLong() and 0xFFFFFF) shl 24) or (order.toLong() and 0xFFFFFF)
    }

    private fun parseDisplay(v: String): Display? = when (v.trim().lowercase()) {
        "none" -> Display.NONE
        "inline" -> Display.INLINE
        "inline-block" -> Display.INLINE_BLOCK
        "list-item" -> Display.LIST_ITEM
        "block", "flex", "grid", "flow-root", "table-caption" -> Display.BLOCK
        "table", "inline-table" -> Display.TABLE
        "table-row" -> Display.TABLE_ROW
        "table-cell" -> Display.TABLE_CELL
        "table-row-group", "table-header-group", "table-footer-group" -> Display.TABLE_ROW_GROUP
        "table-column", "table-column-group" -> Display.NONE // structural, no rendered content
        else -> null
    }

    private fun parseBold(v: String): Boolean? = when (v.trim().lowercase()) {
        "bold", "bolder" -> true
        "normal", "lighter" -> false
        else -> v.trim().toIntOrNull()?.let { it >= 600 }
    }

    private val GENERIC_FAMILIES = setOf(
        "serif", "sans-serif", "monospace", "cursive", "fantasy",
        "system-ui", "ui-serif", "ui-sans-serif", "ui-monospace", "emoji", "math", "inherit", "initial",
    )

    private fun firstSpecificFamily(v: String): String? {
        for (raw in v.split(',')) {
            val f = raw.trim().trim('"', '\'').lowercase()
            if (f.isNotEmpty() && f !in GENERIC_FAMILIES) return f
        }
        return null
    }

    private fun parseFamily(v: String): GenericFont {
        for (raw in v.split(',')) {
            val f = raw.trim().trim('"', '\'').lowercase()
            when {
                f.isEmpty() -> {}
                "mono" in f || "courier" in f || "consol" in f -> return GenericFont.MONO
                f == "sans-serif" || "sans" in f || "arial" in f || "helvetica" in f ||
                    "verdana" in f || "tahoma" in f || "segoe" in f || "calibri" in f || "gothic" in f -> return GenericFont.SANS
                f == "serif" || "serif" in f || "times" in f || "georgia" in f || "garamond" in f ||
                    "cursive" in f || "fantasy" in f -> return GenericFont.SERIF
            }
        }
        return GenericFont.SERIF
    }

    private fun parseAlign(v: String): TextAlign? = when (v.trim().lowercase()) {
        "left", "start" -> TextAlign.LEFT
        "right", "end" -> TextAlign.RIGHT
        "center" -> TextAlign.CENTER
        "justify" -> TextAlign.JUSTIFY
        else -> null
    }

    private fun parseWhiteSpace(v: String): WhiteSpaceMode? = when (v.trim().lowercase()) {
        "normal" -> WhiteSpaceMode.NORMAL
        "pre" -> WhiteSpaceMode.PRE
        "nowrap" -> WhiteSpaceMode.NOWRAP
        "pre-wrap" -> WhiteSpaceMode.PRE_WRAP
        "pre-line" -> WhiteSpaceMode.PRE_LINE
        else -> null
    }

    private fun parseListType(v: String): ListType? = when (v.trim().lowercase()) {
        "disc" -> ListType.DISC
        "circle" -> ListType.CIRCLE
        "square" -> ListType.SQUARE
        "decimal", "decimal-leading-zero" -> ListType.DECIMAL
        "lower-roman" -> ListType.LOWER_ROMAN
        "upper-roman" -> ListType.UPPER_ROMAN
        "lower-alpha", "lower-latin" -> ListType.LOWER_ALPHA
        "upper-alpha", "upper-latin" -> ListType.UPPER_ALPHA
        "none" -> ListType.NONE
        else -> null
    }

    /** Mutable working style: inherited fields seeded from the parent, the rest initial. */
    private inner class Builder(parent: ComputedStyle) {
        var fontSizePt = parent.fontSizePt
        var bold = parent.bold
        var italic = parent.italic
        var fontFamily = parent.fontFamily
        var color = parent.color
        var textAlign = parent.textAlign
        var textIndentPt = parent.textIndentPt
        var lineHeightPt = parent.lineHeightPt
        var whiteSpace = parent.whiteSpace
        var listType = parent.listType
        var underline = parent.underline
        // Non-inherited → initial values.
        var display = Display.INLINE
        var backgroundColor: RgbColor? = null
        var marginTop = 0.0; var marginRight = 0.0; var marginBottom = 0.0; var marginLeft = 0.0
        var paddingTop = 0.0; var paddingRight = 0.0; var paddingBottom = 0.0; var paddingLeft = 0.0
        var verticalAlign = CssVAlign.BASELINE
        // Border width defaults to `medium`; it only occupies space when its style is visible.
        var borderTopW = 2.25; var borderRightW = 2.25; var borderBottomW = 2.25; var borderLeftW = 2.25
        var borderTopVis = false; var borderRightVis = false; var borderBottomVis = false; var borderLeftVis = false
        var borderTopColor: RgbColor? = null; var borderRightColor: RgbColor? = null
        var borderBottomColor: RgbColor? = null; var borderLeftColor: RgbColor? = null
        var widthPt: Double? = null; var heightPt: Double? = null; var maxWidthPt: Double? = null
        var breakBefore = false; var breakAfter = false; var breakInsideAvoid = false
        var marginLeftAuto = false; var marginRightAuto = false
        var fontFamilyName = parent.fontFamilyName
        var direction = parent.direction
        var hyphensAuto = parent.hyphensAuto
        var position = CssPosition.STATIC // not inherited
        var leftPt: Double? = null; var topPt: Double? = null; var rightPt: Double? = null; var bottomPt: Double? = null
        var objectFit = ObjectFit.FILL // not inherited
        var writingMode = parent.writingMode // inherited
        var textTransform = parent.textTransform // inherited
        var letterSpacingPt = parent.letterSpacingPt // inherited
        var wordSpacingPt = parent.wordSpacingPt // inherited
        var smallCaps = parent.smallCaps // inherited

        fun build() = ComputedStyle(
            display, fontSizePt, bold, italic, fontFamily, color, backgroundColor,
            textAlign, textIndentPt, lineHeightPt,
            marginTop, marginRight, marginBottom, marginLeft,
            paddingTop, paddingRight, paddingBottom, paddingLeft,
            whiteSpace, listType, verticalAlign, underline,
            Edge(borderTopW, borderTopColor ?: color, borderTopVis),
            Edge(borderRightW, borderRightColor ?: color, borderRightVis),
            Edge(borderBottomW, borderBottomColor ?: color, borderBottomVis),
            Edge(borderLeftW, borderLeftColor ?: color, borderLeftVis),
            widthPt, heightPt, maxWidthPt,
            breakBefore, breakAfter, breakInsideAvoid,
            marginLeftAuto, marginRightAuto,
            fontFamilyName,
            direction,
            hyphensAuto,
            position, leftPt, topPt, rightPt, bottomPt, objectFit, writingMode,
            textTransform, letterSpacingPt, wordSpacingPt, smallCaps,
        )
    }

    private companion object {
        const val INLINE_SPEC = 0xFFFFFF
    }
}

/** A resolved `::before`/`::after`: its computed style plus the text to inject. */
internal class PseudoContent(val style: ComputedStyle, val text: String)
