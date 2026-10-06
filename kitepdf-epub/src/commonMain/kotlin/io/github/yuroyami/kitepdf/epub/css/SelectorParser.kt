package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.kiteWarn

/**
 * The selector grammar of Selectors 4, 18, over the tokens of CSS Syntax 3, with the namespace
 * prefixes of [namespaces] and the default namespace under the empty prefix (CSS Namespaces, 3).
 *
 * A selector is valid or not as a browser has it: an unknown pseudo-class or pseudo-element, an
 * undeclared prefix or a stray token makes the whole list invalid, but for an argument of `:is()`
 * and `:where()`, which their forgiving lists drop. A list nested in functions beyond
 * [CssParser.MAX_NESTING] levels is invalid as a whole, checked before the parser goes deeper, so
 * a hostile selector cannot exhaust a thread's stack (#451).
 */
internal class SelectorParser(text: String, private val namespaces: Map<String, String>) {

    private val tokenizer = CssTokenizer(text)
    private val src = tokenizer.input
    private val tokens = tokenizer.tokens()

    /** For each opening token, the index of the token that closes it, or the end of the tokens. */
    private val closers = IntArray(tokens.size) { -1 }

    private var tooDeep = false

    /** Whether a `:target` was read, which makes the layout restyle a chapter when its target changes (#550). */
    private var usesTarget = false

    init {
        val stack = ArrayList<Int>()
        for (k in tokens.indices) {
            when (tokens[k].type) {
                CssToken.FUNCTION, CssToken.LEFT_PAREN, CssToken.LEFT_BRACKET, CssToken.LEFT_BRACE -> stack.add(k)
                CssToken.RIGHT_PAREN, CssToken.RIGHT_BRACKET, CssToken.RIGHT_BRACE -> {
                    val open = stack.lastOrNull()
                    if (open != null && closes(tokens[open].type, tokens[k].type)) { closers[open] = k; stack.removeAt(stack.size - 1) }
                }
            }
        }
        for (open in stack) closers[open] = tokens.size
    }

    private fun closes(open: Int, close: Int): Boolean = when (open) {
        CssToken.FUNCTION, CssToken.LEFT_PAREN -> close == CssToken.RIGHT_PAREN
        CssToken.LEFT_BRACKET -> close == CssToken.RIGHT_BRACKET
        else -> close == CssToken.RIGHT_BRACE
    }

    fun selectorList(): List<Selector>? {
        val list = list(0, tokens.size, Context.TOP, 0)
        return if (tooDeep) null else list
    }

    /** Where a selector sits, which decides what it may hold. */
    private class Context(val pseudoElements: Boolean, val inHas: Boolean, val forgiving: Boolean, val relative: Boolean) {
        companion object {
            val TOP = Context(pseudoElements = true, inHas = false, forgiving = false, relative = false)
        }
    }

    /** The comma separated list in tokens [start] to [end], dropping an invalid item when forgiving, or null. */
    private fun list(start: Int, end: Int, cx: Context, depth: Int): List<Selector>? {
        val out = ArrayList<Selector>()
        var from = start
        var k = start
        while (true) {
            if (k >= end || tokens[k].type == CssToken.COMMA) {
                val sel = complex(from, k, cx, depth)
                if (sel != null) out.add(sel) else if (!cx.forgiving) return null
                if (tooDeep) return null
                if (k >= end) break
                from = k + 1
                k = from
                continue
            }
            k = if (closers[k] >= 0) minOf(closers[k], end - 1) + 1 else k + 1
        }
        return out
    }

