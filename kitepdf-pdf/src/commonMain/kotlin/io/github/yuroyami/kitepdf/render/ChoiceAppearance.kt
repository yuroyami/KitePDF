package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.writer.FieldAppearance
import io.github.yuroyami.kitepdf.writer.FieldText
import io.github.yuroyami.kitepdf.writer.PdfObjectWriter
import io.github.yuroyami.kitepdf.writer.StandardFont

/** Live choice appearances preserve export/display distinctions (ISO 32000-1, 12.7.4.4). */
internal object ChoiceAppearance {
    fun build(
        field: PdfFormField, selection: PdfChoiceSelection, widget: PdfDictionary,
        width: Double, height: Double, refs: IndirectResolver,
    ): PdfStream? {
        val display = selection.freeText ?: selection.indices.firstOrNull()?.let { index ->
            field.choiceOptions.firstOrNull { it.index == index }?.label
        } ?: selection.unresolvedValues.firstOrNull() ?: ""
        if (field.isCombo) return FieldAppearance.synthesize(widget, width, height, refs, valueOverride = display)
        val base = FieldAppearance.synthesize(widget, width, height, refs, valueOverride = "") ?: return null
        val da = FieldAppearance.parseDA(field.defaultAppearance)
        val size = if (da.fontSize > 0.0) da.fontSize else 12.0
        val rowHeight = field.choiceRowHeight
        val pad = field.choiceContentPadding(field.widgets.indexOfFirst { it.dict === widget })
        val rowCount = ((height - 2 * pad) / rowHeight).toInt().coerceAtLeast(1)
        val first = field.choiceOptions.indexOfFirst { it.index == field.choiceTopIndexFor(selection, rowCount) }.coerceAtLeast(0)
        val out = ByteArrayBuilder()
        out.append(base.rawBytes)
        fun emit(text: String) { out.append(text.encodeToByteArray()) }
        fun fmt(value: Double): String = PdfObjectWriter.formatReal(value)
        emit("q\n${fmt(pad)} ${fmt(pad)} ${fmt((width - 2 * pad).coerceAtLeast(0.0))} ${fmt((height - 2 * pad).coerceAtLeast(0.0))} re W n\n")
        for ((row, option) in field.choiceOptions.drop(first).take(rowCount + 1).withIndex()) {
            val bottom = height - pad - (row + 1) * rowHeight
            val selected = option.index in selection.indices
            if (selected) emit("0.16 0.36 0.72 rg ${fmt(pad)} ${fmt(bottom)} ${fmt((width - 2 * pad).coerceAtLeast(0.0))} ${fmt(rowHeight)} re f\n")
            emit("q 1 0 0 1 0 ${fmt(bottom)} cm\n")
            val paragraphs = FieldAppearance.plainParagraphs(option.label, StandardFont.Helvetica, size, if (selected) "1 g" else da.colorOps, field.quadding)
            FieldText.write(FieldText.lines(paragraphs, width - 2 * pad, wrap = false), width, rowHeight, pad, false, out) { da.fontName }
            emit("Q\n")
        }
        if (selection.unresolvedValues.isNotEmpty()) {
            // A damaged source remains legible even when no row can represent its value.
            val paragraphs = FieldAppearance.plainParagraphs(selection.unresolvedValues.joinToString(" "), StandardFont.Helvetica, size, da.colorOps, field.quadding)
            FieldText.write(FieldText.lines(paragraphs, width - 2 * pad, false), width, rowHeight, pad, false, out) { da.fontName }
        }
        emit("Q\n")
        val font = PdfDictionary(linkedMapOf("Type" to PdfName("Font"), "Subtype" to PdfName("Type1"), "BaseFont" to PdfName("Helvetica"), "Encoding" to PdfName("WinAnsiEncoding")))
        val dict = PdfDictionary(LinkedHashMap(base.dict.map).also {
            it["Resources"] = PdfDictionary(linkedMapOf("Font" to PdfDictionary(linkedMapOf(da.fontName to font))))
        })
        return PdfStream(dict, out.toByteArray())
    }
}
