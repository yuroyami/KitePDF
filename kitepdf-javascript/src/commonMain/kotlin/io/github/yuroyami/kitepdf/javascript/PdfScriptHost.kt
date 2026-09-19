package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine

/** A message a script asked to show, through `app.alert`. */
public class PdfScriptAlert internal constructor(
    /** The text to show. */
    public val message: String,
    /** The icon Acrobat asks for: 0 error, 1 warning, 2 question, 3 status. */
    public val icon: Int,
    /** The buttons: 0 OK, 1 OK and cancel, 2 yes and no, 3 yes, no and cancel. */
    public val buttons: Int,
    /** The window title, empty when the script gave none. */
    public val title: String,
)

/** A question a script asked, through `app.response`. */
public class PdfScriptPrompt internal constructor(
    public val question: String,
    public val title: String,
    public val defaultValue: String,
    /** True when the script asked for the answer to be masked. */
    public val isPassword: Boolean,
)

/**
 * The Kotlin half of the object model: everything the bundled JavaScript asks the document for.
 *
 * The scripts see Acrobat's objects, which are built in [AcrobatApi]. Those objects call back
 * here, under the name `__kite`, for anything that depends on the file: a field's value, the page
 * count, a timer, a message for the reader. Keeping the split here means the engine never learns
 * about PDF, and the object model can run on any engine that binds functions.
 */
