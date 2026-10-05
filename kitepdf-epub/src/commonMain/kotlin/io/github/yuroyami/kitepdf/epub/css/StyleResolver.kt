package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.render.RgbColor

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
    /** Percentage `height`/`min-height`/`max-height` resolve against this (the page content height); 0 disables them. */
    private val refHeightPt: Double = 0.0,
    /** Reader-app override rules ([Origin.READER]); they outrank author-important. */
    readerRules: List<StyleRule> = emptyList(),
    /**
     * False drops publisher CSS entirely: author rules AND inline `style=""`
     * attributes are ignored, leaving UA + reader rules only (the reader
     * setting `usePublisherCss = false`).
     */
    private val useAuthorCss: Boolean = true,
) {
    private val rules: List<StyleRule> =
        UaStylesheet.rules + (if (useAuthorCss) authorRules else emptyList()) + readerRules

    fun initial(): ComputedStyle = ComputedStyle.initial(rootFontSizePt, direction = baseDirection)

    /**
     * The style of an anonymous box with [display] inside a box styled [parent]: inherited
     * properties come from [parent], the others take their initial values (CSS 2.1, 17.2.1).
     */
    fun anonymous(parent: ComputedStyle, display: Display): ComputedStyle = build(parent, emptyList()).copy(display = display)

    /** [ancestors]: immediate parent first, outward to the root. [parent] = its computed style. */
    fun compute(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>, parent: ComputedStyle): ComputedStyle {
        val offered = ArrayList<Offered>()
        fun offer(prop: String, v: String, weight: Long) { offered.add(Offered(prop, v, weight)) }

        presentationalHints(el, ancestors, ::offer)
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
        if (useAuthorCss) el.attrs["style"]?.let { inline ->
            val decls = CssParser.parse("*{$inline}", Origin.INLINE).firstOrNull()?.declarations.orEmpty()
            for (d in decls) offer(d.property, d.value, weight(Origin.INLINE, d.important, INLINE_SPEC, rules.size + 1))
        }
        val cs = build(parent, inCascadeOrder(offered))
        // The HTML `dir` attribute wins over the CSS `direction` property.
        return when (el.attrs["dir"]?.lowercase()) {
            "rtl" -> cs.copy(direction = Direction.RTL)
            "ltr" -> cs.copy(direction = Direction.LTR)
            else -> cs
        }
    }

    /**
     * HTML presentational hints (`table[border]`, `[cellpadding]`,
     * `[cellspacing]`, `td/tr[valign]`) at the LOWEST cascade weight, so any
     * real CSS declaration overrides them. Common in pre-CSS-era books.
     */
    private fun presentationalHints(
        el: KiteXmlNode.Element,
        ancestors: List<KiteXmlNode.Element>,
        offer: (String, String, Long) -> Unit,
    ) {
        // Author origin at specificity 0, before every real author rule: hints
        // beat the UA sheet but lose to any author declaration (CSS 2.1 §6.4.4).
        val w = 1L shl 56
        fun borderAll(px: String) {
            for (side in listOf("top", "right", "bottom", "left")) {
                offer("border-$side-width", px, w)
                offer("border-$side-style", "solid", w)
            }
        }
        when (el.tag) {
            "table" -> {
                el.attrs["border"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { borderAll("${it}px") }
                el.attrs["cellspacing"]?.toDoubleOrNull()?.let { offer("border-spacing", "${it}px", w) }
            }
            "td", "th" -> {
                val table = ancestors.firstOrNull { it.tag == "table" }
                // table[border] gives every cell a thin border (HTML's rule).
                table?.attrs?.get("border")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { borderAll("1px") }
                table?.attrs?.get("cellpadding")?.toDoubleOrNull()?.let { p ->
                    for (side in listOf("top", "right", "bottom", "left")) offer("padding-$side", "${p}px", w)
                }
                (el.attrs["valign"] ?: ancestors.firstOrNull { it.tag == "tr" }?.attrs?.get("valign"))
                    ?.let { offer("vertical-align", it, w) }
            }
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
        el: KiteXmlNode.Element,
        ancestors: List<KiteXmlNode.Element>,
        elementStyle: ComputedStyle,
        side: PseudoSide,
    ): PseudoContent? {
        val offered = ArrayList<Offered>()
        rules.forEachIndexed { order, rule ->
            var spec = -1
            for (sel in rule.selectors) {
                if (sel.pseudoElement == side && sel.matches(el, ancestors)) spec = maxOf(spec, sel.specificity)
            }
            if (spec < 0) return@forEachIndexed
            for (d in rule.declarations) offered.add(Offered(d.property, d.value, weight(rule.origin, d.important, spec, order)))
        }
        val ordered = inCascadeOrder(offered)
        // The last `content` in cascade order wins even when it is one this resolver cannot draw,
        // such as a counter, which then makes the rule inert.
        val raw = ordered.lastOrNull { it.first == "content" }?.second ?: return null
        val parts = parseContentValue(raw) ?: return null
        val style = build(elementStyle, ordered.filter { it.first != "content" })
        val content = generate(parts, el, ancestors, style.quotes).takeIf { it.isNotEmpty() } ?: return null
        return PseudoContent(style, content)
    }

    /**
     * The rules of the document's own style sheets that declare a property SVG reads, with their
     * place in the cascade, for an SVG the document includes (#509). The user-agent sheet is
     * HTML's, and a reader's rules are for the book's text, so neither reaches the SVG.
     */
    private val svgRules: List<IndexedValue<StyleRule>> by lazy {
        rules.withIndex().filter { (_, rule) -> rule.origin == Origin.AUTHOR && rule.declarations.any { it.property in SVG_PROPERTIES } }
    }

    /**
     * What the document's style sheets declare for each element of [svg], an SVG it includes, as
     * a `style` attribute holds it, for the SVG to cascade with its own styles (EPUB 3.3, SVG
     * embedded by inclusion, #509). Null when no rule reaches it, as in most books. The outermost
     * `svg` is a box of this document's layout, which already applies its box properties.
     */
    fun svgHostStyle(svg: KiteXmlNode.Element): ((KiteXmlNode.Element) -> String?)? {
        if (svgRules.isEmpty()) return null
        val out = HashMap<KiteXmlNode.Element, String>()
        val pending = arrayListOf(svg)
        while (pending.isNotEmpty()) {
            val el = pending.removeAt(pending.lastIndex)
            svgDeclarations(el, root = el === svg)?.let { out[el] = it }
            for (child in el.children) if (child is KiteXmlNode.Element) pending.add(child)
        }
        return if (out.isEmpty()) null else out::get
    }

    private fun svgDeclarations(el: KiteXmlNode.Element, root: Boolean): String? {
        val bestWeight = HashMap<String, Long>()
        val winner = HashMap<String, Declaration>()
        for ((order, rule) in svgRules) {
            var spec = -1
            for (sel in rule.selectors) if (sel.pseudoElement == null && sel.matches(el)) spec = maxOf(spec, sel.specificity)
            if (spec < 0) continue
            for (d in rule.declarations) {
                if (d.property !in SVG_PROPERTIES || (root && d.property in BOX_PROPERTIES)) continue
                val w = weight(rule.origin, d.important, spec, order)
                val prev = bestWeight[d.property]
                if (prev == null || w >= prev) { bestWeight[d.property] = w; winner[d.property] = d }
            }
        }
        if (winner.isEmpty()) return null
        return winner.values.joinToString("; ") { "${it.property}: ${it.value}" + if (it.important) " !important" else "" }
    }

    /**
     * How deep in quotations the generated content so far stands: each `open-quote` goes one level
     * in and each `close-quote` one out (CSS Generated Content 3, 2.4). The box builder asks for
     * the generated content of each element once and in document order, and a resolver serves
     * one build, so the count runs through the document as the spec counts it (#511).
     */
    private var quoteDepth = 0

    /** The text of [parts], for [el] with the `quotes` value [quotes], moving [quoteDepth] as its quote marks do. */
    private fun generate(parts: List<ContentPart>, el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>, quotes: List<String>?): String {
        val marks by lazy { quotes ?: quoteMarks(contentLanguage(el, ancestors)) }
        fun mark(level: Int, close: Boolean): String {
            if (marks.size < 2) return ""
            return marks[2 * minOf(level, marks.size / 2 - 1) + if (close) 1 else 0]
        }
        val sb = StringBuilder()
        for (part in parts) when (part) {
            is ContentPart.Text -> sb.append(part.text)
            is ContentPart.Attr -> el.attrs[part.name]?.let(sb::append)
            ContentPart.OpenQuote -> sb.append(mark(quoteDepth++, close = false))
            ContentPart.NoOpenQuote -> quoteDepth++
            ContentPart.CloseQuote -> if (quoteDepth > 0) sb.append(mark(--quoteDepth, close = true))
            ContentPart.NoCloseQuote -> if (quoteDepth > 0) quoteDepth--
        }
        return sb.toString()
    }

    /** One component of a `content` value. */
    private sealed class ContentPart {
        class Text(val text: String) : ContentPart()
        class Attr(val name: String) : ContentPart()
        object OpenQuote : ContentPart()
        object CloseQuote : ContentPart()
        object NoOpenQuote : ContentPart()
        object NoCloseQuote : ContentPart()
    }

    /**
     * `content:` value → its components: quoted strings (CSS escapes decoded), `attr(name)` and
     * the quote keywords; `none`/`normal` and anything with `counter()`/`counters()`/`url()`
     * return null (rule inert).
     */
    private fun parseContentValue(v: String): List<ContentPart>? {
        val s = v.trim()
        val lower = s.lowercase()
        if (lower == "none" || lower == "normal") return null
        if ("counter(" in lower || "counters(" in lower || "url(" in lower) return null
        val parts = ArrayList<ContentPart>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val keyword = QUOTE_KEYWORDS.entries.firstOrNull { (word, _) ->
                lower.startsWith(word, i) && (i + word.length == s.length || !isNameChar(s[i + word.length]))
            }
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < s.length && s[j] != c) { if (s[j] == '\\' && j + 1 < s.length) j++; j++ }
                    if (j >= s.length) return null // unterminated string
                    parts += ContentPart.Text(cssUnescape(s.substring(i + 1, j)))
                    i = j + 1
                }
                lower.startsWith("attr(", i) -> {
                    val close = s.indexOf(')', i)
                    if (close < 0) return null
                    parts += ContentPart.Attr(s.substring(i + 5, close).trim().trim('"', '\'').lowercase())
                    i = close + 1
                }
                keyword != null -> { parts += keyword.value; i += keyword.key.length }
                else -> return null // unknown component: whole value inert
            }
        }
        return parts.ifEmpty { null }
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'

    /**
     * A `quotes` value: `none`, `auto`, or pairs of strings, open then close for each level
     * (CSS Generated Content 3, 2.4). Anything else keeps [current].
     */
    private fun quotesValue(v: String, current: List<String>?): List<String>? {
        when (v.lowercase()) {
            "none" -> return emptyList()
            "auto", "initial" -> return null
            "inherit" -> return current
        }
        val marks = ArrayList<String>()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < v.length && v[j] != c) { if (v[j] == '\\' && j + 1 < v.length) j++; j++ }
                    if (j >= v.length) return current
                    marks += cssUnescape(v.substring(i + 1, j))
                    i = j + 1
                }
                else -> return current
            }
        }
        return if (marks.isNotEmpty() && marks.size % 2 == 0) marks else current
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

    /** A declaration that matches an element, with its place in the cascade. */
    private class Offered(val property: String, val value: String, val weight: Long)

    /**
     * Each declaration of [offered] as a property and its value, from the lowest cascade weight to
     * the highest and, at one weight, in the order they were written (#575). The sort is stable.
     */
    private fun inCascadeOrder(offered: List<Offered>): List<Pair<String, String>> =
        offered.sortedBy { it.weight }.map { it.property to it.value }

    /**
     * The style that [declarations], in cascade order, give an element whose parent is styled
     * [parent]. Each declaration applies in turn, so the last one wins, and one whose value this
     * resolver cannot read changes nothing and leaves the value before it, as CSS drops an invalid
     * declaration. A shorthand and its longhand meet in the same order (#575).
     */
    private fun build(parent: ComputedStyle, declarations: List<Pair<String, String>>): ComputedStyle {
        val b = Builder(parent)
        // font-size first: everything else's `em`/`%` resolves against the new size.
        for ((prop, v) in declarations) if (prop == "font-size") resolveFontSize(v, parent.fontSizePt)?.let { b.fontSizePt = it }
        for ((prop, v) in declarations) if (prop != "font-size") apply(b, prop, v)
        return b.build()
    }

    /** The transform parser, with lengths in this element's font. */
    private fun transforms(b: Builder) = TransformParser { CssValues.length(it, b.fontSizePt, rootFontSizePt, 0.0) }

    /** The background parser, with lengths in this element's font. */
    private fun backgrounds(b: Builder) = BackgroundParser { CssValues.length(it, b.fontSizePt, rootFontSizePt, 0.0) }

    /**
     * The layer part of the `background` shorthand: for each layer, its image, position, size
     * after a slash and repeat (#503). As in CSS, the shorthand resets what it does not name
     * (CSS Backgrounds 3, 3.10).
     */
    private fun backgroundShorthand(b: Builder, v: String) {
        val parser = backgrounds(b)
        val images = ArrayList<CssBackgroundImage?>()
        val sizes = ArrayList<CssBackgroundSize>()
        val xs = ArrayList<CssOffset>()
        val ys = ArrayList<CssOffset>()
        val repeats = ArrayList<Pair<Boolean, Boolean>>()
        for (layer in CssParser.splitTopLevel(v, ',').map { it.trim() }) {
            var image: CssBackgroundImage? = null
            val position = ArrayList<String>()
            val size = ArrayList<String>()
            var inSize = false
            var repeat: Pair<Boolean, Boolean>? = null
            for (token in CssParser.splitTopLevel(layer.replace(WHITESPACE, " "), ' ').filter { it.isNotBlank() }) {
                val lower = token.trim().lowercase()
                if (lower.startsWith("url(") || lower.startsWith("linear-gradient(")) {
                    image = parser.image(token)
                    continue
                }
                // A slash starts the size, with or without spaces around it.
                for ((i, part) in lower.split('/').withIndex()) {
                    if (i > 0) inSize = true
                    if (part.isEmpty()) continue
                    when {
                        parser.repeat(part) != null -> repeat = parser.repeat(part)
                        part == "cover" || part == "contain" || part == "auto" -> size += part
                        part in POSITION_WORDS || parser.offset(part) != null -> if (inSize) size += part else position += part
                    }
                }
            }
            images += image
            sizes += size.takeIf { it.isNotEmpty() }?.let { parser.size(it.joinToString(" ")) } ?: CssBackgroundSize()
            val (x, y) = position.takeIf { it.isNotEmpty() }?.let { parser.position(it.joinToString(" ")) } ?: (CssOffset.ZERO to CssOffset.ZERO)
            xs += x
            ys += y
            repeats += repeat ?: (true to true)
        }
        // A value with no layer at all still leaves one of each, so a later longhand finds a value for its layers.
        b.bgImages = images
        b.bgSizes = sizes.ifEmpty { listOf(CssBackgroundSize()) }
        b.bgXs = xs.ifEmpty { listOf(CssOffset.ZERO) }
        b.bgYs = ys.ifEmpty { listOf(CssOffset.ZERO) }
        b.bgRepeats = repeats.ifEmpty { listOf(true to true) }
    }

    /** One radius: a length in points, or a percentage of the box's side, or null when it is neither. */
    private fun radiusValue(b: Builder, raw: String): CssRadius? {
        val t = raw.trim()
        if (t.endsWith('%')) return t.dropLast(1).trim().toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { CssRadius(it / 100.0, percent = true) }
        return CssValues.length(t, b.fontSizePt, rootFontSizePt, 0.0)?.takeIf { it >= 0.0 }?.let { CssRadius(it, percent = false) }
    }

    /** A `border-*-radius` longhand: one radius, or a horizontal and a vertical one. */
    private fun cornerValue(b: Builder, v: String): Pair<CssRadius, CssRadius>? {
        val parts = v.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size !in 1..2) return null
        val rx = radiusValue(b, parts[0]) ?: return null
        val ry = parts.getOrNull(1)?.let { radiusValue(b, it) ?: return null } ?: rx
        return rx to ry
    }

    /**
     * The `border-radius` shorthand: one to four horizontal radii for top-left, top-right,
     * bottom-right and bottom-left, then after a slash the vertical ones (CSS Backgrounds 3, 5.1).
     */
    private fun radiiValue(b: Builder, v: String): CornerRadii? {
        fun corners(part: String): List<CssRadius>? {
            val values = part.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { radiusValue(b, it) ?: return null }
            return when (values.size) {
                1 -> List(4) { values[0] }
                2 -> listOf(values[0], values[1], values[0], values[1])
                3 -> listOf(values[0], values[1], values[2], values[1])
                4 -> values
                else -> null
            }
        }
        val halves = v.split('/')
        if (halves.size > 2) return null
        val x = corners(halves[0]) ?: return null
        val y = halves.getOrNull(1)?.let { corners(it) ?: return null } ?: x
        return CornerRadii(x, y)
    }

    /**
     * The `box-shadow` list: each shadow is two to four lengths, a colour and `inset`, in any
     * order of the colour and the keyword (CSS Backgrounds 3, 7.1). Null when a shadow is not valid.
     */
    private fun shadowsValue(b: Builder, v: String): List<BoxShadow>? {
        if (v.trim().lowercase() == "none") return emptyList()
        return CssParser.splitTopLevel(v, ',').map { part ->
            var inset = false
            var color: io.github.yuroyami.kitepdf.core.render.RgbColor? = null
            var alpha = 1.0
            val lengths = ArrayList<Double>()
            for (token in CssParser.splitTopLevel(part.trim().replace(WHITESPACE, " "), ' ')) {
                val t = token.trim()
                if (t.isEmpty()) continue
                val length = CssValues.length(t, b.fontSizePt, rootFontSizePt, 0.0)
                when {
                    t.equals("inset", ignoreCase = true) -> inset = true
                    length != null && lengths.size < 4 -> lengths += length
                    t.equals("currentcolor", ignoreCase = true) -> color = null
                    CssValues.alpha(t) != null -> {
                        color = CssValues.color(t)
                        alpha = CssValues.alpha(t) ?: 1.0
                    }
                    else -> return null
                }
            }
            if (lengths.size < 2) return null
            BoxShadow(lengths[0], lengths[1], lengths.getOrElse(2) { 0.0 }.coerceAtLeast(0.0), lengths.getOrElse(3) { 0.0 }, color, alpha, inset)
        }
    }

    /** A CSS `opacity`: a number or a percentage, clamped to 0..1, or null when it is neither. */
    private fun opacityValue(v: String): Double? {
        val t = v.trim()
        val n = if (t.endsWith('%')) t.dropLast(1).trim().toDoubleOrNull()?.div(100.0) else t.toDoubleOrNull()
        return n?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
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
            // `inherit` keeps the parent's family, which the builder already holds.
            "font-family" -> if (v.trim().lowercase() != "inherit") {
                b.fontFamily = parseFamily(v); b.fontFamilyNames = specificFamilies(v)
            }
            "color" -> CssValues.color(v)?.let { b.color = it }
            "background-color" -> b.backgroundColor = background(v)
            "background" -> {
                CssParser.splitTopLevel(v.trim().replace(WHITESPACE, " "), ' ')
                    .firstOrNull { CssValues.alpha(it) != null }?.let { b.backgroundColor = background(it) }
                backgroundShorthand(b, v)
            }
            "background-image" -> b.bgImages = backgrounds(b).images(v)
            "transform", "-webkit-transform" -> transforms(b).transform(v)?.let { b.transform = it.takeIf { list -> list.isNotEmpty() } }
            "transform-origin", "-webkit-transform-origin" -> transforms(b).origin(v, backgrounds(b))?.let { b.transformOrigin = it }
            "background-size" -> backgrounds(b).sizes(v)?.let { b.bgSizes = it }
            "background-position" -> backgrounds(b).positions(v)?.let { p -> b.bgXs = p.map { it.first }; b.bgYs = p.map { it.second } }
            "background-position-x" -> backgrounds(b).offsets(v)?.let { b.bgXs = it }
            "background-position-y" -> backgrounds(b).offsets(v)?.let { b.bgYs = it }
            "background-repeat" -> backgrounds(b).repeats(v)?.let { b.bgRepeats = it }
            "text-align" -> parseAlign(v)?.let { b.textAlign = it }
            "overflow-wrap", "word-wrap" -> when (v.trim().lowercase()) {
                "normal", "initial", "unset" -> b.overflowWrap = false
                "break-word", "anywhere" -> b.overflowWrap = true
            }
            "word-break", "-epub-word-break" -> when (v.trim().lowercase()) {
                "normal", "initial", "unset" -> b.wordBreak = WordBreak.NORMAL
                "break-all" -> b.wordBreak = WordBreak.BREAK_ALL
                "keep-all" -> b.wordBreak = WordBreak.KEEP_ALL
                "break-word" -> b.wordBreak = WordBreak.BREAK_WORD
            }
            "line-break", "-epub-line-break", "-webkit-line-break" -> when (v.trim().lowercase()) {
                "auto", "initial", "unset" -> b.lineBreak = LineBreak.AUTO
                "loose" -> b.lineBreak = LineBreak.LOOSE
                "normal" -> b.lineBreak = LineBreak.NORMAL
                "strict" -> b.lineBreak = LineBreak.STRICT
                "anywhere" -> b.lineBreak = LineBreak.ANYWHERE
            }
            // EPUB 3's own names: vertical-right and use-glyph-orientation for mixed, sideways-right for sideways.
            "text-orientation", "-epub-text-orientation", "-webkit-text-orientation" -> when (v.trim().lowercase()) {
                "mixed", "vertical-right", "use-glyph-orientation", "initial", "unset" -> b.textOrientation = TextOrientation.MIXED
                "upright" -> b.textOrientation = TextOrientation.UPRIGHT
                "sideways", "sideways-right" -> b.textOrientation = TextOrientation.SIDEWAYS
            }
            "text-align-last", "-epub-text-align-last" -> when (v.trim().lowercase()) {
                "auto", "initial", "unset" -> b.textAlignLast = null
                else -> parseAlign(v)?.let { b.textAlignLast = it }
            }
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
                "top" -> b.verticalAlign = CssVAlign.TOP
                "middle" -> b.verticalAlign = CssVAlign.MIDDLE
                "bottom" -> b.verticalAlign = CssVAlign.BOTTOM
                "baseline" -> b.verticalAlign = CssVAlign.BASELINE
            }
            // The element's own lines. A descendant cannot cancel lines an ancestor
            // propagates with `none` (CSS Text Decoration 3, 2.1); build() adds those.
            "text-decoration", "text-decoration-line" -> {
                val s = v.lowercase()
                b.ownUnderline = "underline" in s
                b.ownLineThrough = "line-through" in s
            }
            "text-decoration-color" -> b.decorationColor = CssValues.color(v)
            "border-top-width" -> borderW(b, v)?.let { b.borderTopW = it }
            "border-right-width" -> borderW(b, v)?.let { b.borderRightW = it }
            "border-bottom-width" -> borderW(b, v)?.let { b.borderBottomW = it }
            "border-left-width" -> borderW(b, v)?.let { b.borderLeftW = it }
            "border-top-style" -> borderStyle(v)?.let { b.borderTopStyle = it }
            "border-right-style" -> borderStyle(v)?.let { b.borderRightStyle = it }
            "border-bottom-style" -> borderStyle(v)?.let { b.borderBottomStyle = it }
            "border-left-style" -> borderStyle(v)?.let { b.borderLeftStyle = it }
            "border-top-color" -> CssValues.color(v)?.let { b.borderTopColor = it }
            "border-right-color" -> CssValues.color(v)?.let { b.borderRightColor = it }
            "border-bottom-color" -> CssValues.color(v)?.let { b.borderBottomColor = it }
            "border-left-color" -> CssValues.color(v)?.let { b.borderLeftColor = it }
            "width" -> b.widthPt = sizeValue(b, v, refWidthPt)
            "max-width" -> b.maxWidthPt = sizeValue(b, v, refWidthPt)
            "min-width" -> b.minWidthPt = sizeValue(b, v, refWidthPt)
            // Percentage heights resolve against the page CONTENT height (the
            // nearest definite vertical reference in a paginated reader).
            "height" -> b.heightPt = heightValue(b, v)
            "min-height" -> b.minHeightPt = heightValue(b, v)
            "max-height" -> b.maxHeightPt = heightValue(b, v)
            "break-before", "page-break-before" -> b.breakBefore = forcesBreak(v)
            "break-after", "page-break-after" -> b.breakAfter = forcesBreak(v)
            "break-inside", "page-break-inside" -> b.breakInsideAvoid = v.trim().lowercase() == "avoid"
            "direction" -> when (v.trim().lowercase()) { "rtl" -> b.direction = Direction.RTL; "ltr" -> b.direction = Direction.LTR }
            "hyphens", "-webkit-hyphens", "-epub-hyphens" -> b.hyphensAuto = v.trim().lowercase() == "auto"
            "float" -> b.cssFloat = when (v.trim().lowercase()) {
                "left" -> CssFloat.LEFT; "right" -> CssFloat.RIGHT; else -> CssFloat.NONE
            }
            "clear" -> b.clear = when (v.trim().lowercase()) {
                "left" -> CssClear.LEFT; "right" -> CssClear.RIGHT
                "both" -> CssClear.BOTH; else -> CssClear.NONE
            }
            "z-index" -> b.zIndex = v.trim().toIntOrNull()
            "opacity" -> opacityValue(v)?.let { b.opacity = it }
            "visibility" -> when (v.trim().lowercase()) {
                "hidden", "collapse" -> b.visible = false
                "visible" -> b.visible = true
            }
            "border-radius" -> radiiValue(b, v)?.let { b.radii = it }
            "border-top-left-radius" -> cornerValue(b, v)?.let { (rx, ry) -> b.radii = (b.radii ?: CornerRadii.ZERO).with(0, rx, ry) }
            "border-top-right-radius" -> cornerValue(b, v)?.let { (rx, ry) -> b.radii = (b.radii ?: CornerRadii.ZERO).with(1, rx, ry) }
            "border-bottom-right-radius" -> cornerValue(b, v)?.let { (rx, ry) -> b.radii = (b.radii ?: CornerRadii.ZERO).with(2, rx, ry) }
            "border-bottom-left-radius" -> cornerValue(b, v)?.let { (rx, ry) -> b.radii = (b.radii ?: CornerRadii.ZERO).with(3, rx, ry) }
            "box-shadow", "-webkit-box-shadow" -> shadowsValue(b, v)?.let { b.shadows = it }
            // A scroll container clips too, and a page cannot scroll, so every value but visible clips.
            "overflow", "overflow-x", "overflow-y" -> when (v.trim().lowercase().substringBefore(' ')) {
                "hidden", "clip", "scroll", "auto" -> b.clipsOverflow = true
                "visible" -> if (prop == "overflow") b.clipsOverflow = false
            }
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
            "quotes" -> b.quotes = quotesValue(v.trim(), b.quotes)
            "text-transform" -> parseTextTransform(v)?.let { (case, wide) -> b.textTransform = case; b.fullWidth = wide }
            "letter-spacing" -> b.letterSpacingPt =
                if (v.trim().lowercase() == "normal") 0.0 else len(b.fontSizePt) ?: b.letterSpacingPt
            "word-spacing" -> b.wordSpacingPt =
                if (v.trim().lowercase() == "normal") 0.0 else len(b.fontSizePt) ?: b.wordSpacingPt
            "font-variant", "font-variant-caps" -> when (v.trim().lowercase()) {
                "small-caps" -> b.smallCaps = true
                "normal", "none" -> b.smallCaps = false
            }
            "border-collapse" -> when (v.trim().lowercase()) {
                "collapse" -> b.borderCollapse = true
                "separate" -> b.borderCollapse = false
            }
            "border-spacing" -> len(b.fontSizePt)?.let { b.borderSpacingPt = it.coerceAtLeast(0.0) }
            "table-layout" -> b.tableLayoutFixed = v.trim().lowercase() == "fixed"
            else -> if (prop.removePrefix("-webkit-") in FlexValues.PROPERTIES) {
                FlexValues.apply(b.flex, prop, v) { CssValues.length(it, b.fontSizePt, rootFontSizePt, refWidthPt) }?.let { b.flex = it }
            } else if (prop.removePrefix("-webkit-") in COLUMN_PROPERTIES) {
                columnValue(b, prop.removePrefix("-webkit-"), v)
            } else if (prop in GridValues.PROPERTIES) {
                GridValues.apply(b.grid, prop, v) { CssValues.length(it, b.fontSizePt, rootFontSizePt, refWidthPt) }?.let { b.grid = it }
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

    /** A `border-style` value, or null for one that is not a style, which leaves the declaration out. */
    private fun borderStyle(v: String): BorderStyle? = when (v.trim().lowercase()) {
        // `border-style` is not inherited, so `unset` means the initial value.
        "none", "initial", "unset" -> BorderStyle.NONE
        "hidden" -> BorderStyle.HIDDEN
        "solid" -> BorderStyle.SOLID
        "double" -> BorderStyle.DOUBLE
        "dashed" -> BorderStyle.DASHED
        "dotted" -> BorderStyle.DOTTED
        "ridge" -> BorderStyle.RIDGE
        "outset" -> BorderStyle.OUTSET
        "groove" -> BorderStyle.GROOVE
        "inset" -> BorderStyle.INSET
        else -> null
    }

    /** The multi-column properties (CSS Multi-column Layout 1, #34). A value that is not valid changes nothing. */
    private fun columnValue(b: Builder, prop: String, v: String) {
        val t = v.trim().lowercase()
        fun count(w: String): Int? = w.toIntOrNull()?.takeIf { it >= 1 }
        fun width(w: String): Double? = CssValues.length(w, b.fontSizePt, rootFontSizePt, refWidthPt)?.takeIf { it > 0.0 && it.isFinite() }
        fun rule(w: String): Boolean {
            borderStyle(w)?.let { b.ruleStyle = it; return true }
            CssValues.color(w)?.let { b.ruleColor = it; return true }
            borderW(b, w)?.takeIf { it >= 0.0 }?.let { b.ruleWidth = it; return true }
            return false
        }
        when (prop) {
            "column-count" -> if (t == "auto") b.columnCount = null else count(t)?.let { b.columnCount = it }
            "column-width" -> if (t == "auto") b.columnWidth = null else width(t)?.let { b.columnWidth = it }
            "columns" -> {
                // A width and a count in either order, `auto` for either (2.3).
                var c: Int? = null
                var w: Double? = null
                for (word in t.split(WHITESPACE).filter { it.isNotEmpty() }) {
                    if (word == "auto") continue
                    count(word)?.let { c = it } ?: width(word)?.let { w = it } ?: return
                }
                b.columnCount = c
                b.columnWidth = w
            }
            "column-rule" -> {
                val words = t.split(WHITESPACE).filter { it.isNotEmpty() }
                b.ruleWidth = 2.25; b.ruleStyle = BorderStyle.NONE; b.ruleColor = null
                for (word in words) if (!rule(word)) return
            }
            "column-rule-width" -> borderW(b, t)?.takeIf { it >= 0.0 }?.let { b.ruleWidth = it }
            "column-rule-style" -> borderStyle(t)?.let { b.ruleStyle = it }
            "column-rule-color" -> CssValues.color(t)?.let { b.ruleColor = it }
            "column-span" -> when (t) {
                "all" -> b.columnSpanAll = true
                "none" -> b.columnSpanAll = false
            }
        }
    }

    private fun sizeValue(b: Builder, v: String, ref: Double): Double? = when (v.trim().lowercase()) {
        "auto", "none", "inherit" -> null
        else -> CssValues.length(v, b.fontSizePt, rootFontSizePt, ref)
    }

    /** Vertical size: percentages against [refHeightPt] (null when it is 0). */
    private fun heightValue(b: Builder, v: String): Double? {
        val s = v.trim()
        if (s.endsWith("%")) {
            if (refHeightPt <= 0.0) return null
            return s.dropLast(1).toDoubleOrNull()?.let { it / 100.0 * refHeightPt }
        }
        return sizeValue(b, s, refHeightPt)
    }

    /** The size [v] gives, or null for a value this resolver cannot read. */
    private fun resolveFontSize(v: String, parentPt: Double): Double? {
        val s = v.trim().lowercase()
        if (s == "inherit") return parentPt
        CssValues.fontSizeKeyword(s, parentPt, rootFontSizePt)?.let { return it }
        return CssValues.length(s, parentPt, rootFontSizePt, parentPt)
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
            origin == Origin.READER -> 3 // user preference: above author-important
            origin != Origin.UA && !important -> 1
            origin != Origin.UA && important -> 2
            else -> 4 // UA important stays the ceiling (CSS 2.1 §6.4.2)
        }
        return (rank.toLong() shl 56) or ((spec.toLong() and 0xFFFFFF) shl 24) or (order.toLong() and 0xFFFFFF)
    }

    private fun parseDisplay(v: String): Display? = when (v.trim().lowercase()) {
        "none" -> Display.NONE
        "inline" -> Display.INLINE
        "inline-block" -> Display.INLINE_BLOCK
        "list-item" -> Display.LIST_ITEM
        "flex", "-webkit-flex" -> Display.FLEX
        "grid" -> Display.GRID
        "block", "flow-root", "table-caption" -> Display.BLOCK
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

    private fun specificFamilies(v: String): List<String> {
        val names = ArrayList<String>()
        // CSS Fonts 4, section 5: unavailable families fall through in order;
        // a generic family resolves immediately, so names after it cannot win.
        for (raw in CssParser.splitTopLevel(v, ',')) {
            val token = raw.trim().lowercase()
            if (token in GENERIC_FAMILIES) break
            val family = token.trim('"', '\'')
            if (family.isNotEmpty()) names.add(family)
        }
        return names
    }

    /** A background colour with its alpha, or null for a transparent or unreadable one (#253). */
    private fun background(v: String): CssBackground? {
        val color = CssValues.color(v) ?: return null
        val alpha = CssValues.alpha(v) ?: 1.0
        return if (alpha > 0.0) CssBackground(color, alpha) else null
    }

    private fun parseFamily(v: String): GenericFont {
        // A quoted name is a family name even when it reads like a generic one.
        val families = CssParser.splitTopLevel(v, ',').map { it.trim() }.filter { it.isNotEmpty() }
        val generic = families.indexOfFirst { it.lowercase() in GENERIC_FAMILIES }
        // CSS Fonts 4, 4.2: the first generic family always resolves. system-ui is the
        // platform's interface face, a sans-serif on every major platform (#257).
        when (families.getOrNull(generic)?.lowercase()) {
            "serif", "ui-serif" -> return GenericFont.SERIF
            "sans-serif", "system-ui", "ui-sans-serif" -> return GenericFont.SANS
            "monospace", "ui-monospace" -> return GenericFont.MONO
        }
        // No face here for cursive, fantasy, emoji or math: the names before them are the best hint.
        for (raw in if (generic >= 0) families.subList(0, generic) else families) {
            val f = raw.trim('"', '\'').lowercase()
            when {
                "mono" in f || "courier" in f || "consol" in f -> return GenericFont.MONO
                "sans" in f || "arial" in f || "helvetica" in f ||
                    "verdana" in f || "tahoma" in f || "segoe" in f || "calibri" in f || "gothic" in f -> return GenericFont.SANS
                "serif" in f || "times" in f || "georgia" in f || "garamond" in f -> return GenericFont.SERIF
            }
        }
        return GenericFont.SERIF
    }

    private fun parseAlign(v: String): TextAlign? = when (v.trim().lowercase()) {
        "left" -> TextAlign.LEFT
        "right" -> TextAlign.RIGHT
        "start" -> TextAlign.START
        "end" -> TextAlign.END
        "center" -> TextAlign.CENTER
        "justify" -> TextAlign.JUSTIFY
        else -> null
    }

    /**
     * `text-transform`: a case, `full-width` (EPUB's `-epub-fullwidth`), both, or `none`, or null
     * for a value that holds another word (CSS Text 3, 2.1, #508). `full-size-kana` is read and
     * has no effect.
     */
    private fun parseTextTransform(v: String): Pair<TextTransform, Boolean>? {
        val words = v.trim().lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (words.singleOrNull() in setOf("none", "initial", "unset")) return TextTransform.NONE to false
        var case: TextTransform? = null
        var wide = false
        var kana = false
        for (w in words) when (w) {
            "uppercase", "lowercase", "capitalize" -> {
                if (case != null) return null
                case = when (w) { "uppercase" -> TextTransform.UPPERCASE; "lowercase" -> TextTransform.LOWERCASE; else -> TextTransform.CAPITALIZE }
            }
            "full-width", "-epub-fullwidth" -> { if (wide) return null; wide = true }
            "full-size-kana" -> { if (kana) return null; kana = true }
            else -> return null
        }
        return if (words.isEmpty()) null else (case ?: TextTransform.NONE) to wide
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
    private inner class Builder(private val parent: ComputedStyle) {
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
        // Non-inherited → initial values.
        var ownUnderline = false
        var ownLineThrough = false
        var decorationColor: RgbColor? = null
        var display = Display.INLINE
        var backgroundColor: CssBackground? = null
        var marginTop = 0.0; var marginRight = 0.0; var marginBottom = 0.0; var marginLeft = 0.0
        var paddingTop = 0.0; var paddingRight = 0.0; var paddingBottom = 0.0; var paddingLeft = 0.0
        var verticalAlign = CssVAlign.BASELINE
        // Border width defaults to `medium`; it only occupies space when its style is visible.
        var borderTopW = 2.25; var borderRightW = 2.25; var borderBottomW = 2.25; var borderLeftW = 2.25
        var borderTopStyle = BorderStyle.NONE; var borderRightStyle = BorderStyle.NONE
        var borderBottomStyle = BorderStyle.NONE; var borderLeftStyle = BorderStyle.NONE
        var borderTopColor: RgbColor? = null; var borderRightColor: RgbColor? = null
        var borderBottomColor: RgbColor? = null; var borderLeftColor: RgbColor? = null
        var widthPt: Double? = null; var heightPt: Double? = null; var maxWidthPt: Double? = null
        var breakBefore = false; var breakAfter = false; var breakInsideAvoid = false
        var marginLeftAuto = false; var marginRightAuto = false
        var fontFamilyNames = parent.fontFamilyNames
        var direction = parent.direction
        var hyphensAuto = parent.hyphensAuto
        var position = CssPosition.STATIC // not inherited
        var leftPt: Double? = null; var topPt: Double? = null; var rightPt: Double? = null; var bottomPt: Double? = null
        var objectFit = ObjectFit.FILL // not inherited
        var writingMode = parent.writingMode // inherited
        var textTransform = parent.textTransform // inherited
        var fullWidth = parent.fullWidth // inherited
        var letterSpacingPt = parent.letterSpacingPt // inherited
        var wordSpacingPt = parent.wordSpacingPt // inherited
        var smallCaps = parent.smallCaps // inherited
        var minWidthPt: Double? = null; var minHeightPt: Double? = null; var maxHeightPt: Double? = null
        var borderCollapse = parent.borderCollapse // inherited
        var borderSpacingPt = parent.borderSpacingPt // inherited
        var cssFloat = CssFloat.NONE // not inherited
        var clear = CssClear.NONE // not inherited
        var tableLayoutFixed = false // not inherited
        var zIndex: Int? = null // not inherited
        var opacity = 1.0 // not inherited
        var visible = parent.visible // inherited
        var clipsOverflow = false // not inherited
        var radii: CornerRadii? = null // not inherited
        var shadows: List<BoxShadow> = emptyList() // not inherited
        // Not inherited. Each list has a value for each layer of bgImages, and repeats when it is shorter (CSS Backgrounds 3, 3.1).
        var bgImages: List<CssBackgroundImage?> = emptyList()
        var bgSizes = listOf(CssBackgroundSize())
        var bgXs = listOf(CssOffset.ZERO)
        var bgYs = listOf(CssOffset.ZERO)
        var bgRepeats = listOf(true to true)
        var transform: List<CssTransform>? = null // not inherited
        var transformOrigin = CssOffset.HALF to CssOffset.HALF // not inherited
        var flex = FlexStyle() // not inherited
        var grid = GridStyle() // not inherited
        var columnCount: Int? = null // not inherited
        var columnWidth: Double? = null // not inherited
        var ruleWidth = 2.25 // medium; not inherited
        var ruleStyle = BorderStyle.NONE // not inherited
        var ruleColor: io.github.yuroyami.kitepdf.core.render.RgbColor? = null // currentColor; not inherited
        var columnSpanAll = false // not inherited
        var quotes = parent.quotes // inherited
        var textAlignLast = parent.textAlignLast // inherited
        var overflowWrap = parent.overflowWrap // inherited
        var wordBreak = parent.wordBreak // inherited
        var lineBreak = parent.lineBreak // inherited
        var textOrientation = parent.textOrientation // inherited

        fun build(): ComputedStyle {
            // CSS Flexible Box Layout 1, 4: an in-flow child of a flex container is a flex item. It is
            // blockified, and float does not apply to it (#33). A grid item is the same (CSS Grid 1, 6, #35).
            val flexItem = (parent.display == Display.FLEX || parent.display == Display.GRID) &&
                position != CssPosition.ABSOLUTE && position != CssPosition.FIXED
            if (flexItem) {
                cssFloat = CssFloat.NONE
                if (display == Display.INLINE || display == Display.INLINE_BLOCK) display = Display.BLOCK
            }
            val outOfFlow = position == CssPosition.ABSOLUTE || position == CssPosition.FIXED || cssFloat != CssFloat.NONE
            // CSS Text Decoration 3, 2.1: lines reach in-flow descendants only, never the
            // contents of an inline block or of a floated or positioned box (#265).
            val inherits = display != Display.INLINE_BLOCK && !outOfFlow
            val own = DecorationLine(decorationColor ?: color, fontSizePt, raised = verticalAlign != CssVAlign.BASELINE)
            val underline = if (ownUnderline) own else parent.underline.takeIf { inherits }
            val lineThrough = if (ownLineThrough) own else parent.lineThrough.takeIf { inherits }
            return ComputedStyle(
                // CSS 9.7: an out-of-flow box is blockified, which is how
                // `<img style="position:absolute">` gets a box of its own instead
                // of flowing on a line.
                if (display == Display.INLINE &&
                    (position == CssPosition.ABSOLUTE || position == CssPosition.FIXED)
                ) Display.BLOCK else display,
                fontSizePt, bold, italic, fontFamily, color, backgroundColor,
                textAlign, textIndentPt, lineHeightPt,
                marginTop, marginRight, marginBottom, marginLeft,
                paddingTop, paddingRight, paddingBottom, paddingLeft,
                whiteSpace, listType, verticalAlign, underline,
                Edge(borderTopW, borderTopColor ?: color, borderTopStyle),
                Edge(borderRightW, borderRightColor ?: color, borderRightStyle),
                Edge(borderBottomW, borderBottomColor ?: color, borderBottomStyle),
                Edge(borderLeftW, borderLeftColor ?: color, borderLeftStyle),
                widthPt, heightPt, maxWidthPt,
                breakBefore, breakAfter, breakInsideAvoid,
                marginLeftAuto, marginRightAuto,
                fontFamilyNames,
                direction,
                hyphensAuto,
                position, leftPt, topPt, rightPt, bottomPt, objectFit, writingMode,
                textTransform, letterSpacingPt, wordSpacingPt, smallCaps,
                minWidthPt, minHeightPt, maxHeightPt,
                borderCollapse, borderSpacingPt,
                cssFloat, clear, tableLayoutFixed, lineThrough, zIndex,
                opacity, visible, clipsOverflow, radii, shadows,
                bgImages.mapIndexedNotNull { i, image ->
                    image?.let {
                        val (repeatX, repeatY) = bgRepeats[i % bgRepeats.size]
                        CssBackgroundLayer(it, bgSizes[i % bgSizes.size], bgXs[i % bgXs.size], bgYs[i % bgYs.size], repeatX, repeatY)
                    }
                },
                transform, transformOrigin,
                flex, grid,
                Columns(
                    count = columnCount, width = columnWidth,
                    rule = Edge(ruleWidth, ruleColor ?: color, ruleStyle).takeIf { it.visible && ruleWidth > 0.0 },
                    spanAll = columnSpanAll,
                ),
                quotes = quotes,
                textAlignLast = textAlignLast,
                overflowWrap = overflowWrap,
                wordBreak = wordBreak,
                fullWidth = fullWidth,
                lineBreak = lineBreak,
                textOrientation = textOrientation,
            )
        }
    }

    private companion object {
        /** The properties an SVG reads from a style sheet, as `SvgStyles` in kitepdf-svg lists them. */
        val SVG_PROPERTIES = setOf(
            "color", "fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity",
            "fill-rule", "display", "visibility", "transform", "clip-path", "font-size", "font-family",
            "font-weight", "font-style", "text-anchor", "stroke-dasharray", "stroke-dashoffset",
            "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stop-color", "stop-opacity",
            "mask", "mask-type", "filter", "flood-color", "flood-opacity", "lighting-color",
            "color-interpolation-filters",
        )

        /** What the layout applies to the outermost `svg` as a box, so the SVG must not apply it again. */
        val BOX_PROPERTIES = setOf("display", "visibility", "opacity", "transform", "clip-path", "mask", "filter")

        val QUOTE_KEYWORDS = linkedMapOf(
            "open-quote" to ContentPart.OpenQuote, "close-quote" to ContentPart.CloseQuote,
            "no-open-quote" to ContentPart.NoOpenQuote, "no-close-quote" to ContentPart.NoCloseQuote,
        )
        val POSITION_WORDS = setOf("left", "right", "top", "bottom", "center")
        const val INLINE_SPEC = 0xFFFFFF
        val WHITESPACE = Regex("\\s+")

        /** The multi-column properties, read with or without the `-webkit-` prefix (#34). */
        val COLUMN_PROPERTIES = setOf(
            "column-count", "column-width", "columns", "column-rule", "column-rule-width", "column-rule-style",
            "column-rule-color", "column-span",
        )
    }
}

/** A resolved `::before`/`::after`: its computed style plus the text to inject. */
internal class PseudoContent(val style: ComputedStyle, val text: String)
