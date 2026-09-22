package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * One terminal interactive-form field (ISO 32000-1 §12.7.3), flattened from the
 * AcroForm field tree with inheritable attributes (`/FT`, `/DA`, `/Ff`, `/V`,
 * `/Q`) resolved down from ancestors.
 *
 * A field and its single widget annotation are frequently merged into one
 * dictionary; when they are separate (e.g. one logical field with widgets on
 * several pages) [fieldReference] and [widgetReference] differ. The writer's
 * form-fill uses these references plus [rect] (the widget's `/Rect`) to set the
 * value and regenerate the appearance.
 */
public class PdfFormField internal constructor(
    /** Fully-qualified name: ancestor partial names joined by '.'. */
    public val fullyQualifiedName: String,
    /** This field's own `/T`, or null for an anonymous node. */
    public val partialName: String?,
    public val type: FieldType,
    /** `/V` rendered as text (text fields) or the selected name (buttons/choices). */
    public val value: String?,
    /** Variable-text default appearance string (`/DA`), inherited if absent. */
    public val defaultAppearance: String?,
    /** Field flags (`/Ff`), inherited if absent. */
    public val flags: Int,
    /** Quadding for variable text (`/Q`): 0 left, 1 centre, 2 right. */
    public val quadding: Int,
    /** The first widget's `/Rect`, or null when this terminal field has no widget. */
    public val rect: KiteRectangle?,
    /** Indirect reference to the field dictionary, for editing. */
    public val fieldReference: PdfReference?,
    /** Indirect reference to the widget annotation (== [fieldReference] when merged). */
    public val widgetReference: PdfReference?,
    internal val fieldDict: PdfDictionary,
    internal val widgetDict: PdfDictionary,
    /**
     * The choices of a list box or a combo box, in the order the file lists them (`/Opt`,
     * ISO 32000-1 §12.7.4.4). Empty for every other kind of field.
     */
    public val options: List<String> = emptyList(),
    /** The value the field goes back to when the form is reset (`/DV`), or null when it has none. */
    public val defaultValue: String? = null,
    /** How many characters a text field takes (`/MaxLen`), or null when it does not say. */
    public val maxLength: Int? = null,
    /** The text a viewer shows when the pointer rests on the field (`/TU`), or null. */
    public val tooltip: String? = null,
    /**
     * Every widget annotation this field owns, in the order the field declares them. One field
     * may be shown in several places: a radio group has one widget per button, and a field may
     * repeat on several pages (ISO 32000-1 §12.7.3.1). The first entry is the one [rect],
     * [widgetReference] and [widgetDict] describe.
     */
    public val widgets: List<Widget> = emptyList(),
) {
    public enum class FieldType { Text, Button, Choice, Signature, Unknown }

    /** One place a field is drawn: its annotation, its rectangle, and the reference to reach it. */
    public class Widget internal constructor(
        /** The widget's `/Rect`, or null when it has none. */
        public val rect: KiteRectangle?,
        /** Indirect reference to the widget annotation, for editing. */
        public val reference: PdfReference?,
        /**
         * The `/AP /N` state that selects this widget, for a check box or a radio button, such as
         * `Yes` or `Choice1` (ISO 32000-1 §12.7.4.2.1). Null when the widget has no named states.
         */
        public val onStateName: String?,
        /** The scripts of this widget's `/AA` dictionary, or null when it has none. */
        public val additionalActions: PdfWidgetActions?,
        /** A push button's caption (`/MK /CA`), or null when the widget has none. */
        public val caption: String? = null,
        /**
         * The border style (`/BS /S`): `solid`, `dashed`, `beveled`, `inset` or `underline`
         * (ISO 32000-1 §12.5.4, Table 166). Null when the widget does not say.
         */
        public val borderStyle: String? = null,
        internal val dict: PdfDictionary,
    ) {
        override fun toString(): String = "Widget(rect=$rect, onState=$onStateName)"
    }

    /** The scripts of the field's first widget, or of the field itself when the two are merged. */
    public val additionalActions: PdfWidgetActions? get() = widgets.firstOrNull()?.additionalActions

    /** `/Ff` bit 1: the field is read-only. */
    public val isReadOnly: Boolean get() = (flags and 0b1) != 0

    /** `/Ff` bit 13 (text fields): multi-line. */
    public val isMultiline: Boolean get() = type == FieldType.Text && (flags and (1 shl 12)) != 0

    override fun toString(): String = "PdfFormField($fullyQualifiedName, $type, value=$value)"

    public companion object {

        internal fun collect(catalog: PdfDictionary, refs: IndirectResolver): List<PdfFormField> {
            val acro = catalog.getDict("AcroForm", refs)
            val acroDA = (acro?.get("DA") as? PdfString)?.asText()
            val acroQ = (acro?.get("Q") as? PdfInt)?.value?.toInt() ?: 0
            val out = ArrayList<PdfFormField>()
            for (item in acro?.getArray("Fields", refs) ?: emptyList()) {
                val (dict, ref) = resolveDictRef(item, refs) ?: continue
                walk(dict, ref, refs, parentName = null, inhFT = null, inhDA = acroDA, inhFf = 0, inhV = null, inhQ = acroQ, out)
            }
            collectStrayWidgets(catalog, refs, acroDA, acroQ, out)
            return out
        }

        /**
         * Adds the widgets that the field tree never reached.
         *
         * ISO 32000-1, 12.7.2 says every field hangs off `/AcroForm /Fields`, so a page widget
         * outside that tree is malformed. Files like that exist, some with no `/AcroForm` at all,
         * and readers accept them: PDFium walks each page and adopts the widgets the tree missed
         * (`CPDF_InteractiveForm::FixPageFields`), which is why such a form works in Chrome. This
         * does the same, so the fields can be read, filled and scripted.
         */
        private fun collectStrayWidgets(
            catalog: PdfDictionary,
            refs: IndirectResolver,
            acroDA: String?,
            acroQ: Int,
            out: MutableList<PdfFormField>,
        ) {
            val seen = HashSet<PdfReference>()
            for (field in out) {
                for (widget in field.widgets) widget.reference?.let { seen.add(it) }
                field.fieldReference?.let { seen.add(it) }
            }
            for (pageNode in walkPageTree(catalog, refs)) {
                val annots = pageNode.getArray("Annots", refs) ?: continue
                for (item in annots) {
                    val (dict, ref) = resolveDictRef(item, refs) ?: continue
                    if (dict.getName("Subtype") != "Widget") continue
                    if (ref != null && !seen.add(ref)) continue
                    // A widget with no /T of its own belongs to its parent field.
                    val (fieldDict, fieldRef) = ownerOf(dict, ref, refs)
                    if (fieldRef != null && fieldRef != ref && !seen.add(fieldRef)) continue
                    walk(
                        fieldDict, fieldRef, refs, parentName = parentChainName(fieldDict, refs),
                        inhFT = null, inhDA = acroDA, inhFf = 0, inhV = null, inhQ = acroQ, out,
                    )
                }
            }
        }

        /** The field a stray widget belongs to: its `/Parent` chain up to the node that owns a `/T`. */
        private fun ownerOf(
            widget: PdfDictionary,
            widgetRef: PdfReference?,
            refs: IndirectResolver,
        ): Pair<PdfDictionary, PdfReference?> {
            if (widget["T"] != null || widget["Parent"] == null) return widget to widgetRef
            var dict = widget
            var ref = widgetRef
            var guard = 0
            while (guard++ < MAX_PARENT_DEPTH) {
                val parentRef = dict["Parent"] as? PdfReference
                val parent = (dict["Parent"]?.resolve(refs) as? PdfDictionary) ?: break
                dict = parent
                ref = parentRef
                if (parent["T"] != null) break
            }
            return dict to ref
        }

        /** The dotted name of everything above [node], so a stray widget keeps its full name. */
        private fun parentChainName(node: PdfDictionary, refs: IndirectResolver): String? {
            val names = ArrayList<String>()
            var current = node["Parent"]?.resolve(refs) as? PdfDictionary
            var guard = 0
            while (current != null && guard++ < MAX_PARENT_DEPTH) {
                (current["T"] as? PdfString)?.asText()?.let { names.add(0, it) }
                current = current["Parent"]?.resolve(refs) as? PdfDictionary
            }
            return names.takeIf { it.isNotEmpty() }?.joinToString(".")
        }

        /** Every page dictionary of the document, without building the page objects. */
        private fun walkPageTree(catalog: PdfDictionary, refs: IndirectResolver): List<PdfDictionary> {
            val root = catalog.getDict("Pages", refs) ?: return emptyList()
            val out = ArrayList<PdfDictionary>()
            val queue = ArrayDeque<PdfDictionary>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < MAX_PAGE_NODES) {
                val node = queue.removeFirst()
                val kids = node.getArray("Kids", refs)
                if (kids == null) {
                    out.add(node)
                    continue
                }
                for (kid in kids) {
                    (kid.resolve(refs) as? PdfDictionary)?.let { queue.add(it) }
                }
            }
            return out
        }

        /** A malformed `/Parent` chain must not loop forever. */
        private const val MAX_PARENT_DEPTH = 32

        /** A malformed page tree must not loop forever. */
        private const val MAX_PAGE_NODES = 100_000

        private fun walk(
            node: PdfDictionary,
            ref: PdfReference?,
            refs: IndirectResolver,
            parentName: String?,
            inhFT: String?,
            inhDA: String?,
            inhFf: Int,
            inhV: String?,
            inhQ: Int,
            out: MutableList<PdfFormField>,
        ) {
            val partial = (node["T"] as? PdfString)?.asText()
            val name = when {
                partial == null -> parentName
                parentName == null -> partial
                else -> "$parentName.$partial"
            }
            val ft = node.getName("FT") ?: inhFT
            val da = (node["DA"] as? PdfString)?.asText() ?: inhDA
            val ff = node.getInt("Ff")?.toInt() ?: inhFf
            val q = node.getInt("Q")?.toInt() ?: inhQ
            val v = node["V"]?.let { valueToString(it, refs) } ?: inhV

            // A kid is a *sub-field* if it has its own /T; otherwise it's a widget.
            val kids = node.getArray("Kids", refs)
            val kidPairs = kids?.mapNotNull { resolveDictRef(it, refs) } ?: emptyList()
            val subFields = kidPairs.filter { (d, _) -> d["T"] != null }

            if (subFields.isNotEmpty()) {
                for ((kd, kr) in subFields) {
                    walk(kd, kr, refs, name, ft, da, ff, v, q, out)
                }
                return
            }

            // Terminal field. Its widgets are either this same dictionary (field and widget
            // merged) or its widget kids: a radio group has one per button, and a field repeated
            // on several pages has one per page (§12.7.3.1).
            val widgetPairs = when {
                node.getName("Subtype") == "Widget" || node["Rect"] != null -> listOf(node to ref)
                kidPairs.isNotEmpty() -> kidPairs
                else -> listOf(node to ref)
            }
            val widgets = widgetPairs.map { (dict, widgetRef) ->
                Widget(
                    rect = missingAsNull { dict.getArray("Rect", refs) }?.takeIf { it.size >= 4 }?.let { KiteRectangle.fromPdfArray(it) },
                    reference = widgetRef,
                    onStateName = onStateNameOf(dict, refs),
                    additionalActions = PdfWidgetActions.parse(dict, refs),
                    caption = (dict.getDict("MK", refs)?.get("CA")?.resolve(refs) as? PdfString)?.asText(),
                    borderStyle = borderStyleOf(dict, refs),
                    dict = dict,
                )
            }
            val (widgetDict, widgetRef) = widgetPairs.first()
            val rect = widgets.first().rect

            out.add(
                PdfFormField(
                    fullyQualifiedName = name ?: "",
                    partialName = partial,
                    type = fieldType(ft),
                    value = v,
                    defaultAppearance = da,
                    flags = ff,
                    quadding = q,
                    rect = rect,
                    fieldReference = ref,
                    widgetReference = widgetRef,
                    fieldDict = node,
                    widgetDict = widgetDict,
                    options = optionsOf(node, refs),
                    defaultValue = inheritedText(node, "DV", refs),
                    maxLength = (inheritedValue(node, "MaxLen", refs) as? PdfInt)?.value?.toInt(),
                    tooltip = (widgetDict["TU"]?.resolve(refs) as? PdfString)?.asText(),
                    widgets = widgets,
                ),
            )
        }

        /** `/Opt`: the choices of a list box or a combo box (ISO 32000-1, 12.7.4.4). */
        private fun optionsOf(node: PdfDictionary, refs: IndirectResolver): List<String> {
            val opt = inheritedValue(node, "Opt", refs) as? PdfArray ?: return emptyList()
            return opt.mapNotNull { entry ->
                when (val item = entry.resolve(refs)) {
                    is PdfString -> item.asText()
                    // A pair is [export value, what the reader sees], and the reader's text wins.
                    is PdfArray -> (item.getOrNull(1)?.resolve(refs) as? PdfString)?.asText()
                        ?: (item.getOrNull(0)?.resolve(refs) as? PdfString)?.asText()
                    else -> null
                }
            }
        }

        /** `/BS /S` as a word a script understands (ISO 32000-1, 12.5.4, Table 166). */
        private fun borderStyleOf(dict: PdfDictionary, refs: IndirectResolver): String? =
            when ((dict.getDict("BS", refs)?.get("S")?.resolve(refs) as? PdfName)?.value) {
                "S" -> "solid"
                "D" -> "dashed"
                "B" -> "beveled"
                "I" -> "inset"
                "U" -> "underline"
                else -> null
            }

        /** An entry of the field or of the nearest ancestor that has it (ISO 32000-1, 12.7.3.2). */
        private fun inheritedValue(node: PdfDictionary, key: String, refs: IndirectResolver): PdfObject? {
            var current: PdfDictionary? = node
            var guard = 0
            while (current != null && guard++ < MAX_PARENT_DEPTH) {
                current[key]?.resolve(refs)?.let { return it }
                current = current["Parent"]?.resolve(refs) as? PdfDictionary
            }
            return null
        }

        private fun inheritedText(node: PdfDictionary, key: String, refs: IndirectResolver): String? =
            when (val value = inheritedValue(node, key, refs)) {
                is PdfString -> value.asText()
                is PdfName -> value.value
                else -> null
            }

        /**
         * The fully qualified name of the field a widget belongs to: its own `/T` if it has one,
         * with every ancestor's `/T` in front, joined by dots (ISO 32000-1, 12.7.3.2). Null when
         * neither the widget nor its ancestors name a field.
         */
        internal fun qualifiedNameOf(widget: PdfDictionary, refs: IndirectResolver): String? {
            val names = ArrayList<String>()
            var node: PdfDictionary? = widget
            var guard = 0
            while (node != null && guard++ < MAX_PARENT_DEPTH) {
                (node["T"] as? PdfString)?.asText()?.let { names.add(0, it) }
                node = node["Parent"]?.resolve(refs) as? PdfDictionary
            }
            return names.takeIf { it.isNotEmpty() }?.joinToString(".")
        }

        /**
         * The `/AP /N` state that turns this widget on: the one name that is not `Off`
         * (ISO 32000-1 §12.7.4.2.1). Null when the widget has no named appearance states.
         */
        private fun onStateNameOf(dict: PdfDictionary, refs: IndirectResolver): String? {
            val normal = dict.getDict("AP", refs)?.get("N")?.resolve(refs) as? PdfDictionary ?: return null
            return normal.map.keys.firstOrNull { it != "Off" }
        }

        private fun resolveDictRef(item: PdfObject, refs: IndirectResolver): Pair<PdfDictionary, PdfReference?>? =
            when (item) {
                is PdfReference -> (refs.resolve(item) as? PdfDictionary)?.let { it to item }
                is PdfDictionary -> item to null
                else -> null
            }

        private fun valueToString(value: PdfObject, refs: IndirectResolver): String? =
            when (val v = value.resolve(refs)) {
                is PdfString -> v.asText()
                is PdfName -> v.value
                else -> null
            }

        private fun fieldType(ft: String?): FieldType = when (ft) {
            "Tx" -> FieldType.Text
            "Btn" -> FieldType.Button
            "Ch" -> FieldType.Choice
            "Sig" -> FieldType.Signature
            else -> FieldType.Unknown
        }
    }
}
