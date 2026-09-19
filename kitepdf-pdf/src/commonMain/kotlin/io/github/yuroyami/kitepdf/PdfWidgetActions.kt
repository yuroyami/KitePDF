package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary

/**
 * The scripts an annotation runs when the reader touches it, from its `/AA`
 * dictionary (ISO 32000-1 §12.6.3, Table 194 and Table 196).
 *
 * An interactive PDF puts its behaviour here: a text field filters what is typed
 * with [keystroke], a button runs [mouseDown] when it is pressed, and a total is
 * recomputed by the [calculate] script of the field that shows it. Each entry is
 * a parsed [PdfAction], usually [PdfAction.JavaScript], and null when the
 * document does not define that trigger.
 *
 * The four form triggers ([keystroke], [format], [validate], [calculate]) only
 * have meaning on a widget of a form field. The six mouse and focus triggers
 * apply to any annotation.
 */
public data class PdfWidgetActions(
    /** `/E`: the pointer enters the annotation's area. */
    val mouseEnter: PdfAction? = null,
    /** `/X`: the pointer leaves the annotation's area. */
    val mouseExit: PdfAction? = null,
    /** `/D`: the pointer button goes down inside the annotation. */
    val mouseDown: PdfAction? = null,
    /** `/U`: the pointer button is released inside the annotation. */
    val mouseUp: PdfAction? = null,
    /** `/Fo`: the annotation receives the input focus. */
    val focus: PdfAction? = null,
    /** `/Bl`: the annotation loses the input focus. */
    val blur: PdfAction? = null,
    /** `/PO`: the page holding the annotation is opened. */
    val pageOpen: PdfAction? = null,
    /** `/PC`: the page holding the annotation is closed. */
    val pageClose: PdfAction? = null,
    /** `/PV`: the page holding the annotation becomes visible. */
    val pageVisible: PdfAction? = null,
    /** `/PI`: the page holding the annotation is no longer visible. */
    val pageInvisible: PdfAction? = null,
    /** `/K`: the reader types into the field, and once more when the value is committed. */
    val keystroke: PdfAction? = null,
    /** `/F`: the field's value is about to be shown, so the script may format it. */
    val format: PdfAction? = null,
    /** `/V`: the field's value changed and the script accepts or rejects it. */
    val validate: PdfAction? = null,
    /** `/C`: the field recomputes its value, in the order of the form's `/CO` array. */
    val calculate: PdfAction? = null,
    /** The raw `/AA` dictionary, for triggers this type does not model. */
    val raw: PdfDictionary,
) {

    /** True when the dictionary defined no trigger this type knows. */
    public val isEmpty: Boolean
        get() = mouseEnter == null && mouseExit == null && mouseDown == null && mouseUp == null &&
            focus == null && blur == null && pageOpen == null && pageClose == null &&
            pageVisible == null && pageInvisible == null && keystroke == null && format == null &&
            validate == null && calculate == null

    public companion object {

        /** Reads `/AA` from [dict], or null when it has none. */
        internal fun parse(dict: PdfDictionary?, refs: IndirectResolver): PdfWidgetActions? {
            val aa = dict?.getDict("AA", refs) ?: return null
            fun action(key: String): PdfAction? = PdfAction.parse(aa.getDict(key, refs), refs)
            return PdfWidgetActions(
                mouseEnter = action("E"),
                mouseExit = action("X"),
                mouseDown = action("D"),
                mouseUp = action("U"),
                focus = action("Fo"),
                blur = action("Bl"),
                pageOpen = action("PO"),
                pageClose = action("PC"),
                pageVisible = action("PV"),
                pageInvisible = action("PI"),
                keystroke = action("K"),
                format = action("F"),
                validate = action("V"),
                calculate = action("C"),
                raw = aa,
            )
        }
    }
}

/**
 * The scripts a document runs at the moments of its own life, from the catalog's
 * `/AA` dictionary (ISO 32000-1 §12.6.3, Table 197).
 *
 * A form uses these to warn before printing, or to stamp a save date into a
 * field. Each entry is null when the document does not define that trigger.
 */
public data class PdfDocumentActions(
    /** `/WC`: the document is about to close. */
    val willClose: PdfAction? = null,
    /** `/WS`: the document is about to be saved. */
    val willSave: PdfAction? = null,
    /** `/DS`: the document has been saved. */
    val didSave: PdfAction? = null,
    /** `/WP`: the document is about to be printed. */
    val willPrint: PdfAction? = null,
    /** `/DP`: the document has been printed. */
    val didPrint: PdfAction? = null,
    /** The raw `/AA` dictionary. */
    val raw: PdfDictionary,
) {

    /** True when the dictionary defined no trigger this type knows. */
    public val isEmpty: Boolean
        get() = willClose == null && willSave == null && didSave == null &&
            willPrint == null && didPrint == null

    public companion object {

        internal fun parse(catalog: PdfDictionary, refs: IndirectResolver): PdfDocumentActions? {
            val aa = catalog.getDict("AA", refs) ?: return null
            fun action(key: String): PdfAction? = PdfAction.parse(aa.getDict(key, refs), refs)
            return PdfDocumentActions(
                willClose = action("WC"),
                willSave = action("WS"),
                didSave = action("DS"),
                willPrint = action("WP"),
                didPrint = action("DP"),
                raw = aa,
            )
        }
    }
}
