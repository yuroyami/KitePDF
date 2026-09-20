package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException

/**
 * Runs the JavaScript a PDF carries: its document scripts, the actions of its pages and buttons,
 * and the four scripts a form field runs while it is filled.
 *
 * Scripts see the objects Acrobat defines and Chrome's PDF engine implements: the document's own
 * members as globals, `getField`, `event`, `app`, `util`, `color`, `console`, and the `AF` helper
 * library every form tool calls. A script that works in Chrome's viewer is meant to work here.
 *
 * ```kotlin
 * val state = PdfFormState(doc)
 * PdfScriptRunner(doc, state, onAlert = { alert -> showDialog(alert.message); 1 }).use { runner ->
 *     runner.runDocumentOpen()            // document scripts, then the open action
 *     runner.setFieldValue("price", "12") // keystroke, validate, calculate, format
 *     state.value("total")                // what the form's own script worked out
 * }
 * ```
 *
 * Nothing here touches the file. Values land in [formState], which a viewer draws and the editor
 * saves. One runner belongs to one document and to one thread, because the engine does.
 *
 * Scripts are untrusted input, so [policy] decides whether they run at all and how long they may
 * take, and anything that reaches outside the document arrives at [onRequest] for the host to
 * allow or refuse.
 */
public class PdfScriptRunner(
    private val document: PdfDocument,
    /** Where field values live while the reader has the file open. */
    override val formState: PdfFormState = PdfFormState(document),
    /** What the document's scripts are allowed to do. */
    public val policy: PdfScriptPolicy = PdfScriptPolicy(),
    /** Shows `app.alert`, and answers which button the reader pressed (1 is OK). */
    onAlert: (PdfScriptAlert) -> Int = { 1 },
    /** Receives `console.println`. Chrome's engine drops these; a host may want them. */
    onConsole: (String) -> Unit = {},
    /** Receives everything a script asks for outside the document, such as a link or a submit. */
    onRequest: (PdfScriptRequest) -> Unit = {},
    /** Answers `app.response`, or null when the reader cancelled. */
    onResponse: (PdfScriptPrompt) -> String? = { null },
    /**
     * Where the runner and the scripts read the time, in milliseconds. The budgets are measured
     * with it and `Date.now()` answers from it. Leave it unset for the real clock; a test sets it
     * so a script that measures time behaves the same on every run.
     */
    private val clock: (() -> Long)? = null,
    engine: KiteScriptEngine? = null,
) : io.github.yuroyami.kitepdf.PdfScriptHandler, AutoCloseable {

    /**
     * The engine, opened when the first script runs rather than when the runner is made.
     *
     * An engine belongs to the thread that opened it, and a viewer keeps its scripts off the
     * thread it draws on, so the runner may be made on one thread and used on another. Opening
     * it on first use puts it on the thread that will run it.
     */
    private val engineSource = engine
    private val ownEngine: KiteScriptEngine by lazy {
        engineSource ?: KiteJsScriptEngine(
            instructionBudget = policy.instructionBudget,
            deadline = { deadlinePassed() },
            clock = clock,
        )
    }

    private val host = PdfScriptHost(
        document,
        formState,
        PdfScriptHost.Hooks(onAlert, onConsole, onRequest, onResponse),
    )

    /** Every script that failed since the runner opened, newest last. */
    public val failures: List<KiteScriptException> get() = failureList

    /**
     * What the engine did with each `"use asm"` function in the document, one line each. Empty
     * until the first script runs, and for an engine that does not compile such a function.
     *
     * A PDF that carries a program compiled from C, such as DoomPDF, holds one of these. It runs
     * many times faster when the engine compiles it ahead of time, and the line says whether that
     * happened and, if not, the first thing in the module that stopped it.
     */
    public val asmReports: List<String>
        get() = if (!started) emptyList() else (ownEngine as? KiteJsScriptEngine)?.asmReports.orEmpty()

    private val failureList = ArrayList<KiteScriptException>()
    private var started = false
    private var eventStartedAt: Long = 0
    private var documentSpent: Long = 0

    private val startMark = kotlin.time.TimeSource.Monotonic.markNow()

    /** The time the budgets are measured against: the host's clock, or a monotonic one. */
    private fun now(): Long = clock?.invoke() ?: startMark.elapsedNow().inWholeMilliseconds

    /** The page the reader is on, which `this.pageNum` reports to scripts. */
    public var currentPage: Int
        get() = host.currentPage
        set(value) {
            host.currentPage = value
        }

    /** The name a script sees as `this.documentFileName`. */
    public var fileName: String
        get() = host.fileName
        set(value) {
            host.fileName = value
        }

    private fun prepare() {
        if (started) return
        started = true
        host.calculateNow = { runCalculations() }
        host.bind(ownEngine)
        ownEngine.evaluate(AcrobatApi.SOURCE, "acrobat-api")
        ownEngine.evaluate(AformApi.SOURCE, "af-helpers")
    }

    /* ─── documents and pages ───────────────────────────────────────────── */

    /**
     * Runs the document's own scripts and then its open action, which is what a viewer does when
     * the file opens (ISO 32000-1 §7.7.4 and §12.6.4.16). Returns the scripts that failed.
     */
    override fun documentOpened() {
        runDocumentOpen()
    }

    override fun pageOpened(pageIndex: Int) {
        runPageOpen(pageIndex)
    }

    override fun pageClosed(pageIndex: Int) {
        runPageClose(pageIndex)
    }

    override fun runAction(action: PdfAction.JavaScript) {
        run(action)
    }

    /** The viewer's keystroke: the value the field should show, or null when a script refused it. */
    override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String? {
        val result = keystroke(fieldName, change, selectionStart, selectionEnd, commit = false)
        return if (result.accepted) result.value else null
    }

    override fun commit(fieldName: String, value: String): Boolean = setFieldValue(fieldName, value)

    public fun runDocumentOpen(): List<KiteScriptException> {
        val before = failureList.size
        runDocumentScripts()
        (document.openAction as? PdfAction.JavaScript)?.let { runAction(it, "openAction") }
        return failureList.drop(before)
    }

    /**
     * Runs every document-level script in the order of their names (ISO 32000-1 §7.7.4). A script
     * that fails is recorded and the ones after it still run.
     */
    public fun runDocumentScripts(): List<KiteScriptException> {
        val before = failureList.size
        for ((name, source) in document.documentJavaScripts.entries.sortedBy { it.key }) {
            evaluate(source, name)
        }
        return failureList.drop(before)
    }

    /** Runs the page's open script, its `/AA /O` entry (ISO 32000-1 §12.6.3, Table 195). */
    public fun runPageOpen(pageIndex: Int): KiteScriptException? {
        currentPage = pageIndex
        val action = document.pages.getOrNull(pageIndex)?.openAction as? PdfAction.JavaScript ?: return null
        return runAction(action, "page $pageIndex open")
    }

    /** Runs the page's close script, its `/AA /C` entry. */
    public fun runPageClose(pageIndex: Int): KiteScriptException? {
        val action = document.pages.getOrNull(pageIndex)?.closeAction as? PdfAction.JavaScript ?: return null
        return runAction(action, "page $pageIndex close")
    }

    /** Runs one JavaScript action, such as a link's or a button's. */
    public fun run(action: PdfAction.JavaScript): String? {
        prepare()
        if (!policy.enabled) return null
        return evaluate(action.script, "action")
    }

    private fun runAction(action: PdfAction.JavaScript, name: String): KiteScriptException? {
        prepare()
        if (!policy.enabled) return null
        val before = failureList.size
        evaluate(action.script, name)
        return failureList.getOrNull(before)
    }

    /* ─── the form ──────────────────────────────────────────────────────── */

    /**
     * What a keystroke script decided: whether the change is allowed, and what the field would
     * hold if it is.
     */
    public class KeystrokeResult internal constructor(
        /** False when the script refused the change by setting `event.rc` to false. */
        public val accepted: Boolean,
        /** The value after the change, which a script may have rewritten. */
        public val value: String,
    )

    /**
     * Runs a field's keystroke script for one edit, without committing it (ISO 32000-1 §12.6.3).
     * A viewer calls this for each character the reader types, so a script can refuse it.
     *
     * [change] is what is being inserted, and [selectionStart] and [selectionEnd] say what it
     * replaces.
     */
    public fun keystroke(
        fieldName: String,
        change: String,
        selectionStart: Int = (formState.value(fieldName) ?: "").length,
        selectionEnd: Int = selectionStart,
        commit: Boolean = false,
    ): KeystrokeResult {
        prepare()
        val script = keystrokeScript(fieldName) ?: return KeystrokeResult(true, mergedValue(fieldName, change, selectionStart, selectionEnd))
        if (!policy.enabled) return KeystrokeResult(true, mergedValue(fieldName, change, selectionStart, selectionEnd))
        val result = dispatch(
            script, "keystroke",
            mapOf(
                "name" to "Keystroke", "type" to "Field", "field" to fieldName,
                "value" to (formState.value(fieldName) ?: ""), "change" to change,
                "selStart" to selectionStart.toDouble(), "selEnd" to selectionEnd.toDouble(),
                "willCommit" to false,
            ),
        )
        return KeystrokeResult(result.rc, if (result.rc) mergedValue(fieldName, result.change, selectionStart, selectionEnd) else formState.value(fieldName) ?: "")
    }

    /**
     * Commits a value to a field the way a viewer does when the reader leaves it: the keystroke
     * script sees the whole value, then validate, then the value is stored, then every calculate
     * script runs in the form's own order, then the format scripts decide what is shown
     * (ISO 32000-1 §12.6.3 for the triggers and §12.7.2 for the calculation order).
     *
     * Returns false when a script refused the value, in which case nothing was stored.
     */
    public fun setFieldValue(fieldName: String, value: String): Boolean {
        prepare()
        if (!policy.enabled) {
            formState.setValue(fieldName, value)
            return true
        }
        val field = document.formField(fieldName) ?: return false
        keystrokeScript(fieldName)?.let { script ->
            val result = dispatch(
                script, "keystroke",
                mapOf(
                    "name" to "Keystroke", "type" to "Field", "field" to fieldName,
                    "value" to value, "change" to "", "willCommit" to true,
                ),
            )
            if (!result.rc) return false
        }
        (field.additionalActions?.validate as? PdfAction.JavaScript)?.let { action ->
            val result = dispatch(
                action.script, "validate",
                mapOf("name" to "Validate", "type" to "Field", "field" to fieldName, "value" to value),
            )
            if (!result.rc) return false
            formState.setValue(fieldName, result.value)
        } ?: formState.setValue(fieldName, value)
        runCalculations()
        formatAll()
        return true
    }

    /**
     * Runs every calculate script, in the order of the form's `/CO` array (ISO 32000-1 §12.7.2).
     * A form with no order runs them in the order its fields appear.
     */
    public fun runCalculations() {
        prepare()
        if (!policy.enabled) return
        for (name in calculationOrder()) {
            val field = document.formField(name) ?: continue
            val action = field.additionalActions?.calculate as? PdfAction.JavaScript ?: continue
            val result = dispatch(
                action.script, "calculate",
                mapOf(
                    "name" to "Calculate", "type" to "Field", "field" to name,
                    "value" to (formState.value(name) ?: ""),
                ),
            )
            if (result.rc) formState.setValue(name, result.value)
        }
    }

    /**
     * The text a field shows, after its format script has had it. The stored value does not
     * change: a formatted total still calculates as a number.
     */
    public fun formattedValue(fieldName: String): String {
        prepare()
        val stored = formState.value(fieldName) ?: ""
        if (!policy.enabled) return stored
        val field = document.formField(fieldName) ?: return stored
        val action = field.additionalActions?.format as? PdfAction.JavaScript ?: return stored
        val result = dispatch(
            action.script, "format",
            mapOf("name" to "Format", "type" to "Field", "field" to fieldName, "value" to stored),
        )
        return if (result.rc) result.value else stored
    }

    /** Runs a widget's mouse down script, which is how an on-screen button reports a press. */
    override fun mouseDown(fieldName: String): Unit = widgetEvent(fieldName, "MouseDown") { it.mouseDown }

    /** Runs a widget's mouse up script, which is how an on-screen button reports a release. */
    override fun mouseUp(fieldName: String): Unit = widgetEvent(fieldName, "MouseUp") { it.mouseUp }

    /** Runs a widget's focus script. */
    override fun focus(fieldName: String): Unit = widgetEvent(fieldName, "Focus") { it.focus }

    /** Runs a widget's blur script, which a viewer fires when the field loses the caret. */
    override fun blur(fieldName: String): Unit = widgetEvent(fieldName, "Blur") { it.blur }

    private inline fun widgetEvent(
        fieldName: String,
        eventName: String,
        select: (io.github.yuroyami.kitepdf.PdfWidgetActions) -> PdfAction?,
    ) {
        prepare()
        if (!policy.enabled) return
        val actions = document.formField(fieldName)?.additionalActions ?: return
        val action = select(actions) as? PdfAction.JavaScript ?: return
        dispatch(
            action.script, eventName.lowercase(),
            mapOf(
                "name" to eventName, "type" to "Field", "field" to fieldName,
                "value" to (formState.value(fieldName) ?: ""),
            ),
        )
    }

    /* ─── timers ────────────────────────────────────────────────────────── */

    /**
     * Runs the timers a script set with `app.setInterval` or `app.setTimeOut` and that are due at
     * [nowMillis]. Returns how long to wait before the next one, or null when none is waiting.
     *
     * A viewer calls this once per frame. Nothing runs on its own: a document cannot take the
     * thread from the host.
     */
    override fun pumpTimers(nowMillis: Long): Long? {
        if (!started || !policy.enabled) return null
        for (code in host.dueTimers(nowMillis)) {
            evaluate(code, "timer")
        }
        val next = host.nextTimerDue() ?: return null
        return (next - nowMillis).coerceAtLeast(0)
    }

    /** True when a script is waiting on a timer, so a viewer knows to keep pumping. */
    override val hasTimers: Boolean get() = host.hasTimers

    /* ─── the engine ────────────────────────────────────────────────────── */

    private fun dispatch(script: String, name: String, info: Map<String, Any?>): PdfScriptHost.EventResult {
        ownEngine.defineValue("__kiteEventInfo", info)
        // The script runs as a function body, which is where a field's script lives in Acrobat,
        // so its own `var` declarations stay out of the global scope.
        evaluate("__kiteEvent(__kiteEventInfo, function () {\n$script\n})", name)
        return host.lastEventResult
    }

    private fun evaluate(source: String, name: String): String? {
        prepare()
        if (!policy.enabled) return null
        eventStartedAt = now()
        return try {
            ownEngine.evaluate(source, name)
        } catch (e: KiteScriptException) {
            failureList.add(e)
            null
        } finally {
            documentSpent += (now() - eventStartedAt).coerceAtLeast(0)
        }
    }

    /** True when this event, or the whole document, has used the time the policy allows. */
    private fun deadlinePassed(): Boolean {
        val now = now()
        if (policy.budgetMillis > 0 && now - eventStartedAt > policy.budgetMillis) {
            val keepGoing = policy.onStillRunning?.invoke(now - eventStartedAt) == true
            if (!keepGoing) return true
            eventStartedAt = now
        }
        if (policy.documentBudgetMillis > 0 && documentSpent > policy.documentBudgetMillis) return true
        return false
    }

    /** The order the form asks for its calculations, its `/CO` array, or the field order. */
    private fun calculationOrder(): List<String> {
        val acro = document.catalog.getDict("AcroForm", document)
        val order = acro?.get("CO")?.resolve(document) as? PdfArray
        if (order == null || order.isEmpty()) {
            return document.formFields.filter { it.additionalActions?.calculate != null }.map { it.fullyQualifiedName }
        }
        // /CO holds references to the field dictionaries, so the fields are matched by reference.
        val byReference = document.formFields.associateBy { it.fieldReference }
        val names = ArrayList<String>()
        for (entry in order) {
            val reference = entry as? io.github.yuroyami.kitepdf.core.parser.PdfReference ?: continue
            byReference[reference]?.let { names.add(it.fullyQualifiedName) }
        }
        return names
    }

    /** Puts every field's shown text through its format script, after a calculation round. */
    private fun formatAll() {
        for (field in document.formFields) {
            if (field.additionalActions?.format == null) continue
            formattedValue(field.fullyQualifiedName)
        }
    }

    private fun keystrokeScript(fieldName: String): String? =
        (document.formField(fieldName)?.additionalActions?.keystroke as? PdfAction.JavaScript)?.script

    private fun mergedValue(fieldName: String, change: String, start: Int, end: Int): String {
        val current = formState.value(fieldName) ?: ""
        val from = start.coerceIn(0, current.length)
        val to = end.coerceIn(from, current.length)
        return current.substring(0, from) + change + current.substring(to)
    }

    override fun close() {
        // Nothing to close when no script ever ran, because no engine was ever opened.
        if (started) ownEngine.close()
    }
}