    /** One complex or relative selector in tokens [start] to [end], or null. */
    private fun complex(start: Int, end: Int, cx: Context, depth: Int): Selector? {
        var i = skipSpace(start, end)
        var last = end
        while (last > i && tokens[last - 1].type == CssToken.WHITESPACE) last--
        if (i >= last) return null
        var leading: Combinator? = null
        if (cx.relative) {
            leading = combinator(tokens[i])
            if (leading != null) i = skipSpace(i + 1, last) else leading = Combinator.DESCENDANT
        }
        val parts = ArrayList<SimpleSelector>()
        val combinators = ArrayList<Combinator>()
        while (true) {
            val compound = compound(i, last, cx, depth) ?: return null
            parts.add(compound.first)
            i = compound.second
            if (i >= last) break
            val space = tokens[i].type == CssToken.WHITESPACE
            i = skipSpace(i, last)
            val explicit = combinator(tokens[i])
            if (explicit != null) {
                i = skipSpace(i + 1, last)
                if (i >= last) return null
                combinators.add(explicit)
            } else if (space) {
                combinators.add(Combinator.DESCENDANT)
            } else {
                return null
            }
        }
        for (k in 0 until parts.size - 1) if (parts[k].pseudoElement != null || parts[k].otherPseudoElement != null) return null
        return Selector(parts, combinators, leading, usesTarget)
    }

    private fun combinator(t: CssToken): Combinator? = when {
        t.isDelim('>') -> Combinator.CHILD
        t.isDelim('+') -> Combinator.NEXT_SIBLING
        t.isDelim('~') -> Combinator.SUBSEQUENT_SIBLING
        else -> null
    }

    private fun skipSpace(from: Int, end: Int): Int {
        var k = from
        while (k < end && tokens[k].type == CssToken.WHITESPACE) k++
        return k
    }

    /** A compound selector from token [start], and the index past it, or null. */
    private fun compound(start: Int, end: Int, cx: Context, depth: Int): Pair<SimpleSelector, Int>? {
        var i = start
        var tag: String? = null
        var namespace: String? = null
        var typed = false
        typeName(i, end)?.let { (prefix, name, next) ->
            namespace = when (prefix) {
                null -> namespaces[""]
                "*" -> null
                "" -> ""
                else -> namespaces[prefix] ?: return null
            }
            tag = name.takeUnless { it == "*" }
            typed = true
            i = next
        }
        val conditions = ArrayList<Condition>()
        var side: PseudoSide? = null
        var other: String? = null
        while (i < end) {
            val t = tokens[i]
            val afterElement = side != null || other != null
            when {
                t.type == CssToken.HASH -> {
                    if (!t.flag || afterElement) return null
                    conditions.add(IdCondition(t.value))
                    i++
                }
                t.isDelim('.') -> {
                    if (afterElement || i + 1 >= end || tokens[i + 1].type != CssToken.IDENT) return null
                    conditions.add(ClassCondition(tokens[i + 1].value))
                    i += 2
                }
                t.type == CssToken.LEFT_BRACKET -> {
                    if (afterElement) return null
                    val close = closers[i]
                    conditions.add(attribute(i + 1, minOf(close, end)) ?: return null)
                    i = close + 1
                }
                t.type == CssToken.COLON -> {
                    if (i + 1 >= end) return null
                    val n = tokens[i + 1]
                    if (n.type == CssToken.COLON) {
                        if (!cx.pseudoElements || (afterElement && !(side != null && other == null))) return null
                        if (i + 2 >= end) return null
                        val e = tokens[i + 2]
                        val name = asciiLower(e.value)
                        when {
                            e.type == CssToken.IDENT && afterElement -> if (name == "marker") other = "marker" else return null
                            e.type == CssToken.IDENT && (name == "before" || name == "after") ->
                                side = if (name == "before") PseudoSide.BEFORE else PseudoSide.AFTER
                            e.type == CssToken.IDENT && (name in PSEUDO_ELEMENTS || name.startsWith("-webkit-")) -> other = name
                            e.type == CssToken.FUNCTION && name in FUNCTIONAL_PSEUDO_ELEMENTS && !afterElement -> {
                                other = name
                                i = closers[i + 2] - 2
                            }
                            else -> return null
                        }
                        i += 3
                    } else {
                        val name = asciiLower(n.value)
                        when (n.type) {
                            CssToken.IDENT -> {
                                if (afterElement && name !in AFTER_PSEUDO_ELEMENT) return null
                                if (name in LEGACY_PSEUDO_ELEMENTS) {
                                    if (!cx.pseudoElements || afterElement) return null
                                    when (name) {
                                        "before" -> side = PseudoSide.BEFORE
                                        "after" -> side = PseudoSide.AFTER
                                        else -> other = name
                                    }
                                } else {
                                    conditions.add(pseudoClass(name) ?: return null)
                                }
                                i += 2
                            }
                            CssToken.FUNCTION -> {
                                if (afterElement) return null
                                val close = closers[i + 1]
                                conditions.add(functional(name, i + 2, minOf(close, end), cx, depth) ?: return null)
                                if (tooDeep) return null
                                i = close + 1
                            }
                            else -> return null
                        }
                    }
                }
                else -> break
            }
        }
        if (!typed && conditions.isEmpty() && side == null && other == null) return null
        // A default namespace applies to a type selector, and to a compound without one only as `*` (CSS Namespaces, 3).
        if (!typed) namespace = namespaces[""]
        return SimpleSelector(tag, namespace, conditions, side, other) to i
    }

