package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.withLock

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
 * A viewer keeps one for as long as the document is open, and reads [revision] to know when to
 * redraw. Reads and writes are safe from two threads, because a document's scripts run on a
 * thread of their own while the viewer draws on its own: the engine that runs the scripts is
 * confined to one thread, but the values it writes are read by another.
 */
public class PdfFormState(private val document: PdfDocument) {

    private val lock = io.github.yuroyami.kitepdf.core.KiteLock()
    private val values = HashMap<String, String>()
    private val hidden = HashMap<String, Boolean>()
    private val readOnly = HashMap<String, Boolean>()
    private val listeners = ArrayList<(Change) -> Unit>()

    /**
     * How many changes this state has seen. A viewer that keeps a drawn page can
     * compare it with the number it drew at to know whether anything moved. It is read
     * under the same lock that guards the writes, so a thread that did not write it still
     * sees the latest number (#364).
     */
    public val revision: Int get() = lock.withLock { changes }

    private var changes = 0

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
        lock.withLock { values[fieldName] } ?: document.formField(fieldName)?.value

    /** True when this state has its own value for the field, so the file's is out of date. */
    public fun isChanged(fieldName: String): Boolean = lock.withLock { fieldName in values }

    /** Every field this state has a value for, in the order they were first set. */
    public val changedFields: Set<String> get() = lock.withLock { values.keys.toSet() }

    /**
     * Sets the value shown for [fieldName]. Does nothing when the document has no
     * such field, so a script that names a missing field cannot grow the state.
     */
    public fun setValue(fieldName: String, value: String) {
        if (document.formField(fieldName) == null) return
        val changed = lock.withLock {
            if (values[fieldName] == value) false else { values[fieldName] = value; changes++; true }
        }
        if (changed) publish(Change(fieldName, value))
    }

    /** Drops this state's value for [fieldName], so the file's own value shows again. */
    public fun reset(fieldName: String) {
        val removed = lock.withLock {
            if (values.remove(fieldName) == null) false else { changes++; true }
        }
        if (removed) publish(Change(fieldName, value(fieldName) ?: ""))
    }

    /** Drops every value, visibility and read-only change this state holds. */
    public fun resetAll() {
        val touched = lock.withLock {
            if (values.isEmpty() && hidden.isEmpty() && readOnly.isEmpty()) return
            val names = values.keys + hidden.keys + readOnly.keys
            values.clear()
            hidden.clear()
            readOnly.clear()
            changes++
            names
        }
        for (name in touched) publish(Change(name, value(name) ?: ""))
    }

    /**
     * Whether the field is drawn: what this state says, or the Hidden flag of the
     * field's own widget (ISO 32000-1 §12.5.3, Table 165, bit 2). A script changes
     * it through `field.display` or `field.hidden`.
     */
    public fun isHidden(fieldName: String): Boolean {
        lock.withLock { hidden[fieldName] }?.let { return it }
        val widget = document.formField(fieldName)?.widgets?.firstOrNull() ?: return false
        val flags = (widget.dict["F"]?.resolve(document) as? io.github.yuroyami.kitepdf.core.parser.PdfInt)
            ?.value?.toInt() ?: 0
        return (flags and HIDDEN_FLAG) != 0
    }

    /** What this state says about the field's visibility, or null when it leaves that to the file. */
    internal fun hiddenOverride(fieldName: String): Boolean? = lock.withLock { hidden[fieldName] }

    /**
     * Gives each selected field its default value (`/DV`) back, or no value when it has none, as
     * a reset-form action and a script's `resetForm` do (ISO 32000-1, 12.7.5.3). A name selects
     * that field and every field below it, so `address` selects `address.street`. With
     * [exclude], every field that the names do not select is reset instead. With no names at
     * all, every field is reset. A push button and a signature keep theirs, and visibility and
     * read-only changes stay (#439).
     */
    public fun resetForm(fields: Collection<String>? = null, exclude: Boolean = false) {
        for (field in document.formFields) {
            val name = field.fullyQualifiedName
            if (fields != null) {
                val named = fields.any { name == it || name.startsWith("$it.") }
                if (named == exclude) continue
            }
            if (field.type == PdfFormField.FieldType.Signature) continue
            if (field.type == PdfFormField.FieldType.Button && (field.flags and PUSH_BUTTON_FLAG) != 0) continue
            val empty = if (field.type == PdfFormField.FieldType.Button) "Off" else ""
            val default = field.defaultValue ?: empty
            // When the file's own value is the default, the file's appearance shows it as it is.
            if ((field.value ?: empty) == default) reset(name) else setValue(name, default)
        }
    }

    /**
     * Performs a reset-form action: the fields it names, by name or by reference, or every field
     * but those when its Include/Exclude flag is set, or every field when it names none. See the
     * other [resetForm].
     */
    public fun resetForm(action: PdfAction.ResetForm) {
        val names = action.fields?.mapNotNull { entry ->
            // A reference that leads nowhere names no field.
            when (val item = if (entry is PdfReference) document.resolve(entry) else entry) {
                is PdfString -> item.asText()
                is PdfDictionary -> runCatching { PdfFormField.qualifiedNameOf(item, document) }.getOrNull()
                else -> null
            }
        }
        resetForm(names, exclude = names != null && (action.flags and INCLUDE_EXCLUDE_FLAG) != 0)
    }

    /** Hides or shows the field, whatever the file's own flag says. */
    public fun setHidden(fieldName: String, value: Boolean) {
        if (document.formField(fieldName) == null) return
        val changed = lock.withLock {
            if (hidden[fieldName] == value) false else { hidden[fieldName] = value; changes++; true }
        }
        if (changed) publish(Change(fieldName, this.value(fieldName) ?: "", flagsOnly = true))
    }

    /** Whether the reader may change the field: the file's own flag, unless this state says otherwise. */
    public fun isReadOnly(fieldName: String): Boolean =
        lock.withLock { readOnly[fieldName] } ?: (document.formField(fieldName)?.isReadOnly == true)

    /** Makes the field read-only, or gives it back to the reader. */
    public fun setReadOnly(fieldName: String, value: Boolean) {
        if (document.formField(fieldName) == null) return
        val changed = lock.withLock {
            if (readOnly[fieldName] == value) false else { readOnly[fieldName] = value; changes++; true }
        }
        if (changed) publish(Change(fieldName, this.value(fieldName) ?: "", flagsOnly = true))
    }

    /**
     * Calls [listener] after every change, with the field that moved. Returns a
     * function that stops the listening.
     */
    public fun onChange(listener: (Change) -> Unit): () -> Unit {
        lock.withLock { listeners.add(listener) }
        return { lock.withLock { listeners.remove(listener) } }
    }

    private companion object {
        /** `/F` bit 2: the annotation is not displayed. */
        private const val HIDDEN_FLAG = 1 shl 1

        /** `/Ff` bit 17 of a button field: a push button, which holds no value. */
        private const val PUSH_BUTTON_FLAG = 1 shl 16

        /** Reset-form `/Flags` bit 1: reset every field except the named ones (ISO 32000-1, Table 239). */
        private const val INCLUDE_EXCLUDE_FLAG = 1
    }

    private fun publish(change: Change) {
        // A listener that throws must not stop the others, and must not stop a script.
        for (listener in lock.withLock { listeners.toList() }) {
            try {
                listener(change)
            } catch (e: RuntimeException) {
                io.github.yuroyami.kitepdf.core.kiteWarn { "form state listener failed: ${e.message}" }
            }
        }
    }
}
