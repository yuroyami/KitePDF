package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * in order. [close] stops that thread. The callbacks below run on it too. On JavaScript and
 * WebAssembly the engine loads before its first use: call [prepare] once and wait for it, as
 * `KiteDocView` does. There, a long script that a `suspend` event method runs pauses now and
 * then so the page can draw, and the next call waits for it (#489).
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

    /** The caller's engine, when it gave one. */
    private val engineSource = engine

    /**
     * The engine while it is open: opened when the first script runs rather than when the runner
     * is made, and always on [scriptThread].
     */
    private var openEngine: KiteScriptEngine? = null

    /** The thread the runner's own engine lives on, started by the first call (#355). */
    private val scriptThread = lazy { startScriptThread() }

    /** Read by the deadline check on the script thread, so a close stops a running script (#365). */
    @kotlin.concurrent.Volatile
    private var closed = false

    /** True once the document's own scripts ran, which happens once per runner (#365). */
    private var documentOpenRan = false

    /** How many calls of this runner the script thread is inside, so its engine stays open while a script runs. */
    private var busy = 0

    /** Closes the engine when another runner opens one on the thread they share; the next script opens it again. */
    private val engineUser = EngineUser {
        if (busy > 0) return@EngineUser false
        openEngine?.let { engine ->
            openEngine = null
            runCatching { engine.close() }
        }
        true
    }

    /** Runs [block] on the thread the engine belongs to, and waits for it. A script in it never pauses. */
    private fun <T> onScriptThread(block: suspend () -> T): T {
        check(!closed) { "the script runner is closed" }
        val counted = { counted { withPausing(false) { runNow(block) } } }
        return if (engineSource != null) counted() else scriptThread.value.call(counted)
    }

    /**
     * Runs a viewer's call: as [onScriptThread] on a thread of its own, where no engine can pause.
     * Where the work runs where it is called, as on the web, a long script pauses and lets the
     * page draw, and the next call waits for it (#489).
     */
    private suspend fun <T> pausingCall(block: suspend () -> T): T {
        check(!closed) { "the script runner is closed" }
        if (engineSource == null && scriptThread.value.isOwnThread) return onScriptThread(block)
        return turns.withLock { counted { withPausing(true) { block() } } }
    }

    private inline fun <T> counted(block: () -> T): T {
        busy++
        try {
            return block()
        } finally {
            busy--
            if (closeWaits && busy == 0) closeHere()
        }
    }

    private inline fun <T> withPausing(on: Boolean, block: () -> T): T {
        val was = pausing
        pausing = on
        try {
            return block()
        } finally {
            pausing = was
        }
    }

    /** Whether a script that runs now may pause: only in a viewer's call, and where the engine can. */
    private var pausing = false

    /** Takes turns where a script may pause, so that no call reaches the engine while its script waits. */
    private val turns = Mutex()

    /** Whether [close] came while a script was paused, and left the closing to the call that runs it. */
    private var closeWaits = false

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

    private val failureList = ArrayList<KiteScriptException>()

    /** [failureList] as the other threads may read it: a new list after every failure. */
    @kotlin.concurrent.Volatile
    private var failureCopy: List<KiteScriptException> = emptyList()

    private fun recordFailure(failure: KiteScriptException) {
        failureList.add(failure)
        failureCopy = failureList.toList()
    }
    /** True once an engine opened, so timers may be waiting and a closed engine is opened again. */
    private var started = false

    /** True once the document's own scripts ran, so an engine opened again runs them again. */
    private var documentScriptsRan = false
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

    /**
     * Opens the engine and gives it the Acrobat API, unless it is open. False when it would not
     * open, which is recorded, so the script does not run.
     */
    private fun ensureEngine(): Boolean {
        if (openEngine != null) return true
        val engine = engineSource ?: try {
            check(KiteJsScriptEngine.isLoaded) { KiteJsScriptEngine.NOT_LOADED }
            // Where every engine shares one thread, another runner's engine closes first (#553).
            scriptThread.value.makeRoom(engineUser)
            KiteJsScriptEngine(
                instructionBudget = policy.instructionBudget,
                deadline = { deadlinePassed() },
                clock = clock,
                maxStackBytes = scriptThread.value.stackBytes,
            )
        } catch (failure: RuntimeException) {
            recordFailure(KiteScriptException("the engine did not open: ${failure.message}", failure))
            return false
        }
        val again = started
        started = true
        openEngine = engine
        host.calculateNow = { runCalculations() }
        host.bind(engine)
        engine.evaluate(AcrobatApi.SOURCE, "acrobat-api")
        engine.evaluate(AformApi.SOURCE, "af-helpers")
        // An engine that closed to make room for another runner's opens again here. The
        // document's own scripts define what its fields' scripts call, so they run again first.
        if (again && documentScriptsRan) runDocumentScripts()
        return true
    }

    /* ─── documents and pages ───────────────────────────────────────────── */

    /**
     * Runs the document's own scripts and then its open action, which is what a viewer does when
     * the file opens (ISO 32000-1 §7.7.4 and §12.6.4.16). They run once for each runner, so a
     * viewer that reports the document open again runs nothing (#365). [runDocumentOpen] runs
     * them again and returns the scripts that failed.
     */
    /** Loads the engine, which on JavaScript and WebAssembly has to happen before the first script. */
    override suspend fun prepare(): Unit = KiteJsScriptEngine.load()

    override suspend fun documentOpened() {
        pausingCall {
            if (!documentOpenRan) {
                documentOpenRan = true
                documentOpenHere()
            }
        }
    }

    override suspend fun pageOpened(pageIndex: Int) {
        pausingCall { pageOpenHere(pageIndex) }
    }

    override suspend fun pageClosed(pageIndex: Int) {
        pausingCall { pageCloseHere(pageIndex) }
    }

    override suspend fun runAction(action: PdfAction.JavaScript) {
        pausingCall { runHere(action) }
    }

    /** The viewer's keystroke: the value the field should show, or null when a script refused it. */
    override suspend fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String? {
        val result = pausingCall { keystrokeHere(fieldName, change, selectionStart, selectionEnd) }
        return if (result.accepted) result.value else null
    }

    override suspend fun commit(fieldName: String, value: String): Boolean = pausingCall { commitOnThread(fieldName, value) }

    override val supportsMultipleChoices: Boolean get() = true

    /** Runs only the selection-change event; deferred selection remains outside the live state. */
    override suspend fun choiceKeystroke(fieldName: String, selection: PdfChoiceSelection): PdfChoiceSelection? = pausingCall {
        choiceKeystrokeOnThread(fieldName, selection, formState.choiceSelection(fieldName) ?: PdfChoiceSelection(emptyList()))
    }

    override suspend fun choiceKeystroke(fieldName: String, selection: PdfChoiceSelection, previous: PdfChoiceSelection): PdfChoiceSelection? = pausingCall {
        choiceKeystrokeOnThread(fieldName, selection, previous)
    }

    private suspend fun choiceKeystrokeOnThread(fieldName: String, selection: PdfChoiceSelection, previous: PdfChoiceSelection): PdfChoiceSelection? {
        val field = document.formField(fieldName) ?: return null
        var candidate = field.validateChoiceSelection(selection) ?: return null
        if (formState.isReadOnly(fieldName) || formState.isHidden(fieldName)) return null
        val revision = formState.fieldRevision(fieldName)
        ensureEngine()
        val script = keystrokeScript(fieldName)
        if (policy.enabled && script != null) {
            val info = mutableMapOf<String, Any?>(
                "name" to "Keystroke", "type" to "Field", "field" to fieldName,
                "value" to choiceEventValue(field, previous, face = field.isCombo),
                "change" to choiceEventValue(field, candidate, face = true), "willCommit" to false,
                "selStart" to 0.0, "selEnd" to choiceEventValue(field, previous, face = true).length.toDouble(),
            )
            if (candidate.freeText == null) info["changeEx"] = field.choiceValues(candidate).singleOrNull().orEmpty()
            val result = dispatch(script, "choice selection", info)
            if (!result.rc) return null
            if (candidate.indices.size <= 1) {
                candidate = choiceRewrite(field, candidate, result.change, face = true) ?: return null
            }
        }
        return candidate.takeIf {
            formState.fieldRevision(fieldName) == revision && !formState.isReadOnly(fieldName) && !formState.isHidden(fieldName)
        }
    }

    /** One choice change follows the Acrobat Field event sequence without flattening its values. */
    override suspend fun commitChoice(fieldName: String, selection: PdfChoiceSelection): Boolean = pausingCall {
        commitChoiceOnThread(fieldName, selection)
    }

    public fun runDocumentOpen(): List<KiteScriptException> = onScriptThread { documentOpenHere() }

    private suspend fun documentOpenHere(): List<KiteScriptException> {
        val before = failureList.size
        documentScriptsHere()
        (document.openAction as? PdfAction.JavaScript)?.let { runAction(it, "openAction") }
        return failureList.drop(before)
    }

    /**
     * Runs every document-level script in the order of their names (ISO 32000-1 §7.7.4). A script
     * that fails is recorded and the ones after it still run.
     */
    public fun runDocumentScripts(): List<KiteScriptException> = onScriptThread { documentScriptsHere() }

    private suspend fun documentScriptsHere(): List<KiteScriptException> {
        val before = failureList.size
        documentScriptsRan = true
        for ((name, source) in document.documentJavaScripts.entries.sortedBy { it.key }) {
            evaluate(source, name)
        }
        return failureList.drop(before)
    }

    /** Runs the page's open script, its `/AA /O` entry (ISO 32000-1 §12.6.3, Table 195). */
    public fun runPageOpen(pageIndex: Int): KiteScriptException? = onScriptThread { pageOpenHere(pageIndex) }

    private suspend fun pageOpenHere(pageIndex: Int): KiteScriptException? {
        currentPage = pageIndex
        val action = document.pages.getOrNull(pageIndex)?.openAction as? PdfAction.JavaScript
        return action?.let { runAction(it, "page $pageIndex open") }
    }

    /** Runs the page's close script, its `/AA /C` entry. */
    public fun runPageClose(pageIndex: Int): KiteScriptException? = onScriptThread { pageCloseHere(pageIndex) }

    private suspend fun pageCloseHere(pageIndex: Int): KiteScriptException? {
        val action = document.pages.getOrNull(pageIndex)?.closeAction as? PdfAction.JavaScript
        return action?.let { runAction(it, "page $pageIndex close") }
    }

    /** Runs one JavaScript action, such as a link's or a button's. */
    public fun run(action: PdfAction.JavaScript): String? = onScriptThread { runHere(action) }

    private suspend fun runHere(action: PdfAction.JavaScript): String? {
        ensureEngine()
        return if (!policy.enabled) null else evaluate(action.script, "action")
    }

    private suspend fun runAction(action: PdfAction.JavaScript, name: String): KiteScriptException? {
        ensureEngine()
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
        selectionStart: Int = editingValue(fieldName).length,
        selectionEnd: Int = selectionStart,
        commit: Boolean = false,
    ): KeystrokeResult = onScriptThread { keystrokeHere(fieldName, change, selectionStart, selectionEnd) }

    private suspend fun keystrokeHere(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): KeystrokeResult {
        ensureEngine()
        val script = keystrokeScript(fieldName)
        return if (script == null || !policy.enabled) {
            KeystrokeResult(true, mergedValue(fieldName, change, selectionStart, selectionEnd))
        } else {
            val result = dispatch(
                script, "keystroke",
                mapOf(
                    "name" to "Keystroke", "type" to "Field", "field" to fieldName,
                    "value" to editingValue(fieldName), "change" to change,
                    "selStart" to selectionStart.toDouble(), "selEnd" to selectionEnd.toDouble(),
                    "willCommit" to false,
                ),
            )
            KeystrokeResult(result.rc, if (result.rc) mergedValue(fieldName, result.change, selectionStart, selectionEnd) else editingValue(fieldName))
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

    private suspend fun commitOnThread(fieldName: String, value: String): Boolean {
        ensureEngine()
        val field = document.formField(fieldName) ?: return false
        if (field.type == PdfFormField.FieldType.Choice) {
            val selection = field.choiceSelectionForValue(value) ?: return false
            return commitChoiceOnThread(fieldName, selection)
        }
        if (!policy.enabled) {
            formState.setValue(fieldName, value)
            return true
        }
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

    private suspend fun commitChoiceOnThread(fieldName: String, selection: PdfChoiceSelection): Boolean {
        val field = document.formField(fieldName) ?: return false
        var candidate = field.validateChoiceSelection(selection) ?: return false
        if (formState.isReadOnly(fieldName) || formState.isHidden(fieldName)) return false
        val revision = formState.fieldRevision(fieldName)
        ensureEngine()
        if (policy.enabled) {
            keystrokeScript(fieldName)?.let { script ->
                val result = dispatch(
                    script, "choice commit",
                    mapOf(
                        "name" to "Keystroke", "type" to "Field", "field" to fieldName,
                        "value" to choiceEventValue(field, candidate, face = true),
                        "change" to "", "changeEx" to "", "willCommit" to true,
                    ),
                )
                if (!result.rc) return false
                candidate = choiceRewrite(field, candidate, result.value, face = true) ?: return false
            }
            (field.additionalActions?.validate as? PdfAction.JavaScript)?.let { action ->
                val result = dispatch(
                    action.script, "choice validate",
                    mapOf(
                        "name" to "Validate", "type" to "Field", "field" to fieldName,
                        "value" to choiceEventValue(field, candidate, face = field.isCombo),
                    ),
                )
                if (!result.rc) return false
                candidate = choiceRewrite(field, candidate, result.value, face = field.isCombo) ?: return false
            }
        }
        // A script, another edit or a visibility/read-only change invalidates this transaction.
        // The revision check and store share the state's lock, including the final flag check.
        if (!formState.setChoiceSelection(fieldName, candidate, revision)) return false
        if (policy.enabled) {
            calculateAll()
            formatAll()
        }
        return true
    }

    /** Adobe's event.value uses the combo face value and is empty for a multiple selection. */
    private fun choiceEventValue(field: PdfFormField, selection: PdfChoiceSelection, face: Boolean): String {
        val values = field.choiceValues(selection)
        if (values.size > 1) return ""
        if (!face) return values.singleOrNull().orEmpty()
        return selection.indices.singleOrNull()?.let { index -> field.choiceOptions.firstOrNull { it.index == index }?.label }
            ?: selection.freeText ?: selection.unresolvedValues.singleOrNull().orEmpty()
    }

    private fun choiceRewrite(field: PdfFormField, current: PdfChoiceSelection, value: String, face: Boolean): PdfChoiceSelection? {
        // Acrobat ignores event.value assignments for a list holding multiple selections.
        if (current.indices.size > 1 || value == choiceEventValue(field, current, face)) return current
        if (!face) return field.choiceSelectionForValue(value)
        field.choiceOptions.firstOrNull { it.label == value }?.let { return PdfChoiceSelection(listOf(it.index)) }
        return when {
            field.isEditableCombo -> PdfChoiceSelection(emptyList(), freeText = value)
            value.isEmpty() -> PdfChoiceSelection(emptyList())
            else -> null
        }
    }

    /**
     * Runs every calculate script, in the order of the form's `/CO` array (ISO 32000-1 §12.7.2).
     * A form with no order runs them in the order its fields appear.
     */
    public fun runCalculations(): Unit = onScriptThread {
        ensureEngine()
        if (policy.enabled) calculateAll()
    }

    private suspend fun calculateAll() {
        for (name in calculationOrder()) {
            val field = document.formField(name) ?: continue
            val action = field.additionalActions?.calculate as? PdfAction.JavaScript ?: continue
            val result = dispatch(
                action.script, "calculate",
                mapOf(
                    "name" to "Calculate", "type" to "Field", "field" to name,
                    "value" to (formState.choiceSelection(name)?.let { choiceEventValue(field, it, face = false) } ?: formState.value(name).orEmpty()),
                ),
            )
            if (result.rc && formState.choiceSelection(name)?.indices.orEmpty().size <= 1) formState.setValue(name, result.value)
        }
    }

    /**
     * The text a field shows, after its format script has had it. The stored value does not
     * change: a formatted total still calculates as a number.
     */
    public fun formattedValue(fieldName: String): String = onScriptThread { formatOnThread(fieldName) }

    private suspend fun formatOnThread(fieldName: String): String {
        ensureEngine()
        val field = document.formField(fieldName)
        val stored = if (field != null) formState.choiceSelection(fieldName)?.let { choiceEventValue(field, it, face = field.isCombo) }
            ?: formState.value(fieldName).orEmpty() else ""
        if (!policy.enabled) return stored
        val action = field?.additionalActions?.format as? PdfAction.JavaScript ?: return stored
        val result = dispatch(
            action.script, "format",
            mapOf("name" to "Format", "type" to "Field", "field" to fieldName, "value" to stored),
        )
        if (formState.choiceSelection(fieldName)?.indices.orEmpty().size > 1) return stored
        return if (result.rc) result.value else stored
    }

    /** Runs a widget's mouse down script, which is how an on-screen button reports a press. */
    override suspend fun mouseDown(fieldName: String): Unit = widgetEvent(fieldName, "MouseDown") { it.mouseDown }

    /** Runs a widget's mouse up script, which is how an on-screen button reports a release. */
    override suspend fun mouseUp(fieldName: String): Unit = widgetEvent(fieldName, "MouseUp") { it.mouseUp }

    /** Runs a widget's focus script. */
    override suspend fun focus(fieldName: String): Unit = widgetEvent(fieldName, "Focus") { it.focus }

    /** Runs a widget's blur script, which a viewer fires when the field loses the caret. */
    override suspend fun blur(fieldName: String): Unit = widgetEvent(fieldName, "Blur") { it.blur }

    /** Runs the mouse down script of one widget of the field, such as one button of a radio group. */
    override suspend fun mouseDown(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "MouseDown", widgetIndex) { it.mouseDown }

    /** Runs the mouse up script of one widget of the field. */
    override suspend fun mouseUp(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "MouseUp", widgetIndex) { it.mouseUp }

    /** Runs the focus script of one widget of the field. */
    override suspend fun focus(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "Focus", widgetIndex) { it.focus }

    /** Runs the blur script of one widget of the field. */
    override suspend fun blur(fieldName: String, widgetIndex: Int): Unit = widgetEvent(fieldName, "Blur", widgetIndex) { it.blur }

    /**
     * Performs one action of a widget's `/A` chain. A script runs as the field's mouse up event,
     * which is where Acrobat keeps a button's mouse up script, and a form reset is followed by a
     * calculation round, as MuPDF does after one.
     */
    override suspend fun runWidgetAction(fieldName: String, action: PdfAction): Boolean = when (action) {
        is PdfAction.JavaScript -> {
            pausingCall {
                ensureEngine()
                if (policy.enabled) fieldEvent(fieldName, "MouseUp", action)
            }
            true
        }
        is PdfAction.ResetForm -> {
            pausingCall {
                formState.resetForm(action)
                ensureEngine()
                if (policy.enabled) calculateAll()
            }
            true
        }
        else -> false
    }

    /** Runs one trigger of a field: of widget [widgetIndex] when given, else of its first widget. */
    private suspend fun widgetEvent(
        fieldName: String,
        eventName: String,
        widgetIndex: Int? = null,
        select: (io.github.yuroyami.kitepdf.PdfWidgetActions) -> PdfAction?,
    ): Unit = pausingCall {
        ensureEngine()
        val field = if (policy.enabled) document.formField(fieldName) else null
        val actions = if (widgetIndex == null) field?.additionalActions else field?.widgets?.getOrNull(widgetIndex)?.additionalActions
        val action = actions?.let(select) as? PdfAction.JavaScript
        if (action != null) fieldEvent(fieldName, eventName, action)
    }

    /** Runs [action] as the [eventName] event of the field. Call it on the script thread. */
    private suspend fun fieldEvent(fieldName: String, eventName: String, action: PdfAction.JavaScript) {
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
    override suspend fun pumpTimers(nowMillis: Long): Long? = pausingCall {
        if (!started || !policy.enabled) return@pausingCall null
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

    private suspend fun dispatch(script: String, name: String, info: Map<String, Any?>): PdfScriptHost.EventResult {
        // An event that never reaches its script, as when the engine would not open, changes nothing.
        val unchanged = PdfScriptHost.EventResult(rc = true, value = info["value"] as? String ?: "", change = info["change"] as? String ?: "")
        val engine = openEngine ?: return unchanged
        try {
            engine.defineValue("__kiteEventInfo", info)
        } catch (e: KiteScriptException) {
            recordFailure(e)
            return unchanged
        }
        // The script runs as a function body, which is where a field's script lives in Acrobat,
        // so its own `var` declarations stay out of the global scope.
        evaluate("__kiteEvent(__kiteEventInfo, function () {\n$script\n})", name)
        return host.lastEventResult
    }

    private suspend fun evaluate(source: String, name: String): String? {
        if (!ensureEngine()) return null
        if (!policy.enabled) return null
        val engine = openEngine ?: return null
        eventStartedAt = now()
        return try {
            if (pausing) engine.evaluatePausing(source, name) else engine.evaluate(source, name)
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
    private suspend fun formatAll() {
        for (field in document.formFields) {
            if (field.additionalActions?.format == null) continue
            formatOnThread(field.fullyQualifiedName)
        }
    }

    private fun keystrokeScript(fieldName: String): String? =
        (document.formField(fieldName)?.additionalActions?.keystroke as? PdfAction.JavaScript)?.script

    private fun mergedValue(fieldName: String, change: String, start: Int, end: Int): String {
        val current = editingValue(fieldName)
        val from = start.coerceIn(0, current.length)
        val to = end.coerceIn(from, current.length)
        return current.substring(0, from) + change + current.substring(to)
    }

    private fun editingValue(fieldName: String): String {
        val field = document.formField(fieldName)
        if (field?.isEditableCombo == true) {
            formState.choiceSelection(fieldName)?.let { return choiceEventValue(field, it, face = true) }
        }
        return formState.value(fieldName).orEmpty()
    }

    override fun close() {
        if (closed) return
        closed = true
        // A paused script cannot close; it stops at its next check of the deadline, and its call closes the runner.
        if (busy > 0 && (engineSource != null || scriptThread.isInitialized() && !scriptThread.value.isOwnThread)) {
            closeWaits = true
            return
        }
        closeHere()
    }

    private fun closeHere() {
        closeWaits = false
        // Nothing to close when no script ever ran, because no engine was ever opened.
        if (engineSource != null || !scriptThread.isInitialized()) {
            openEngine?.close()
            return
        }
        val thread = scriptThread.value
        try {
            thread.call {
                try {
                    openEngine?.close()
                } finally {
                    openEngine = null
                    thread.leave(engineUser)
                }
            }
        } finally {
            thread.close()
        }
    }
}