    /** A type or universal selector at [i], as its prefix (null when none is written), its name or `*`, and the index past it. */
    private fun typeName(i: Int, end: Int): Triple<String?, String, Int>? {
        fun name(k: Int): String? = tokens.getOrNull(k)?.takeIf { k < end }?.let {
            when {
                it.type == CssToken.IDENT -> it.value
                it.isDelim('*') -> "*"
                else -> null
            }
        }
        val first = name(i)
        val bar = i + (if (first != null) 1 else 0)
        if (bar < end && tokens[bar].isDelim('|')) {
            val local = name(bar + 1) ?: return null
            return Triple(first ?: "", local, bar + 2)
        }
        return first?.let { Triple(null, it, i + 1) }
    }

    /** The attribute selector in tokens [start] to [end], the inside of its brackets, or null. */
    private fun attribute(start: Int, end: Int): AttrCondition? {
        var i = skipSpace(start, end)
        if (i >= end) return null
        var prefix: String? = null
        val first = tokens[i]
        val local: String
        when {
            first.type == CssToken.IDENT && i + 2 < end && tokens[i + 1].isDelim('|') && tokens[i + 2].type == CssToken.IDENT -> {
                prefix = first.value; local = tokens[i + 2].value; i += 3
            }
            first.isDelim('*') && i + 2 < end && tokens[i + 1].isDelim('|') && tokens[i + 2].type == CssToken.IDENT -> {
                prefix = "*"; local = tokens[i + 2].value; i += 3
            }
            first.isDelim('|') && i + 1 < end && tokens[i + 1].type == CssToken.IDENT -> {
                prefix = ""; local = tokens[i + 1].value; i += 2
            }
            first.type == CssToken.IDENT -> { local = first.value; i++ }
            else -> return null
        }
        val namespace = when (prefix) {
            null, "" -> ""
            "*" -> null
            else -> namespaces[prefix] ?: return null
        }
        i = skipSpace(i, end)
        if (i >= end) return AttrCondition(namespace, local, '0', "", '0')
        val op: Char
        val t = tokens[i]
        when {
            t.isDelim('=') -> { op = '='; i++ }
            t.type == CssToken.DELIM && t.value.length == 1 && t.value[0] in "~|^$*" && i + 1 < end && tokens[i + 1].isDelim('=') -> {
                op = t.value[0]; i += 2
            }
            else -> return null
        }
        i = skipSpace(i, end)
        val v = tokens.getOrNull(i)?.takeIf { i < end && (it.type == CssToken.IDENT || it.type == CssToken.STRING) } ?: return null
        i = skipSpace(i + 1, end)
        var flag = '0'
        if (i < end) {
            val f = tokens[i]
            if (f.type != CssToken.IDENT) return null
            flag = when (asciiLower(f.value)) {
                "i" -> 'i'
                "s" -> 's'
                else -> return null
            }
            if (skipSpace(i + 1, end) < end) return null
        }
        return AttrCondition(namespace, local, op, v.value, flag)
    }

