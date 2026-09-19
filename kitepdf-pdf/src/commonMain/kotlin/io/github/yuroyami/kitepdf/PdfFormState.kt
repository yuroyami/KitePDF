package io.github.yuroyami.kitepdf

/**
 * The live values of a document's form fields, while the reader has it open.
 *
 * A file's own values are what [PdfFormField.value] reports, and they never
 * change. This holds what the form looks like now: what the reader typed, what
 * a script wrote, which box is ticked. A viewer draws from it, a script reads
 * and writes it, and [io.github.yuroyami.kitepdf.writer.PdfEditor] saves it.
 *
 * Nothing here touches the file. Saving is a separate step, so a reader can fill
 * a form, change its mind, and close without writing anything.
 *
 * ```kotlin
 * val state = PdfFormState(doc)
 * state.setValue("total", "42")
 * state.value("total")          // "42"
 * doc.formField("total")?.value // still what the file says
 * ```
 *
 * One instance belongs to one document and to one thread. A viewer keeps it for
 * as long as the document is open, and reads [revision] to know when to redraw.
 */
public class PdfFormState(private val document: PdfDocument) {

    private val values = HashMap<String, String>()
    private val hidden = HashMap<String, Boolean>()
    private val readOnly = HashMap<String, Boolean>()
    private val listeners = ArrayList<(Change) -> Unit>()

    /**
     * How many changes this state has seen. A viewer that keeps a drawn page can
     * compare it with the number it drew at to know whether anything moved.
     */
    public var revision: Int = 0
        private set

    /** What changed, for a listener that redraws only the widgets that moved. */
    public class Change internal constructor(
        /** The fully qualified name of the field that changed. */
        public val fieldName: String,
        /** What the field shows now. */
        public val value: String,
        /** True when the change was visibility or read-only rather than the value. */
        public val flagsOnly: Boolean = false,
    )

    /** The value the reader sees: what this state holds, or the file's own value. */
    public fun value(fieldName: String): String? =
        values[fieldName] ?: document.formField(fieldName)?.value

    /** True when this state has its own value for the field, so the file's is out of date. */
    public fun isChanged(fieldName: String): Boolean = fieldName in values

    /** Every field this state has a value for, in the order they were first set. */
    public val changedFields: Set<String> get() = values.keys.toSet()

    /**
     * Sets the value shown for [fieldName]. Does nothing when the document has no
     * such field, so a script that names a missing field cannot grow the state.
     */
    public fun setValue(fieldName: String, value: String) {
        if (document.formField(fieldName) == null) return
        if (values[fieldName] == value) return
        values[fieldName] = value
        revision++
        publish(Change(fieldName, value))
    }

    /** Drops this state's value for [fieldName], so the file's own value shows again. */
    public fun reset(fieldName: String) {
        if (values.remove(fieldName) == null) return
        revision++
        publish(Change(fieldName, value(fieldName) ?: ""))
    }

    /** Drops every value, visibility and read-only change this state holds. */
    public fun resetAll() {
        if (values.isEmpty() && hidden.isEmpty() && readOnly.isEmpty()) return
        val touched = values.keys + hidden.keys + readOnly.keys
        values.clear()
        hidden.clear()
        readOnly.clear()
        revision++
        for (name in touched) publish(Change(name, value(name) ?: ""))
    }

    /**
     * Whether the field is drawn: what this state says, or the Hidden flag of the
     * field's own widget (ISO 32000-1 §12.5.3, Table 165, bit 2). A script changes
     * it through `field.display` or `field.hidden`.
     */
    public fun isHidden(fieldName: String): Boolean {
        hidden[fieldName]?.let { return it }
        val widget = document.formField(fieldName)?.widgets?.firstOrNull() ?: return false
        val flags = (widget.dict["F"]?.resolve(document) as? io.github.yuroyami.kitepdf.core.parser.PdfInt)
            ?.value?.toInt() ?: 0
        return (flags and HIDDEN_FLAG) != 0
    }

    /** Hides or shows the field, whatever the file's own flag says. */
    public fun setHidden(fieldName: String, value: Boolean) {
        if (document.formField(fieldName) == null) return
        if (isHidden(fieldName) == value && hidden.containsKey(fieldName)) return
        hidden[fieldName] = value
        revision++
        publish(Change(fieldName, this.value(fieldName) ?: "", flagsOnly = true))
    }

    /** Whether the reader may change the field: the file's own flag, unless this state says otherwise. */
    public fun isReadOnly(fieldName: String): Boolean =
        readOnly[fieldName] ?: (document.formField(fieldName)?.isReadOnly == true)

    /** Makes the field read-only, or gives it back to the reader. */
    public fun setReadOnly(fieldName: String, value: Boolean) {
        if (document.formField(fieldName) == null) return
        if (readOnly[fieldName] == value) return
        readOnly[fieldName] = value
        revision++
        publish(Change(fieldName, this.value(fieldName) ?: "", flagsOnly = true))
    }

    /**
     * Calls [listener] after every change, with the field that moved. Returns a
     * function that stops the listening.
     */
    public fun onChange(listener: (Change) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private companion object {
        /** `/F` bit 2: the annotation is not displayed. */
        private const val HIDDEN_FLAG = 1 shl 1
    }

    private fun publish(change: Change) {
        // A listener that throws must not stop the others, and must not stop a script.
        for (listener in listeners.toList()) {
            try {
                listener(change)
            } catch (e: RuntimeException) {
                io.github.yuroyami.kitepdf.core.kiteWarn { "form state listener failed: ${e.message}" }
            }
        }
    }
}
