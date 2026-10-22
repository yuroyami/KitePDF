package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.kiteWarn

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
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
    /** `/V` as text; for choices, the first selected export value. Use [choiceSelection] for all values. */
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
     * ISO 32000-1 §12.7.4.4). This legacy label view omits malformed entries; use [choiceOptions]
     * to retain original option indices and export values.
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
        /**
         * The action performed when the widget is activated (`/A`, ISO 32000-1 §12.5.6.19): what
         * a push button does on release, such as a reset, a submit or a script. Null when the
         * widget has none.
         */
        public val action: PdfAction? = null,
    ) {
        override fun toString(): String = "Widget(rect=$rect, onState=$onStateName)"
    }

    /**
     * The scripts of the field's first widget, or of the field itself when the two are merged.
     * Each button of a radio group has scripts of its own: read those from [widgets].
     */
    public val additionalActions: PdfWidgetActions? get() = widgets.firstOrNull()?.additionalActions

    /** `/Ff` bit 1: the field is read-only. */
    public val isReadOnly: Boolean get() = (flags and 0b1) != 0

    /** `/Ff` bit 13 (text fields): multi-line. */
    public val isMultiline: Boolean get() = type == FieldType.Text && (flags and (1 shl 12)) != 0

    /**
     * `/Ff` bit 26 (text fields): the value is rich text, XHTML kept in `/RV` next to the plain
     * value in [value] (ISO 32000-1, 12.7.3.4).
     */
    public val isRichText: Boolean get() = type == FieldType.Text && (flags and (1 shl 25)) != 0

    /** The original `/Opt` indices, exports and labels (ISO 32000-1, 12.7.4.4). */
    public val choiceOptions: List<PdfChoiceOption> get() = parsedChoices
    private var parsedChoices: List<PdfChoiceOption> = choiceSnapshot(options.mapIndexed { index, label -> PdfChoiceOption(index, label, label) })

    /** The complete source `/V`, with `/I` resolving duplicate exports when consistent. */
    public val choiceSelection: PdfChoiceSelection get() = parsedSelection
    private var parsedSelection: PdfChoiceSelection = PdfChoiceSelection(emptyList())

    /** The complete reset value `/DV`, or an empty selection (ISO 32000-1, 12.7.3.1). */
    public val defaultChoiceSelection: PdfChoiceSelection get() = parsedDefaultSelection
    private var parsedDefaultSelection: PdfChoiceSelection = PdfChoiceSelection(emptyList())

    /** `/Ff` bit 18: a combo rather than a list (ISO 32000-1, Table 230). */
    public val isCombo: Boolean get() = type == FieldType.Choice && (flags and (1 shl 17)) != 0

    /** `/Ff` bit 19 is meaningful only for a combo (ISO 32000-1, Table 230). */
    public val isEditableCombo: Boolean get() = isCombo && (flags and (1 shl 18)) != 0

    /** `/Ff` bit 22 allows several selected list options (ISO 32000-1, Table 230). */
    public val isMultiSelect: Boolean get() = type == FieldType.Choice && !isCombo && (flags and (1 shl 21)) != 0

    /** `/Ff` bit 27 commits a selection change immediately (ISO 32000-1, Table 230). */
    public val commitOnSelectionChange: Boolean get() = type == FieldType.Choice && (flags and (1 shl 26)) != 0

    /** Original option index at the top of a list, `/TI`, clamped to a non-negative value. */
    public val topIndex: Int get() = parsedTopIndex
    private var parsedTopIndex: Int = 0

    /** Height of one displayed choice row in PDF points, using `/DA` (ISO 32000-1, 12.7.3.3). */
    public val choiceRowHeight: Double get() = io.github.yuroyami.kitepdf.writer.FieldAppearance.parseDA(defaultAppearance)
        .fontSize.takeIf { it > 0.0 }?.times(1.2) ?: 14.4

    /** Content inset of a choice widget in PDF points, accounting for its own border. */
    public fun choiceContentPadding(widgetIndex: Int = 0): Double = choicePadding.getOrElse(widgetIndex) { 1.0 }
    private var choicePadding: List<Double> = emptyList()

    /**
     * Original option index at the top of the displayed list. Starts at `/TI` and moves to
     * the first selected row if no selected row is visible, so drawing and pointer hit tests
     * use the same viewport. Returns zero when no usable options remain.
     */
    public fun choiceTopIndexFor(selection: PdfChoiceSelection, visibleRows: Int): Int {
        var first = choiceOptions.indexOfFirst { it.index >= topIndex }.takeIf { it >= 0 } ?: 0
        val selectedPositions = choiceOptions.mapIndexedNotNull { index, option -> index.takeIf { option.index in selection.indices } }
        if (selectedPositions.isNotEmpty() && selectedPositions.none { it in first until first + visibleRows.coerceAtLeast(1) }) {
            first = selectedPositions.first()
        }
        return choiceOptions.getOrNull(first)?.index ?: 0
    }

    /**
     * Validates a new selection and sorts its indices in original option order. Duplicate,
     * unavailable or disallowed indices, mixed text/indices and unresolved source values are
     * refused with null. An empty selection is valid (ISO 32000-1, 12.7.4.4).
     */
    public fun validateChoiceSelection(selection: PdfChoiceSelection): PdfChoiceSelection? {
        if (type != FieldType.Choice || selection.unresolvedValues.isNotEmpty()) return null
        if (selection.freeText != null) {
            return selection.takeIf { isEditableCombo && it.indices.isEmpty() }
        }
        if ((!isMultiSelect && selection.indices.size > 1) || selection.indices.distinct().size != selection.indices.size) return null
        val available = choiceOptions.mapTo(HashSet()) { it.index }
        if (selection.indices.any { it !in available }) return null
        return PdfChoiceSelection(selection.indices.sorted())
    }

    /**
     * Adapts a scalar export value to one option, choosing the first duplicate export. Empty
     * text clears a noneditable field only when it has no empty export option. Other unmatched
     * values are valid only for an editable combo (ISO 32000-1, 12.7.4.4).
     */
    public fun choiceSelectionForValue(value: String): PdfChoiceSelection? {
        if (type != FieldType.Choice) return null
        choiceOptions.firstOrNull { it.exportValue == value }?.let { return PdfChoiceSelection(listOf(it.index)) }
        if (isEditableCombo) return PdfChoiceSelection(emptyList(), freeText = value)
        return if (value.isEmpty()) PdfChoiceSelection(emptyList()) else null
    }

    /** Export values in option order, followed by unresolved source strings; text stays scalar. */
    public fun choiceValues(selection: PdfChoiceSelection): List<String> {
        selection.freeText?.let { return listOf(it) }
        val optionsByIndex = choiceOptions.associateBy { it.index }
        return selection.indices.sorted().mapNotNull { optionsByIndex[it]?.exportValue } + selection.unresolvedValues
    }

    override fun toString(): String = "PdfFormField($fullyQualifiedName, $type, value=$value)"

    public companion object {

        /**
         * Every terminal field of the form. ISO 32000-1, 7.3.10 makes a reference to a missing
         * object the null object, so each read below treats one as absent, and a field that still
         * cannot be read is skipped while the others stay (#441).
         */
        internal fun collect(catalog: PdfDictionary, refs: IndirectResolver): List<PdfFormField> {
            val acro = missingAsNull { catalog.getDict("AcroForm", refs) }
            val acroDA = (acro?.get("DA") as? PdfString)?.asText()
            val acroQ = (acro?.get("Q") as? PdfInt)?.value?.toInt() ?: 0
            val out = ArrayList<PdfFormField>()
            for (item in missingAsNull { acro?.getArray("Fields", refs) } ?: emptyList()) {
                val (dict, ref) = resolveDictRef(item, refs) ?: continue
                skipIfBroken { walk(dict, ref, refs, parentName = null, inhFT = null, inhDA = acroDA, inhFf = 0, inhV = null, inhQ = acroQ, out) }
            }
            collectStrayWidgets(catalog, refs, acroDA, acroQ, out)
            return out
        }

        /** Runs one field's [read], and skips that field with a warning when it fails. */
        private inline fun skipIfBroken(read: () -> Unit) {
            try {
                read()
            } catch (failure: Exception) {
                kiteWarn { "form: a field cannot be read and is skipped: ${failure.message}" }
            }
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
                val annots = missingAsNull { pageNode.getArray("Annots", refs) } ?: continue
                for (item in annots) {
                    val (dict, ref) = resolveDictRef(item, refs) ?: continue
                    if (dict.getName("Subtype") != "Widget") continue
                    if (ref != null && !seen.add(ref)) continue
                    // A widget with no /T of its own belongs to its parent field.
                    val (fieldDict, fieldRef) = ownerOf(dict, ref, refs)
                    if (fieldRef != null && fieldRef != ref && !seen.add(fieldRef)) continue
                    skipIfBroken {
                        walk(
                            fieldDict, fieldRef, refs, parentName = parentChainName(fieldDict, refs),
                            inhFT = null, inhDA = acroDA, inhFf = 0, inhV = null, inhQ = acroQ, out,
                        )
                    }
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
                val parent = (missingAsNull { dict["Parent"]?.resolve(refs) } as? PdfDictionary) ?: break
                dict = parent
                ref = parentRef
                if (parent["T"] != null) break
            }
            return dict to ref
        }

        /** The dotted name of everything above [node], so a stray widget keeps its full name. */
        private fun parentChainName(node: PdfDictionary, refs: IndirectResolver): String? {
            val names = ArrayList<String>()
            var current = missingAsNull { node["Parent"]?.resolve(refs) } as? PdfDictionary
            var guard = 0
            while (current != null && guard++ < MAX_PARENT_DEPTH) {
                (current["T"] as? PdfString)?.asText()?.let { names.add(0, it) }
                current = missingAsNull { current.get("Parent")?.resolve(refs) } as? PdfDictionary
            }
            return names.takeIf { it.isNotEmpty() }?.joinToString(".")
        }

        /** Every page dictionary of the document, without building the page objects. */
        private fun walkPageTree(catalog: PdfDictionary, refs: IndirectResolver): List<PdfDictionary> {
            val root = missingAsNull { catalog.getDict("Pages", refs) } ?: return emptyList()
            val out = ArrayList<PdfDictionary>()
            val queue = ArrayDeque<PdfDictionary>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < MAX_PAGE_NODES) {
                val node = queue.removeFirst()
                val kids = missingAsNull { node.getArray("Kids", refs) }
                if (kids == null) {
                    out.add(node)
                    continue
                }
                for (kid in kids) {
                    (missingAsNull { kid.resolve(refs) } as? PdfDictionary)?.let { queue.add(it) }
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
            val kids = missingAsNull { node.getArray("Kids", refs) }
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
                    caption = (missingAsNull { dict.getDict("MK", refs)?.get("CA")?.resolve(refs) } as? PdfString)?.asText(),
                    borderStyle = borderStyleOf(dict, refs),
                    dict = dict,
                    action = missingAsNull { PdfAction.parse(dict.getDict("A", refs), refs) },
                )
            }
            val (widgetDict, widgetRef) = widgetPairs.first()
            val rect = widgets.first().rect

            val choiceOptions = choiceOptionsOf(node, refs)
            val choice = fieldType(ft) == FieldType.Choice
            val sourceChoice = if (choice) selectionOf(node, "V", choiceOptions, ff, refs, useIndices = true) else PdfChoiceSelection(emptyList())
            val defaultChoice = if (choice) selectionOf(node, "DV", choiceOptions, ff, refs, useIndices = false) else PdfChoiceSelection(emptyList())
            fun scalar(selection: PdfChoiceSelection): String? = selection.freeText
                ?: selection.indices.firstOrNull()?.let { index -> choiceOptions.firstOrNull { it.index == index }?.exportValue }
                ?: selection.unresolvedValues.firstOrNull()
            out.add(
                PdfFormField(
                    fullyQualifiedName = name ?: "",
                    partialName = partial,
                    type = fieldType(ft),
                    value = if (choice) scalar(sourceChoice) else v,
                    defaultAppearance = da,
                    flags = ff,
                    quadding = q,
                    rect = rect,
                    fieldReference = ref,
                    widgetReference = widgetRef,
                    fieldDict = node,
                    widgetDict = widgetDict,
                    options = choiceSnapshot(choiceOptions.map { it.label }),
                    defaultValue = if (choice) scalar(defaultChoice) else inheritedText(node, "DV", refs),
                    maxLength = (inheritedValue(node, "MaxLen", refs) as? PdfInt)?.value?.toInt(),
                    tooltip = (missingAsNull { widgetDict["TU"]?.resolve(refs) } as? PdfString)?.asText(),
                    widgets = widgets,
                ).also { field ->
                    field.parsedChoices = choiceSnapshot(choiceOptions)
                    field.choicePadding = widgets.map { widget ->
                        val mk = missingAsNull { widget.dict.getDict("MK", refs) }
                        val fallback = if (missingAsNull { mk?.getArray("BC", refs) }?.isNotEmpty() == true) 1.0 else 0.0
                        val widthObject = missingAsNull { widget.dict.getDict("BS", refs)?.get("W")?.resolve(refs) }
                            ?: missingAsNull { widget.dict.getArray("Border", refs)?.getOrNull(2)?.resolve(refs) }
                        val width = when (widthObject) {
                            is PdfInt -> widthObject.value.toDouble()
                            is PdfReal -> widthObject.value
                            else -> fallback
                        }
                        (width + 1.0).takeIf { it.isFinite() }?.coerceAtLeast(1.0) ?: 1.0
                    }
                    field.parsedSelection = sourceChoice
                    field.parsedDefaultSelection = defaultChoice
                    field.parsedTopIndex = ((inheritedValue(node, "TI", refs) as? PdfInt)?.value ?: 0L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                },
            )
        }

        /** `/Opt`: skip damaged entries without renumbering the survivors (ISO 32000-1, Table 231). */
        private fun choiceOptionsOf(node: PdfDictionary, refs: IndirectResolver): List<PdfChoiceOption> {
            val opt = inheritedValue(node, "Opt", refs) as? PdfArray ?: return emptyList()
            return opt.mapIndexedNotNull { index, entry ->
                val item = missingAsNull { entry.resolve(refs) }
                val export = when (item) {
                    is PdfString -> item.asText()
                    is PdfArray -> (missingAsNull { item.getOrNull(0)?.resolve(refs) } as? PdfString)?.asText()
                    else -> null
                }
                if (export == null) {
                    kiteWarn { "form: skipped malformed choice option $index" }
                    null
                } else {
                    val label = if (item is PdfArray) (missingAsNull { item.getOrNull(1)?.resolve(refs) } as? PdfString)?.asText() ?: export else export
                    PdfChoiceOption(index, export, label)
                }
            }
        }

        /** `/I` disambiguates exports only when it agrees with `/V`; invalid source values survive. */
        private fun selectionOf(
            node: PdfDictionary, key: String, options: List<PdfChoiceOption>, flags: Int,
            refs: IndirectResolver, useIndices: Boolean,
        ): PdfChoiceSelection {
            val raw = inheritedValue(node, key, refs)
            val values = when (raw) {
                is PdfString -> listOf(raw.asText())
                // The old scalar reader salvaged name objects used where a string was required.
                is PdfName -> listOf(raw.value)
                is PdfArray -> raw.mapNotNull { (missingAsNull { it.resolve(refs) } as? PdfString)?.asText() }
                else -> emptyList()
            }
            val indexed = if (useIndices) (inheritedValue(node, "I", refs) as? PdfArray)?.mapNotNull {
                (missingAsNull { it.resolve(refs) } as? PdfInt)?.value?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
            } else null
            val byIndex = options.associateBy { it.index }
            if (indexed != null && indexed.distinct().size == indexed.size && indexed.all { it in byIndex }) {
                val exports = indexed.sorted().map { byIndex.getValue(it).exportValue }
                val labels = indexed.sorted().map { byIndex.getValue(it).label }
                if ((raw == null && indexed.isNotEmpty()) || exports.sorted() == values.sorted() || labels.sorted() == values.sorted()) {
                    return PdfChoiceSelection(indexed.sorted())
                }
            }
            if (indexed != null) kiteWarn { "form: ignored inconsistent or damaged choice indices" }
            val selected = ArrayList<Int>()
            val unresolved = ArrayList<String>()
            for (value in values) {
                // 12.7.4.4 describes label-valued /V; many writers store exports instead.
                // Recognise both on input while the live/script API consistently uses exports.
                val option = options.firstOrNull { it.exportValue == value && it.index !in selected }
                    ?: options.firstOrNull { it.label == value && it.index !in selected }
                if (option == null) unresolved += value else selected += option.index
            }
            val editable = flags and (1 shl 17) != 0 && flags and (1 shl 18) != 0
            if (editable && selected.isEmpty() && unresolved.size == 1) return PdfChoiceSelection(emptyList(), freeText = unresolved.single())
            if (unresolved.isNotEmpty()) kiteWarn { "form: choice $key preserves ${unresolved.size} unavailable source values" }
            return PdfChoiceSelection(selected.sorted(), unresolvedValues = unresolved)
        }

        /** `/BS /S` as a word a script understands (ISO 32000-1, 12.5.4, Table 166). */
        private fun borderStyleOf(dict: PdfDictionary, refs: IndirectResolver): String? =
            when ((missingAsNull { dict.getDict("BS", refs)?.get("S")?.resolve(refs) } as? PdfName)?.value) {
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
                val dict: PdfDictionary = current
                missingAsNull { dict[key]?.resolve(refs) }?.let { return it }
                current = missingAsNull { dict["Parent"]?.resolve(refs) } as? PdfDictionary
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
                val child: PdfDictionary = node
                node = missingAsNull { child["Parent"]?.resolve(refs) } as? PdfDictionary
            }
            return names.takeIf { it.isNotEmpty() }?.joinToString(".")
        }

        /**
         * The `/AP /N` state that turns this widget on: the one name that is not `Off`
         * (ISO 32000-1 §12.7.4.2.1). Null when the widget has no named appearance states.
         */
        private fun onStateNameOf(dict: PdfDictionary, refs: IndirectResolver): String? {
            val normal = missingAsNull { dict.getDict("AP", refs)?.get("N")?.resolve(refs) } as? PdfDictionary ?: return null
            return normal.map.keys.firstOrNull { it != "Off" }
        }

        private fun resolveDictRef(item: PdfObject, refs: IndirectResolver): Pair<PdfDictionary, PdfReference?>? =
            when (item) {
                is PdfReference -> (refs.resolve(item) as? PdfDictionary)?.let { it to item }
                is PdfDictionary -> item to null
                else -> null
            }

        private fun valueToString(value: PdfObject, refs: IndirectResolver): String? =
            when (val v = missingAsNull { value.resolve(refs) }) {
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