    private fun pseudoClass(name: String): Condition? {
        if (name == "target") usesTarget = true
        STRUCTURAL[name]?.let { return PseudoCondition(it) }
        return if (name in NEVER_MATCHING) NeverCondition else null
    }

    /** The functional pseudo-class [name] with its argument in tokens [start] to [end], or null. */
    private fun functional(name: String, start: Int, end: Int, cx: Context, depth: Int): Condition? {
        if (depth + 1 > CssParser.MAX_NESTING) {
            if (!tooDeep) kiteWarn { "epub: a selector nested beyond ${CssParser.MAX_NESTING} levels is dropped" }
            tooDeep = true
            return null
        }
        val inner = depth + 1
        return when (name) {
            "not" -> list(start, end, Context(false, cx.inHas, forgiving = false, relative = false), inner)
                ?.takeIf { it.isNotEmpty() }?.let(::NotCondition)
            "is", "where" -> list(start, end, Context(false, cx.inHas, forgiving = true, relative = false), inner)
                ?.let { IsCondition(it, counts = name == "is") }
            "-webkit-any" -> list(start, end, Context(false, cx.inHas, forgiving = false, relative = false), inner)
                ?.takeIf { l -> l.isNotEmpty() && l.all { it.parts.size == 1 } }?.let { IsCondition(it, counts = true) }
            "has" -> if (cx.inHas) null else list(start, end, Context(false, inHas = true, forgiving = false, relative = true), inner)
                ?.takeIf { it.isNotEmpty() }?.let(::HasCondition)
            "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> nth(name, start, end, cx, inner)
            "lang" -> lang(start, end)
            "dir" -> {
                val i = skipSpace(start, end)
                val t = tokens.getOrNull(i)?.takeIf { i < end && it.type == CssToken.IDENT } ?: return null
                if (skipSpace(i + 1, end) < end) return null
                DirCondition(when (asciiLower(t.value)) { "rtl" -> true; "ltr" -> false; else -> null })
            }
            "host", "host-context" -> {
                val i = skipSpace(start, end)
                var last = end
                while (last > i && tokens[last - 1].type == CssToken.WHITESPACE) last--
                val c = compound(i, last, Context(false, cx.inHas, forgiving = false, relative = false), inner) ?: return null
                if (c.second != last) null else NeverCondition
            }
            "state", "active-view-transition-type" -> {
                val i = skipSpace(start, end)
                if (i >= end || tokens[i].type != CssToken.IDENT) null else NeverCondition
            }
            "current" -> list(start, end, Context(false, cx.inHas, forgiving = false, relative = false), inner)
                ?.takeIf { it.isNotEmpty() }?.let { NeverCondition }
            else -> null
        }
    }

    /** `:nth-*(An+B)`, with `of S` for the two that count children (Selectors 4, 14.4). */
    private fun nth(name: String, start: Int, end: Int, cx: Context, depth: Int): Condition? {
        val ofType = name.endsWith("of-type")
        var ofAt = -1
        if (!ofType) {
            var k = start
            while (k < end) {
                if (tokens[k].type == CssToken.IDENT && asciiEquals(tokens[k].value, "of")) { ofAt = k; break }
                k = if (closers[k] >= 0) closers[k] + 1 else k + 1
            }
        }
        val (a, b) = anPlusB(start, if (ofAt >= 0) ofAt else end) ?: return null
        val of = if (ofAt < 0) null else {
            list(ofAt + 1, end, Context(false, cx.inHas, forgiving = false, relative = false), depth)?.takeIf { it.isNotEmpty() } ?: return null
        }
        return NthCondition(a, b, last = name.startsWith("nth-last"), ofType = ofType, of = of)
    }

