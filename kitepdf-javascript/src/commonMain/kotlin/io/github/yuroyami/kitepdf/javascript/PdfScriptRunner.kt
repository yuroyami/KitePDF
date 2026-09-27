package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.withLock
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
 * saves. One runner belongs to one document.
 *
 * A runner can be called from any thread. A KiteJS engine belongs to the thread that opened it, so
 * the runner opens its engine on a thread of its own and runs every call there, one at a time and
 * in order. [close] stops that thread. The callbacks below run on it too.
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
    /**
     * The engine to run the scripts on, instead of a KiteJS engine the runner opens itself. It
     * belongs to the thread that made it, so the runner runs on the caller's thread and the
     * caller keeps every call on that thread.
     */
    engine: KiteScriptEngine? = null,
) : io.github.yuroyami.kitepdf.PdfScriptHandler, AutoCloseable {

    /**
     * The engine, opened when the first script runs rather than when the runner is made, and
     * always on [scriptThread].
     */
    private val engineSource = engine
    private val ownEngine: KiteScriptEngine by lazy {
        engineSource ?: KiteJsScriptEngine(
            instructionBudget = policy.instructionBudget,
            deadline = { deadlinePassed() },
            clock = clock,
        )
    }

    /** The thread the runner's own engine lives on, started by the first call (#355). */
    private val scriptThread = lazy { startScriptThread() }

    /** Read by the deadline check on the script thread, so a close stops a running script (#365). */
    @kotlin.concurrent.Volatile
    private var closed = false

    /** True once the document's own scripts ran, which happens once per runner (#365). */
    private var documentOpenRan = false

    /** Runs [block] on the thread the engine belongs to, and waits for it. */
    private fun <T> onScriptThread(block: () -> T): T {
        check(!closed) { "the script runner is closed" }
        return if (engineSource != null) block() else scriptThread.value.call(block)
    }

    private val host = PdfScriptHost(
        document,
        formState,
        PdfScriptHost.Hooks(onAlert, onConsole, onRequest, onResponse),
    ).also { host ->
        host.clock = { now() }
        host.timersChanged = { timerLock.withLock { timerListeners.toList() }.forEach { it() } }
    }

    private val timerLock = io.github.yuroyami.kitepdf.core.KiteLock()
    private val timerListeners = ArrayList<() -> Unit>()

    /** Every script that failed since the runner opened, newest last. */
    public val failures: List<KiteScriptException> get() = failureCopy

    /**
     * What the engine did with each `"use asm"` function in the document, one line each. Empty
     * until the first script runs, and for an engine that does not compile such a function.
     *
     * A PDF that carries a program compiled from C, such as DoomPDF, holds one of these. It runs
     * many times faster when the engine compiles it ahead of time, and the line says whether that
     * happened and, if not, the first thing in the module that stopped it.
     */
    public val asmReports: List<String>
        get() = onScriptThread {
            if (!started) emptyList() else (ownEngine as? KiteJsScriptEngine)?.asmReports.orEmpty()
        }

    private val failureList = ArrayList<KiteScriptException>()

    /** [failureList] as the other threads may read it: a new list after every failure. */
    @kotlin.concurrent.Volatile
    private var failureCopy: List<KiteScriptException> = emptyList()

    private fun recordFailure(failure: KiteScriptException) {
        failureList.add(failure)
        failureCopy = failureList.toList()
    }
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
     * the file opens (ISO 32000-1 §7.7.4 and §12.6.4.16). They run once for each runner, so a
     * viewer that reports the document open again runs nothing (#365). [runDocumentOpen] runs
     * them again and returns the scripts that failed.
     */
    override fun documentOpened() {
        val first = onScriptThread { !documentOpenRan.also { documentOpenRan = true } }
        if (first) runDocumentOpen()
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

    public fun runDocumentOpen(): List<KiteScriptException> = onScriptThread {
        val before = failureList.size
        runDocumentScripts()
        (document.openAction as? PdfAction.JavaScript)?.let { runAction(it, "openAction") }
        failureList.drop(before)
    }

    /**
     * Runs every document-level script in the order of their names (ISO 32000-1 §7.7.4). A script
     * that fails is recorded and the ones after it still run.
     */
    public fun runDocumentScripts(): List<KiteScriptException> = onScriptThread {
        val before = failureList.size
        for ((name, source) in document.documentJavaScripts.entries.sortedBy { it.key }) {
            evaluate(source, name)
        }
        failureList.drop(before)
    }

    /** Runs the page's open script, its `/AA /O` entry (ISO 32000-1 §12.6.3, Table 195). */
    public fun runPageOpen(pageIndex: Int): KiteScriptException? = onScriptThread {
        currentPage = pageIndex
        val action = document.pages.getOrNull(pageIndex)?.openAction as? PdfAction.JavaScript
        action?.let { runAction(it, "page $pageIndex open") }
    }

    /** Runs the page's close script, its `/AA /C` entry. */
    public fun runPageClose(pageIndex: Int): KiteScriptException? = onScriptThread {
        val action = document.pages.getOrNull(pageIndex)?.closeAction as? PdfAction.JavaScript
        action?.let { runAction(it, "page $pageIndex close") }
    }

    /** Runs one JavaScript action, such as a link's or a button's. */
    public fun run(action: PdfAction.JavaScript): String? = onScriptThread {
        prepare()
        if (!policy.enabled) null else evaluate(action.script, "action")
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
    ): KeystrokeResult = onScriptThread {
        prepare()
        val script = keystrokeScript(fieldName)
        if (script == null || !policy.enabled) {
            KeystrokeResult(true, mergedValue(fieldName, change, selectionStart, selectionEnd))
        } else {
            val result = dispatch(
                script, "keystroke",
                mapOf(
                    "name" to "Keystroke", "type" to "Field", "field" to fieldName,
                    "value" to (formState.value(fieldName) ?: ""), "change" to change,
                    "selStart" to selectionStart.toDouble(), "selEnd" to selectionEnd.toDouble(),
                    "willCommit" to false,
                ),
            )
            KeystrokeResult(result.rc, if (result.rc) mergedValue(fieldName, result.change, selectionStart, selectionEnd) else formState.value(fieldName) ?: "")
        }
    }

    /**
     * Commits a value to a field the way a viewer does when the reader leaves it: the keystroke
     * script sees the whole value, then validate, then the value is stored, then every calculate
     * script runs in the form's own order, then the format scripts decide what is shown
     * (ISO 32000-1 §12.6.3 for the triggers and §12.7.2 for the calculation order).
     *
     * Returns false when a script refused the value, in which case nothing was stored.
     */
    public fun setFieldValue(fieldName: String, value: String): Boolean = onScriptThread { commitOnThread(fieldName, value) }

    private fun commitOnThread(fieldName: String, value: String): Boolean {
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
        calculateAll()
        formatAll()
        return true
    }

    /**
     * Runs every calculate script, in the order of the form's `/CO` array (ISO 32000-1 §12.7.2).
     * A form with no order runs them in the order its fields appear.
     */
    public fun runCalculations(): Unit = onScriptThread {
        prepare()
        if (policy.enabled) calculateAll()
    }

    private fun calculateAll() {
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
    public fun formattedValue(fieldName: String): String = onScriptThread { formatOnThread(fieldName) }

    private fun formatOnThread(fieldName: String): String {
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

    /** Runs the mouse down script of one widget of the field, such as one button of a radio group. */
    override fun mouseDown(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "MouseDown", widgetIndex) { it.mouseDown }

    /** Runs the mouse up script of one widget of the field. */
    override fun mouseUp(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "MouseUp", widgetIndex) { it.mouseUp }

    /** Runs the focus script of one widget of the field. */
    override fun focus(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "Focus", widgetIndex) { it.focus }

    /** Runs the blur script of one widget of the field. */
    override fun blur(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "Blur", widgetIndex) { it.blur }

    /**
     * Performs one action of a widget's `/A` chain. A script runs as the field's mouse up event,
     * which is where Acrobat keeps a button's mouse up script, and a form reset is followed by a
     * calculation round, as MuPDF does after one.
     */
    override fun runWidgetAction(fieldName: String, action: PdfAction): Boolean = when (action) {
        is PdfAction.JavaScript -> {
            onScriptThread {
                prepare()
                if (policy.enabled) fieldEvent(fieldName, "MouseUp", action)
            }
            true
        }
        is PdfAction.ResetForm -> {
            onScriptThread {
                formState.resetForm(action)
                prepare()
                if (policy.enabled) calculateAll()
            }
            true
        }
        else -> false
    }

    /** Runs one trigger of a field: of widget [widgetIndex] when given, else of its first widget. */
    private fun widgetEvent(
        fieldName: String,
        eventName: String,
        widgetIndex: Int? = null,
        select: (io.github.yuroyami.kitepdf.PdfWidgetActions) -> PdfAction?,
    ): Unit = onScriptThread {
        prepare()
        val field = if (policy.enabled) document.formField(fieldName) else null
        val actions = if (widgetIndex == null) field?.additionalActions else field?.widgets?.getOrNull(widgetIndex)?.additionalActions
        val action = actions?.let(select) as? PdfAction.JavaScript
        if (action != null) fieldEvent(fieldName, eventName, action)
    }

    /** Runs [action] as the [eventName] event of the field. Call it on the script thread. */
    private fun fieldEvent(fieldName: String, eventName: String, action: PdfAction.JavaScript) {
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
     * Runs the timers a script set with `app.setInterval` or `app.setTimeOut` that are due, and
     * returns how long to wait before the next one, or null when none is waiting.
     *
     * The timers run on the runner's clock, the one the budgets use, and [nowMillis] is not
     * read: a timer fires after its delay, counted from the moment a script set it, whatever time
     * base the caller pumps with (#367). Nothing runs on its own: a document cannot take the
     * thread from the host.
     */
    override fun pumpTimers(nowMillis: Long): Long? = onScriptThread {
        if (!started || !policy.enabled) return@onScriptThread null
        for (code in host.dueTimers(now())) {
            evaluate(code, "timer")
        }
        host.nextTimerDue()?.let { next -> (next - now()).coerceAtLeast(0) }
    }

    /** Reports each timer a script sets or clears, from the script thread (#368). */
    override fun onTimersChanged(listener: () -> Unit): () -> Unit {
        timerLock.withLock { timerListeners += listener }
        return { timerLock.withLock { timerListeners -= listener } }
    }

    /** True when a script is waiting on a timer, so a viewer knows to keep pumping. */
    override val hasTimers: Boolean get() = host.hasTimers

    /* ─── the engine ────────────────────────────────────────────────────── */

    private fun dispatch(script: String, name: String, info: Map<String, Any?>): PdfScriptHost.EventResult {
        try {
            ownEngine.defineValue("__kiteEventInfo", info)
        } catch (e: KiteScriptException) {
            // The event never reached its script, so it changes nothing.
            recordFailure(e)
            return PdfScriptHost.EventResult(rc = true, value = info["value"] as? String ?: "", change = info["change"] as? String ?: "")
        }
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
            recordFailure(e)
            null
        } finally {
            documentSpent += (now() - eventStartedAt).coerceAtLeast(0)
        }
    }

    /**
     * True when this event, or the whole document, has used the time the policy allows.
     *
     * The clock is only read when there is a budget to compare it against. A policy with no
     * budget is asked this many times a second and would otherwise read the clock each time for
     * an answer that is always the same. It also keeps the clock out of the engine's own speed: a
     * test that fixes the clock so its run is repeatable would otherwise see it move faster the
     * more instructions the engine ran.
     */
    private fun deadlinePassed(): Boolean {
        // A runner that is closing stops the script it runs at the next check (#365).
        if (closed) return true
        if (policy.budgetMillis <= 0 && policy.documentBudgetMillis <= 0) return false
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
        // A /CO that points at a missing object reads as absent, and the field order applies (#441).
        val order = try {
            document.catalog.getDict("AcroForm", document)?.get("CO")?.resolve(document) as? PdfArray
        } catch (_: io.github.yuroyami.kitepdf.core.PdfFormatException) {
            null
        }
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
            formatOnThread(field.fullyQualifiedName)
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
        if (closed) return
        closed = true
        // Nothing to close when no script ever ran, because no engine was ever opened.
        if (engineSource != null || !scriptThread.isInitialized()) {
            if (started) ownEngine.close()
            return
        }
        val thread = scriptThread.value
        try {
            thread.call { if (started) ownEngine.close() }
        } finally {
            thread.close()
        }
    }
}

