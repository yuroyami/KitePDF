package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * The pseudo-classes of a form control's state (HTML, 4.16.3). A control's checkedness, an option's
 * selectedness, a control's value and the indeterminate flag are what a script set, which
 * [SelectorTree.state] reads, and else what the control's attributes and text say (#552).
 */
internal object FormStates {

    /**
     * The start of the key under which an element's layout attribute map keeps a state that a script set.
     * No attribute name can hold the character, so no selector and no serializer reads it as one.
     */
    const val STATE = "\u0000"

    /** The checkedness of an input: what a script set, else its `checked` attribute (HTML, 4.10.5). */
    private fun checkedness(el: KiteXmlNode.Element, tree: SelectorTree): Boolean =
        tree.state(el, "checked")?.let { it == "1" } ?: (tree.attr(el, "checked") != null)

    /** The value of an input or a textarea: what a script set, else its `value` attribute or its text. */
    private fun value(el: KiteXmlNode.Element, local: String?, tree: SelectorTree): String? = tree.state(el, "value")
        ?: if (local == "textarea") el.children.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text } else tree.attr(el, "value")

    fun matches(kind: PseudoKind, el: KiteXmlNode.Element, tree: SelectorTree): Boolean {
        val local = html(el, tree)
        return when (kind) {
            PseudoKind.CHECKED -> checked(el, local, tree)
            PseudoKind.DEFAULT -> when (local) {
                "input" -> inputType(el, tree).let { (it == "checkbox" || it == "radio") && tree.attr(el, "checked") != null } ||
                    isDefaultButton(el, tree)
                "option" -> tree.attr(el, "selected") != null
                "button" -> isDefaultButton(el, tree)
                else -> false
            }
            PseudoKind.INDETERMINATE -> when (local) {
                // The group holds the radio itself (HTML 4.10.5.1.15), as it does for validity below.
                "input" -> when (inputType(el, tree)) {
                    "checkbox" -> tree.state(el, "indeterminate") == "1"
                    "radio" -> (radioGroup(el, tree) + el).none { checkedness(it, tree) }
                    else -> false
                }
                "progress" -> tree.attr(el, "value") == null
                else -> false
            }
            PseudoKind.DISABLED -> local in DISABLEABLE && disabled(el, local!!, tree)
            PseudoKind.ENABLED -> local in DISABLEABLE && !disabled(el, local!!, tree)
            PseudoKind.REQUIRED -> required(el, local, tree)
            PseudoKind.OPTIONAL -> (local == "input" || local == "select" || local == "textarea") && !required(el, local, tree)
            PseudoKind.READ_WRITE -> readWrite(el, local, tree)
            PseudoKind.READ_ONLY -> !readWrite(el, local, tree)
            PseudoKind.PLACEHOLDER_SHOWN -> tree.attr(el, "placeholder") != null && when (local) {
                "input" -> inputType(el, tree) in PLACEHOLDER_TYPES && value(el, local, tree).isNullOrEmpty()
                "textarea" -> value(el, local, tree).isNullOrEmpty()
                else -> false
            }
            PseudoKind.VALID -> validity(el, local, tree) == true
            PseudoKind.INVALID -> validity(el, local, tree) == false
            PseudoKind.IN_RANGE -> range(el, local, tree) == true
            PseudoKind.OUT_OF_RANGE -> range(el, local, tree) == false
            else -> false
        }
    }

    /** The type of an input, by HTML's keywords, `text` for a missing or unknown one. */
    private fun inputType(el: KiteXmlNode.Element, tree: SelectorTree): String =
        tree.attr(el, "type")?.let(::asciiLower)?.takeIf { it in INPUT_TYPES } ?: "text"

    // Blink matches no indeterminate checkbox, though its checkedness is true.
    private fun checked(el: KiteXmlNode.Element, local: String?, tree: SelectorTree): Boolean = when (local) {
        "input" -> when (inputType(el, tree)) {
            "checkbox" -> checkedness(el, tree) && tree.state(el, "indeterminate") != "1"
            "radio" -> checkedness(el, tree)
            else -> false
        }
        "option" -> selected(el, tree)
        else -> false
    }

    /**
     * The selectedness of an option (HTML, 4.10.7): its `selected` attribute, but that a select of
     * one choice shown at a time has the last option so marked, or else its first enabled option.
     */
    private fun selected(option: KiteXmlNode.Element, tree: SelectorTree): Boolean {
        tree.state(option, "selected")?.let { return it == "1" }
        val select = optionSelect(option, tree) ?: return tree.attr(option, "selected") != null
        if (tree.attr(select, "multiple") != null || (tree.attr(select, "size")?.trim()?.toIntOrNull() ?: 0) > 1) {
            return tree.attr(option, "selected") != null
        }
        val options = options(select, tree)
        val chosen = options.lastOrNull { tree.attr(it, "selected") != null } ?: options.firstOrNull { !disabled(it, "option", tree) }
        return chosen === option
    }

    private fun optionSelect(option: KiteXmlNode.Element, tree: SelectorTree): KiteXmlNode.Element? {
        var p = parentElement(option) ?: return null
        if (html(p, tree) == "optgroup") p = parentElement(p) ?: return null
        return p.takeIf { html(it, tree) == "select" }
    }

    /** The list of options of a select: its option children and those of its optgroup children. */
    private fun options(select: KiteXmlNode.Element, tree: SelectorTree): List<KiteXmlNode.Element> {
        val out = ArrayList<KiteXmlNode.Element>()
        for (c in select.children) if (c is KiteXmlNode.Element) when (html(c, tree)) {
            "option" -> out.add(c)
            "optgroup" -> for (o in c.children) if (o is KiteXmlNode.Element && html(o, tree) == "option") out.add(o)
        }
        return out
    }

    /**
     * Whether a control is disabled (HTML, 4.10.18.5): by its own `disabled` attribute, or an option's
     * optgroup's, or a disabled fieldset around it, unless it sits in that fieldset's first legend.
     */
    private fun disabled(el: KiteXmlNode.Element, local: String, tree: SelectorTree): Boolean {
        if (tree.attr(el, "disabled") != null) return true
        if (local == "option") return parentElement(el)?.let { html(it, tree) == "optgroup" && tree.attr(it, "disabled") != null } ?: false
        if (local == "optgroup") return false
        var child = el
        var p = parentElement(el)
        while (p != null) {
            if (html(p, tree) == "fieldset" && tree.attr(p, "disabled") != null) {
                val legend = p.children.firstOrNull { it is KiteXmlNode.Element && html(it, tree) == "legend" }
                if (child !== legend) return true
            }
            child = p
            p = parentElement(p)
        }
        return false
    }

    private fun required(el: KiteXmlNode.Element, local: String?, tree: SelectorTree): Boolean = when (local) {
        "input" -> inputType(el, tree) in REQUIRED_TYPES && tree.attr(el, "required") != null
        "select", "textarea" -> tree.attr(el, "required") != null
        else -> false
    }

    /** `:read-write`: a mutable text control, or an element an editing host holds (HTML, 4.16.3 and 6.8.1). */
    private fun readWrite(el: KiteXmlNode.Element, local: String?, tree: SelectorTree): Boolean {
        when (local) {
            "input" -> return inputType(el, tree) in READONLY_TYPES && tree.attr(el, "readonly") == null && !disabled(el, local, tree)
            "textarea" -> return tree.attr(el, "readonly") == null && !disabled(el, local, tree)
        }
        var e: KiteXmlNode.Element? = el
        while (e != null) {
            when (tree.attr(e, "contenteditable")?.let(::asciiLower)) {
                "", "true", "plaintext-only" -> return true
                "false" -> return false
            }
            e = parentElement(e)
        }
        return false
    }

    /**
     * Whether a candidate for constraint validation satisfies its constraints (HTML, 4.10.20), as far
     * as its attributes tell: a required control's value missing, or a number out of its range. A
     * form or a fieldset is invalid when a control in it is. Null for an element that is neither.
     */
    private fun validity(el: KiteXmlNode.Element, local: String?, tree: SelectorTree): Boolean? = when (local) {
        "form", "fieldset" -> !anyInvalid(el, tree)
        "input", "select", "textarea", "button" -> if (!candidate(el, local, tree)) null else controlValid(el, local, tree)
        else -> null
    }

    private fun anyInvalid(el: KiteXmlNode.Element, tree: SelectorTree): Boolean {
        for (c in el.children) if (c is KiteXmlNode.Element) {
            val local = html(c, tree)
            if (local in CONTROLS && candidate(c, local!!, tree) && !controlValid(c, local, tree)) return true
            if (anyInvalid(c, tree)) return true
        }
        return false
    }

    private fun candidate(el: KiteXmlNode.Element, local: String, tree: SelectorTree): Boolean {
        if (disabled(el, local, tree)) return false
        var p = parentElement(el)
        while (p != null) { if (html(p, tree) == "datalist") return false; p = parentElement(p) }
        return when (local) {
            "input" -> inputType(el, tree).let { it != "hidden" && it != "reset" && it != "button" && !(it in READONLY_TYPES && tree.attr(el, "readonly") != null) }
            "button" -> (tree.attr(el, "type")?.let(::asciiLower) ?: "submit").let { it != "reset" && it != "button" }
            "textarea" -> tree.attr(el, "readonly") == null
            else -> true
        }
    }

    private fun controlValid(el: KiteXmlNode.Element, local: String, tree: SelectorTree): Boolean {
        if (required(el, local, tree)) {
            val missing = when (local) {
                "input" -> when (inputType(el, tree)) {
                    "checkbox" -> !checkedness(el, tree)
                    "radio" -> (radioGroup(el, tree) + el).none { checkedness(it, tree) }
                    else -> value(el, local, tree).isNullOrEmpty()
                }
                "textarea" -> value(el, local, tree).isNullOrEmpty()
                "select" -> {
                    val options = options(el, tree)
                    val chosen = options.filter { selected(it, tree) }
                    chosen.isEmpty() || (chosen.size == 1 && chosen[0] === options.firstOrNull() && placeholderOption(el, chosen[0], tree))
                }
                else -> false
            }
            if (missing) return false
        }
        return range(el, local, tree) != false
    }

    /** Whether [option] is the placeholder label option of [select]: an empty value in a select of one choice shown at a time. */
    private fun placeholderOption(select: KiteXmlNode.Element, option: KiteXmlNode.Element, tree: SelectorTree): Boolean =
        tree.attr(select, "multiple") == null && (tree.attr(select, "size")?.trim()?.toIntOrNull() ?: 1) <= 1 &&
            parentElement(option) === select &&
            (tree.attr(option, "value") ?: option.children.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text }).isEmpty()

    /**
     * Whether a number input with a range is in it (HTML, 4.10.5.3.7), or null for an element with no
     * range to be in. A range input is always in its range, as its value is clamped to it.
     */
    private fun range(el: KiteXmlNode.Element, local: String?, tree: SelectorTree): Boolean? {
        if (local != "input") return null
        return when (inputType(el, tree)) {
            "range" -> if (candidate(el, local, tree)) true else null
            "number" -> {
                val min = tree.attr(el, "min")?.trim()?.toDoubleOrNull()
                val max = tree.attr(el, "max")?.trim()?.toDoubleOrNull()
                if ((min == null && max == null) || !candidate(el, local, tree)) return null
                val number = value(el, local, tree)?.trim()?.toDoubleOrNull() ?: return true
                !(min != null && number < min) && !(max != null && number > max)
            }
            else -> null
        }
    }

    /** The other radio buttons of [input]'s group: those of its form, or of its tree when it has none, with its name. */
    private fun radioGroup(input: KiteXmlNode.Element, tree: SelectorTree): List<KiteXmlNode.Element> {
        val name = tree.attr(input, "name")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val form = formOf(input, tree)
        var top = input
        while (true) top = top.parent ?: break
        val out = ArrayList<KiteXmlNode.Element>()
        fun walk(e: KiteXmlNode.Element) {
            for (c in e.children) if (c is KiteXmlNode.Element) {
                if (c !== input && html(c, tree) == "input" && inputType(c, tree) == "radio" && tree.attr(c, "name") == name && formOf(c, tree) === form) out.add(c)
                walk(c)
            }
        }
        walk(top)
        return out
    }

    private fun formOf(el: KiteXmlNode.Element, tree: SelectorTree): KiteXmlNode.Element? {
        var p = parentElement(el)
        while (p != null) { if (html(p, tree) == "form") return p; p = parentElement(p) }
        return null
    }

    /** Whether [el] is the default button of its form: the first submit button in it (HTML, 4.10.22.2). */
    private fun isDefaultButton(el: KiteXmlNode.Element, tree: SelectorTree): Boolean {
        if (!isSubmitButton(el, tree)) return false
        val form = formOf(el, tree) ?: return false
        fun first(e: KiteXmlNode.Element): KiteXmlNode.Element? {
            for (c in e.children) if (c is KiteXmlNode.Element) {
                if (isSubmitButton(c, tree)) return c
                first(c)?.let { return it }
            }
            return null
        }
        return first(form) === el
    }

    private fun isSubmitButton(el: KiteXmlNode.Element, tree: SelectorTree): Boolean = when (html(el, tree)) {
        "button" -> (tree.attr(el, "type")?.let(::asciiLower) ?: "submit").let { it != "reset" && it != "button" }
        "input" -> inputType(el, tree).let { it == "submit" || it == "image" }
        else -> false
    }

    private val DISABLEABLE = setOf("button", "input", "select", "textarea", "optgroup", "option", "fieldset")
    private val CONTROLS = setOf("button", "input", "select", "textarea")
    private val INPUT_TYPES = setOf(
        "hidden", "text", "search", "tel", "url", "email", "password", "date", "month", "week", "time", "datetime-local", "number",
        "range", "color", "checkbox", "radio", "file", "submit", "image", "reset", "button",
    )
    private val REQUIRED_TYPES = setOf(
        "text", "search", "tel", "url", "email", "password", "date", "month", "week", "time", "datetime-local", "number",
        "checkbox", "radio", "file",
    )
    private val READONLY_TYPES = setOf(
        "text", "search", "tel", "url", "email", "password", "date", "month", "week", "time", "datetime-local", "number",
    )
    private val PLACEHOLDER_TYPES = setOf("text", "search", "tel", "url", "email", "password", "number")
}