    /** The `An+B` of CSS Syntax 3, 6.2, in tokens [start] to [end], as (A, B), or null. */
    private fun anPlusB(start: Int, end: Int): Pair<Int, Int>? {
        val ts = (start until end).map { tokens[it] }.filter { it.type != CssToken.WHITESPACE }
        if (ts.isEmpty()) return null
        fun signed(t: CssToken) = src[t.start] == '+' || src[t.start] == '-'
        fun integer(t: CssToken) = t.type == CssToken.NUMBER && t.flag
        fun clamp(v: Double): Int = v.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
        /** The B after `An`, from token [k]: none, a signed integer, or a sign and an integer without one. */
        fun b(k: Int): Int? = when {
            k == ts.size -> 0
            k + 1 == ts.size && integer(ts[k]) && signed(ts[k]) -> clamp(ts[k].number)
            k + 2 == ts.size && (ts[k].isDelim('+') || ts[k].isDelim('-')) && integer(ts[k + 1]) && !signed(ts[k + 1]) ->
                clamp(if (ts[k].isDelim('-')) -ts[k + 1].number else ts[k + 1].number)
            else -> null
        }
        /** B for a `n-` that a signless integer follows from token [k]. */
        fun dashB(k: Int): Int? = if (k + 1 == ts.size && integer(ts[k]) && !signed(ts[k])) clamp(-ts[k].number) else null
        /** A trailing `-` and digits of a unit or an ident after its `n`, as B. */
        fun digitsB(rest: String): Int? =
            if (rest.length > 1 && rest[0] == '-' && rest.substring(1).all { it in '0'..'9' }) clamp(-(rest.substring(1).toDoubleOrNull() ?: return null)) else null

        val t0 = ts[0]
        when {
            t0.type == CssToken.IDENT && ts.size == 1 && asciiEquals(t0.value, "odd") -> return 2 to 1
            t0.type == CssToken.IDENT && ts.size == 1 && asciiEquals(t0.value, "even") -> return 2 to 0
            integer(t0) -> return if (ts.size == 1) 0 to clamp(t0.number) else null
            t0.type == CssToken.DIMENSION && t0.flag -> {
                val a = clamp(t0.number)
                val unit = asciiLower(t0.value)
                return when {
                    unit == "n" -> b(1)?.let { a to it }
                    unit == "n-" -> dashB(1)?.let { a to it }
                    unit.startsWith("n-") && ts.size == 1 -> digitsB(unit.substring(1))?.let { a to it }
                    else -> null
                }
            }
        }
        var k = 0
        var plus = false
        if (t0.isDelim('+')) {
            if (ts.size < 2 || ts[1].type != CssToken.IDENT || ts[1].start != t0.end) return null
            plus = true
            k = 1
        }
        val ident = ts[k]
        if (ident.type != CssToken.IDENT) return null
        val v = asciiLower(ident.value)
        val a = if (v.startsWith("-n") && !plus) -1 else if (v.startsWith("n")) 1 else return null
        val rest = v.substring(if (a == -1) 2 else 1)
        return when {
            rest.isEmpty() -> b(k + 1)?.let { a to it }
            rest == "-" -> dashB(k + 1)?.let { a to it }
            k + 1 == ts.size -> digitsB(rest)?.let { a to it }
            else -> null
        }
    }

    /** `:lang()`: a comma separated list of language ranges, each an ident or a string (Selectors 4, 7.2). */
    private fun lang(start: Int, end: Int): Condition? {
        val ranges = ArrayList<String>()
        var i = skipSpace(start, end)
        while (true) {
            val t = tokens.getOrNull(i)?.takeIf { i < end && (it.type == CssToken.IDENT || it.type == CssToken.STRING) } ?: return null
            ranges.add(t.value)
            i = skipSpace(i + 1, end)
            if (i >= end) break
            if (tokens[i].type != CssToken.COMMA) return null
            i = skipSpace(i + 1, end)
        }
        return LangCondition(ranges)
    }

