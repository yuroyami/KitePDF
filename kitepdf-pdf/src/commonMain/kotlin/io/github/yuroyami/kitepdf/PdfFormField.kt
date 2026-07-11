package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.Rectangle

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
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
    /** The widget's `/Rect`, or null when this terminal field has no widget. */
    public val rect: Rectangle?,
    /** Indirect reference to the field dictionary, for editing. */
    public val fieldReference: PdfReference?,
    /** Indirect reference to the widget annotation (== [fieldReference] when merged). */
    public val widgetReference: PdfReference?,
    internal val fieldDict: PdfDictionary,
    internal val widgetDict: PdfDictionary,
) {
    public enum class FieldType { Text, Button, Choice, Signature, Unknown }

    /** `/Ff` bit 1 — the field is read-only. */
    public val isReadOnly: Boolean get() = (flags and 0b1) != 0

    /** `/Ff` bit 13 (text fields) — multi-line. */
    public val isMultiline: Boolean get() = type == FieldType.Text && (flags and (1 shl 12)) != 0

    override fun toString(): String = "PdfFormField($fullyQualifiedName, $type, value=$value)"

    public companion object {

        internal fun collect(catalog: PdfDictionary, refs: IndirectResolver): List<PdfFormField> {
            val acro = catalog.getDict("AcroForm", refs) ?: return emptyList()
            val roots = acro.getArray("Fields", refs) ?: return emptyList()
            val acroDA = (acro["DA"] as? PdfString)?.asText()
            val acroQ = (acro["Q"] as? PdfInt)?.value?.toInt() ?: 0
            val out = ArrayList<PdfFormField>()
            for (item in roots) {
                val (dict, ref) = resolveDictRef(item, refs) ?: continue
                walk(dict, ref, refs, parentName = null, inhFT = null, inhDA = acroDA, inhFf = 0, inhV = null, inhQ = acroQ, out)
            }
            return out
        }

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

            // Terminal field. Its widget is either this same dict (merged) or its
            // first widget kid.
            val (widgetDict, widgetRef) = when {
                node.getName("Subtype") == "Widget" || node["Rect"] != null -> node to ref
                else -> kidPairs.firstOrNull() ?: (node to ref)
            }
            val rect = widgetDict.getArray("Rect")?.takeIf { it.size >= 4 }?.let { Rectangle.fromPdfArray(it) }

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
                ),
            )
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