internal class PdfScriptHost(
    private val document: PdfDocument,
    private val state: PdfFormState,
    private val hooks: Hooks,
) {

    /** What the host application does with the things a script asks for. */
    internal class Hooks(
        val onAlert: (PdfScriptAlert) -> Int,
        val onConsole: (String) -> Unit,
        val onRequest: (PdfScriptRequest) -> Unit,
        val onResponse: (PdfScriptPrompt) -> String?,
    )

    /** A timer a script set with `app.setInterval` or `app.setTimeOut`. */
    private class Timer(val id: Int, val code: String, val period: Long, val repeats: Boolean, var dueAt: Long)

    private val timers = LinkedHashMap<Int, Timer>()
    private var nextTimerId = 1

    /** What the last event's script left behind, reported by the JavaScript side. */
    var lastEventResult: EventResult = EventResult(rc = true, value = "", change = "")
        private set

    internal class EventResult(val rc: Boolean, val value: String, val change: String)

    fun bind(engine: KiteScriptEngine) {
        engine.defineFunction("__kite.hasField") { args -> document.formField(args.str(0)) != null }
        engine.defineFunction("__kite.fieldProp") { args -> fieldProp(args.str(0), args.str(1)) }
        engine.defineFunction("__kite.setFieldProp") { args -> setFieldProp(args.str(0), args.str(1), args.getOrNull(2)); null }
        engine.defineFunction("__kite.nthFieldName") { args ->
            document.formFields.getOrNull(args.int(0))?.fullyQualifiedName ?: ""
        }
        engine.defineFunction("__kite.itemAt") { args -> itemAt(args.str(0), args.int(1), args.bool(2)) }
        engine.defineFunction("__kite.setItems") { args -> setItems(args.str(0), args.getOrNull(1)); null }
        engine.defineFunction("__kite.insertItem") { args -> insertItem(args.str(0), args.str(1), args.int(3)); null }
        engine.defineFunction("__kite.deleteItem") { args -> deleteItem(args.str(0), args.int(1)); null }
        engine.defineFunction("__kite.docProp") { args -> docProp(args.str(0)) }
        engine.defineFunction("__kite.pageWords") { args -> pageWords(args.int(0)) }
        engine.defineFunction("__kite.pageBox") { args -> pageBox(args.int(1)) }
        engine.defineFunction("__kite.console") { args -> hooks.onConsole(args.str(0)); null }
        engine.defineFunction("__kite.alert") { args -> alert(args.map(0)).toDouble() }
        engine.defineFunction("__kite.response") { args -> response(args.map(0)) }
        engine.defineFunction("__kite.setTimer") { args ->
            setTimer(args.str(0), args.double(1).toLong(), args.bool(2)).toDouble()
        }
        engine.defineFunction("__kite.clearTimer") { args -> clearTimer(args.double(0).toInt()); null }
        engine.defineFunction("__kite.action") { args -> action(args.str(0), args.map(1)); null }
        engine.defineFunction("__kite.eventResult") { args ->
            val map = args.map(0)
            lastEventResult = EventResult(
                rc = map["rc"] as? Boolean ?: true,
                value = map["value"]?.let { text(it) } ?: "",
                change = map["change"]?.let { text(it) } ?: "",
            )
            null
        }
    }

    /* ─── fields ────────────────────────────────────────────────────────── */

    private fun fieldProp(name: String, prop: String): Any? {
        val field = document.formField(name) ?: return null
        return when (prop) {
            "value" -> state.value(name) ?: ""
            "type" -> typeName(field)
            "readonly" -> state.isReadOnly(name)
            "hidden" -> state.isHidden(name)
            "required" -> (field.flags and REQUIRED) != 0
            "multiline" -> field.isMultiline
            "page" -> document.pageIndexOfField(field)?.toDouble() ?: -1.0
            "rect" -> field.rect?.let { listOf(it.left, it.top, it.right, it.bottom) }
            "numItems" -> field.options.size.toDouble()
            "defaultValue" -> field.defaultValue ?: ""
            "charLimit" -> (field.maxLength ?: 0).toDouble()
            "alignment" -> when (field.quadding) { 1 -> "center"; 2 -> "right"; else -> "left" }
            "comb" -> (field.flags and COMB) != 0
            "editable" -> (field.flags and EDITABLE) != 0
            "password" -> (field.flags and PASSWORD) != 0
            "checked" -> isChecked(field)
            "defaultChecked" -> (field.defaultValue ?: "").let { it.isNotEmpty() && it != "Off" }
            "caption" -> field.widgets.firstOrNull()?.caption ?: ""
            "currentValueIndices" -> field.options.indexOf(state.value(name)).toDouble()
            "userName" -> field.tooltip ?: ""
            "textSize" -> textSizeOf(field)
            "borderStyle" -> field.widgets.firstOrNull()?.borderStyle ?: "solid"
            "lineWidth" -> 1.0
            "print" -> true
            else -> null
        }
    }

    private fun setFieldProp(name: String, prop: String, value: Any?) {
        val field = document.formField(name) ?: return
        when (prop) {
            "value" -> state.setValue(name, text(value))
            "hidden" -> state.setHidden(name, value == true)
            "readonly" -> state.setReadOnly(name, value == true)
            "checked" -> state.setValue(name, if (value == true) onStateOf(field) else "Off")
            "currentValueIndices" -> {
                val index = (value as? Double)?.toInt() ?: return
                field.options.getOrNull(index)?.let { state.setValue(name, it) }
            }
            // The rest are appearance details the renderer does not read yet. A script that sets
            // them must not fail, so they are accepted and ignored.
            else -> Unit
        }
    }

    private fun typeName(field: PdfFormField): String = when (field.type) {
        PdfFormField.FieldType.Text -> "text"
        PdfFormField.FieldType.Signature -> "signature"
        PdfFormField.FieldType.Choice -> if ((field.flags and COMBO) != 0) "combobox" else "listbox"
        PdfFormField.FieldType.Button -> when {
            (field.flags and PUSH_BUTTON) != 0 -> "button"
            (field.flags and RADIO) != 0 -> "radiobutton"
            else -> "checkbox"
        }
        PdfFormField.FieldType.Unknown -> "text"
    }



    /** A check box or radio button is on when its value is a state name other than `Off`. */
    private fun isChecked(field: PdfFormField): Boolean {
        val value = state.value(field.fullyQualifiedName) ?: return false
        return value.isNotEmpty() && value != "Off"
    }

    /** The state name that turns this field on, which a script sets through `checkThisBox`. */
    private fun onStateOf(field: PdfFormField): String =
        field.widgets.firstOrNull { it.onStateName != null }?.onStateName ?: "Yes"



    private fun itemAt(name: String, index: Int, exportValue: Boolean): String =
        document.formField(name)?.options?.getOrNull(index) ?: ""

    /** The size in the field's `/DA` string, or 0 when it asks the viewer to fit the box. */
    private fun textSizeOf(field: PdfFormField): Double {
        val parts = (field.defaultAppearance ?: return 0.0).trim().split(Regex("\\s+"))
        val index = parts.indexOf("Tf")
        return if (index > 0) parts[index - 1].toDoubleOrNull() ?: 0.0 else 0.0
    }

    /** Choice lists are part of the file rather than of the live state, so changing them waits for the editor. */
    private fun setItems(name: String, items: Any?) = Unit

    private fun insertItem(name: String, item: String, index: Int) = Unit

    private fun deleteItem(name: String, index: Int) = Unit

    /* ─── the document ──────────────────────────────────────────────────── */

    private fun docProp(name: String): Any? = when (name) {
        "numPages" -> document.pageCount.toDouble()
        "numFields" -> document.formFields.size.toDouble()
        "pageNum" -> currentPage.toDouble()
        "title" -> document.info.title ?: ""
        "author" -> document.info.author ?: ""
        "subject" -> document.info.subject ?: ""
        "keywords" -> document.info.keywords ?: ""
        "creator" -> document.info.creator ?: ""
        "producer" -> document.info.producer ?: ""
        "creationDate" -> document.info.creationDate?.toString() ?: ""
        "modDate" -> document.info.modDate?.toString() ?: ""
        "documentFileName" -> fileName
        "path" -> fileName
        "URL" -> ""
        "baseURL" -> ""
        "filesize" -> 0.0
        "viewerVersion" -> 8.0
        "language" -> document.language ?: "ENU"
        "platform" -> "KitePDF"
        "security" -> if (document.isEncrypted) "Password Security" else "None"
        "permStatusReady" -> true
        "info" -> mapOf(
            "Title" to (document.info.title ?: ""),
            "Author" to (document.info.author ?: ""),
            "Subject" to (document.info.subject ?: ""),
            "Keywords" to (document.info.keywords ?: ""),
            "Creator" to (document.info.creator ?: ""),
            "Producer" to (document.info.producer ?: ""),
            "CreationDate" to (document.info.creationDate?.toString() ?: ""),
            "ModDate" to (document.info.modDate?.toString() ?: ""),
        )
        else -> null
    }

    /** The page the reader is on, which a viewer keeps up to date. */
    var currentPage: Int = 0

    /** The name a host gave the file, for `this.documentFileName`. */
    var fileName: String = ""

    private fun pageWords(pageIndex: Int): List<String> {
        val page = document.pages.getOrNull(pageIndex) ?: return emptyList()
        return page.extractText().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun pageBox(pageIndex: Int): List<Double> {
        val page = document.pages.getOrNull(pageIndex) ?: return listOf(0.0, 0.0, 0.0, 0.0)
        val box = page.cropBox
        return listOf(box.left, box.top, box.right, box.bottom)
    }

    /* ─── what a script asks the host for ───────────────────────────────── */

    private fun alert(options: Map<String, Any?>): Int = hooks.onAlert(
        PdfScriptAlert(
            message = options["message"]?.let { text(it) } ?: "",
            icon = (options["icon"] as? Double)?.toInt() ?: 0,
            buttons = (options["type"] as? Double)?.toInt() ?: 0,
            title = options["title"]?.let { text(it) } ?: "",
        ),
    )

    private fun response(options: Map<String, Any?>): String? = hooks.onResponse(
        PdfScriptPrompt(
            question = options["question"]?.let { text(it) } ?: "",
            title = options["title"]?.let { text(it) } ?: "",
            defaultValue = options["defaultValue"]?.let { text(it) } ?: "",
            isPassword = options["password"] == true,
        ),
    )

    private fun action(kind: String, options: Map<String, Any?>) {
        val request = when (kind) {
            "launchURL" -> PdfScriptRequest.LaunchUrl(options["url"]?.let { text(it) } ?: "")
            "submitForm" -> PdfScriptRequest.SubmitForm(options["url"]?.let { text(it) } ?: "")
            "print" -> PdfScriptRequest.Print
            "mailDoc", "mailForm", "mailMsg" -> PdfScriptRequest.Mail
            "beep" -> PdfScriptRequest.Beep
            "closeDoc", "saveAs" -> PdfScriptRequest.Document(kind)
            "gotoPage" -> PdfScriptRequest.GoTo((options["page"] as? Double)?.toInt(), null)
            "gotoNamedDest" -> PdfScriptRequest.GoTo(null, options["name"]?.let { text(it) })
            "setFocus" -> PdfScriptRequest.Focus(options["field"]?.let { text(it) } ?: "")
            "execMenuItem" -> PdfScriptRequest.MenuItem(options["item"]?.let { text(it) } ?: "")
            "resetForm" -> {
                resetForm(options["fields"])
                return
            }
            "calculateNow" -> {
                calculateNow?.invoke()
                return
            }
            else -> return
        }
        hooks.onRequest(request)
    }

    /** Set by the runner, because a calculation round is the runner's own job. */
    var calculateNow: (() -> Unit)? = null

    private fun resetForm(fields: Any?) {
        val names = (fields as? List<*>)?.mapNotNull { it?.let { v -> text(v) } }
        if (names.isNullOrEmpty()) state.resetAll() else names.forEach { state.reset(it) }
    }

    /* ─── timers ────────────────────────────────────────────────────────── */

    private fun setTimer(code: String, period: Long, repeats: Boolean): Int {
        val id = nextTimerId++
        // Acrobat takes the period in milliseconds and a zero means "as often as you can", which
        // a viewer answers at its own frame rate rather than in a loop.
        val safePeriod = if (period <= 0) 0L else period
        timers[id] = Timer(id, code, safePeriod, repeats, clockMillis + safePeriod)
        return id
    }

    private fun clearTimer(id: Int) {
        timers.remove(id)
    }

    /** The timers due at [now], oldest first, and the queue is updated for the next round. */
    fun dueTimers(now: Long): List<String> {
        clockMillis = now
        val due = timers.values.filter { it.dueAt <= now }.sortedBy { it.dueAt }
        for (timer in due) {
            if (timer.repeats) timer.dueAt = now + timer.period else timers.remove(timer.id)
        }
        return due.map { it.code }
    }

    /** When the next timer is due, or null when none is waiting. */
    fun nextTimerDue(): Long? = timers.values.minOfOrNull { it.dueAt }

    /** True when a script has a timer waiting. */
    val hasTimers: Boolean get() = timers.isNotEmpty()

    private var clockMillis: Long = 0

    /* ─── small conversions ─────────────────────────────────────────────── */

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun List<Any?>.str(index: Int): String = text(getOrNull(index))
    private fun List<Any?>.int(index: Int): Int = (getOrNull(index) as? Double)?.toInt() ?: 0
    private fun List<Any?>.double(index: Int): Double = (getOrNull(index) as? Double) ?: 0.0
    private fun List<Any?>.bool(index: Int): Boolean = getOrNull(index) == true

    @Suppress("UNCHECKED_CAST")
    private fun List<Any?>.map(index: Int): Map<String, Any?> =
        (getOrNull(index) as? Map<String, Any?>) ?: emptyMap()

    private companion object {
        const val REQUIRED = 1 shl 1
        const val PUSH_BUTTON = 1 shl 16
        const val RADIO = 1 shl 15
        const val COMBO = 1 shl 17
        const val EDITABLE = 1 shl 18
        const val PASSWORD = 1 shl 13
        const val COMB = 1 shl 24
    }
}