    private companion object {
        val STRUCTURAL = mapOf(
            "root" to PseudoKind.ROOT, "scope" to PseudoKind.SCOPE, "empty" to PseudoKind.EMPTY,
            "first-child" to PseudoKind.FIRST_CHILD, "last-child" to PseudoKind.LAST_CHILD, "only-child" to PseudoKind.ONLY_CHILD,
            "first-of-type" to PseudoKind.FIRST_OF_TYPE, "last-of-type" to PseudoKind.LAST_OF_TYPE, "only-of-type" to PseudoKind.ONLY_OF_TYPE,
            "link" to PseudoKind.ANY_LINK, "any-link" to PseudoKind.ANY_LINK, "-webkit-any-link" to PseudoKind.ANY_LINK,
            "defined" to PseudoKind.DEFINED, "open" to PseudoKind.OPEN,
            "checked" to PseudoKind.CHECKED, "default" to PseudoKind.DEFAULT, "indeterminate" to PseudoKind.INDETERMINATE,
            "disabled" to PseudoKind.DISABLED, "enabled" to PseudoKind.ENABLED, "required" to PseudoKind.REQUIRED,
            "optional" to PseudoKind.OPTIONAL, "read-only" to PseudoKind.READ_ONLY, "read-write" to PseudoKind.READ_WRITE,
            "placeholder-shown" to PseudoKind.PLACEHOLDER_SHOWN, "valid" to PseudoKind.VALID, "invalid" to PseudoKind.INVALID,
            "in-range" to PseudoKind.IN_RANGE, "out-of-range" to PseudoKind.OUT_OF_RANGE, "target" to PseudoKind.TARGET,
        )

        /**
         * The pseudo-classes that are valid and never hold in a paginated book: the user's actions, a
         * visited link, the current target of a scroll, the states of a full screen, a popover, autofill and media, of a
         * shadow host, and of a scroll bar's parts.
         */
        val NEVER_MATCHING = setOf(
            "visited", "hover", "active", "focus", "focus-visible", "focus-within", "target-current", "current", "past", "future",
            "fullscreen", "-webkit-full-screen", "modal", "popover-open", "picture-in-picture", "autofill", "-webkit-autofill",
            "user-valid", "user-invalid", "playing", "paused", "seeking", "buffering", "stalled", "muted", "volume-locked",
            "host", "xr-overlay", "-webkit-drag", "active-view-transition",
            "window-inactive", "horizontal", "vertical", "decrement", "increment", "start", "end", "double-button", "single-button",
            "no-button", "corner-present",
        )

        /** The pseudo-classes that may follow a pseudo-element: the user's actions and a scroll bar's states. */
        val AFTER_PSEUDO_ELEMENT = setOf(
            "hover", "active", "focus", "focus-visible", "focus-within",
            "window-inactive", "horizontal", "vertical", "decrement", "increment", "start", "end", "double-button", "single-button",
            "no-button", "corner-present",
        )

        val LEGACY_PSEUDO_ELEMENTS = setOf("before", "after", "first-line", "first-letter")

        val PSEUDO_ELEMENTS = setOf(
            "first-line", "first-letter", "marker", "placeholder", "selection", "backdrop", "file-selector-button", "cue",
            "grammar-error", "spelling-error", "target-text", "view-transition", "details-content", "scroll-marker",
            "scroll-marker-group", "column", "checkmark", "picker-icon",
        )

        val FUNCTIONAL_PSEUDO_ELEMENTS = setOf(
            "highlight", "part", "slotted", "cue", "picker", "scroll-button", "view-transition-group", "view-transition-image-pair",
            "view-transition-old", "view-transition-new",
        )
    }
}
