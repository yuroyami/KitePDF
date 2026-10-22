package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubScriptHandler
import io.github.yuroyami.kitepdf.epub.EpubScriptSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a book's scripts are allowed to do, and for how long (#41).
 *
 * A script inside an EPUB is input from whoever made the file. The defaults are the careful
 * ones: scripts run, and each thing the reader does, opening a chapter, a tap, a round of
 * timers, may keep them busy for five seconds before the engine stops them.
 */
public class EpubScriptPolicy(
    /** Whether the book's scripts run at all. False makes every call a no-op. */
    public val enabled: Boolean = true,
    /**
     * How long one call may run before the engine stops it. Zero means no limit. The DOM the
     * library sets up in a chapter's engine before the book's first script does not count (#554).
     */
    public val budgetMillis: Long = 5_000,
    /**
     * Interpreter steps one engine call may take, as a second line of defence for a loop that
     * never asks the clock. Zero means the clock is the only limit.
     */
    public val instructionBudget: Int = 0,
) {
    public companion object {
        /** Scripts never run. */
        public val DENY: EpubScriptPolicy = EpubScriptPolicy(enabled = false)
    }
}

/**
 * Runs the scripts of a book on KiteJS, for `KiteDocView` or any other host (#41).
 *
 * ```kotlin
 * val scripts = EpubScriptRunner(book)
 * KiteDocView(state, epubScripts = scripts)
 * ```
 *
 * The scripts of each chapter run in a window of their own, over the library's own parse and
 * layout: [EpubScriptSession] says what they see. A runner can be called from any thread. It
 * runs every call one at a time and in order, as `PdfScriptRunner` does. Off the web, it runs
 * them on a thread of its own. A KiteJS engine belongs to the thread that opened it, and each
 * chapter's engine opens on a thread of its own, so the call goes there while it runs that
 * chapter's scripts (#498). [close] stops every one of them. On the web, a long script pauses now
 * and then so the page can draw, and the next call waits for it (#489). A listener of [onNavigate] or
 * [onTimersChanged] runs on one of them while a script waits for it, so it hands its work on, as
 * `KiteDocView` does, rather than calling the runner.
 *
 * At most [LIVE_CHAPTERS] chapters keep their engines open: opening another closes the engine of
 * the chapter used least recently, whose scripts start over from its markup when it opens again.
 * On JavaScript and WebAssembly the engine loads before its first use: call [prepare] once and
 * wait for it, as `KiteDocView` does.
 *
 * @param onConsole gets what scripts print with `console`, and what `alert`, `confirm` and
 *   `prompt` would have shown, on one of the runner's threads.
 * @param clock milliseconds on a clock that only goes forward, for the timers, the budget and
 *   `Date.now()`. Leave it unset for the real one; a test sets it so a timer is repeatable.
 * @param instanceKey a value the app keeps for its reader, such as an install id, which makes the
 *   book's origin one of this reader's copy (#521). Without one each runner has an origin of its own.
 * @param fontOutlines the outline of a run of text in a host font, in glyph space as
 *   `KiteCanvas.hostGlyphOutline` answers it. A canvas's `getImageData` and `toDataURL` draw text
 *   with it. Without it, text reads back as blank.
 */
public class EpubScriptRunner(
    public val document: EpubDocument,
    public val policy: EpubScriptPolicy = EpubScriptPolicy(),
    private val onConsole: (level: String, message: String) -> Unit = { _, _ -> },
    private val clock: (() -> Long)? = null,
    private val instanceKey: String? = null,
    private val fontOutlines: ((text: String, font: FontSpec) -> KitePath?)? = null,
) : EpubScriptHandler, AutoCloseable {

    private val startMark = kotlin.time.TimeSource.Monotonic.markNow()
    private fun now(): Long = clock?.invoke() ?: startMark.elapsedNow().inWholeMilliseconds

    /** The thread the runner's engines live on, started by the first call. */
    private val scriptThread = lazy { startScriptThread() }

    @kotlin.concurrent.Volatile
    private var closed = false

    /** When the call in progress started, for the budget: written on the script thread, read on a chapter's. */
    @kotlin.concurrent.Volatile
    private var callStartedAt = 0L

    /** True while a chapter's engine runs the session's own DOM, which the budget leaves out (#554). */
    @kotlin.concurrent.Volatile
    private var settingUp = false

    /** The session, made on the script thread by the first call that needs it. */
    @kotlin.concurrent.Volatile
    private var session: EpubScriptSession? = null

    /** How many calls of this runner the script thread is inside, so its engines stay open while a script runs. */
    private var busy = 0

    /** Unloads the chapters when another runner opens an engine on the thread they share. */
    private val engineUser = EngineUser {
        if (busy > 0) return@EngineUser false
        session?.unloadChapters()
        true
    }

    private val lock = KiteLock()
    private val timerListeners = ArrayList<() -> Unit>()
    private val navigationListeners = ArrayList<(String) -> Unit>()

    /** Every script that failed since the runner opened, newest last. */
    public val failures: List<KiteScriptException> get() = session?.failures.orEmpty()

    override val hasTimers: Boolean get() = session?.hasTimers == true

    /** Loads the engine, which on JavaScript and WebAssembly has to happen before the first script. */
    override suspend fun prepare(): Unit = KiteJsScriptEngine.load()

    override suspend fun chapterOpened(chapter: Int) {
        if (!policy.enabled) return
        call { it.chapterOpened(chapter) }
    }

    override suspend fun tap(page: EpubPage, x: Double, y: Double): Boolean {
        if (!policy.enabled) return false
        return call { it.tap(page, x, y) } ?: false
    }

    override suspend fun pumpTimers(nowMillis: Long): Long? {
        if (!policy.enabled) return null
        return call { it.pumpTimers(nowMillis) }
    }

    override fun onTimersChanged(listener: () -> Unit): () -> Unit {
        lock.withLock { timerListeners.add(listener) }
        return { lock.withLock { timerListeners.remove(listener) } }
    }

    override fun onNavigate(listener: (href: String) -> Unit): () -> Unit {
        lock.withLock { navigationListeners.add(listener) }
        return { lock.withLock { navigationListeners.remove(listener) } }
    }

    /**
     * Runs [block] with the session on the script thread, and waits for it; null once closed. On a
     * thread of its own no engine can pause, so the call runs to its end there. Where the work runs
     * where it is called, as on the web, a long script pauses and lets the page draw, and the next
     * call waits for it (#489).
     */
    private suspend fun <T> call(block: suspend (EpubScriptSession) -> T): T? {
        if (closed) return null
        val thread = scriptThread.value
        if (thread.isOwnThread) return thread.call { turn { session -> runNow { block(session) } } }
        return turns.withLock { turn { session -> block(session) } }
    }

    /** One call of the runner, with the budget started; a [close] that came while it was paused ends it. */
    private inline fun <T> turn(block: (EpubScriptSession) -> T): T? {
        if (closed) return null
        callStartedAt = now()
        busy++
        try {
            return block(sessionHere())
        } finally {
            busy--
            if (closed && busy == 0 && closeWaits) closeHere()
        }
    }

    /** Takes turns where a script may pause, so that no call reaches an engine while its script waits. */
    private val turns = Mutex()

    /** Whether [close] came while a script was paused, and left the closing to the call that runs it. */
    private var closeWaits = false

    /** The session, made on first use on the script thread. Each chapter's engine opens on a thread of its own. */
    private fun sessionHere(): EpubScriptSession = session ?: EpubScriptSession(
        document,
        engineFor = {
            check(KiteJsScriptEngine.isLoaded) { KiteJsScriptEngine.NOT_LOADED }
            // Where every engine shares one thread, another runner's engine closes first (#553).
            scriptThread.value.makeRoom(engineUser)
            val thread = startScriptThread()
            OwnDomFirst(ThreadedScriptEngine(thread) {
                // A book's scripts polyfill and patch the built-ins as in a browser, and each chapter has an
                // engine of its own, so the seal would guard nothing (#537).
                KiteJsScriptEngine(
                    instructionBudget = policy.instructionBudget,
                    deadline = ::deadlinePassed,
                    clock = clock,
                    sealBuiltins = false,
                    maxStackBytes = thread.stackBytes,
                )
            })
        },
        onConsole = onConsole,
        clock = ::now,
        instanceKey = instanceKey,
        fontOutlines = fontOutlines,
        liveChapters = if (scriptThread.value.isOwnThread || !KiteJsScriptEngine.oneEnginePerThread) LIVE_CHAPTERS else 1,
    ).also { made ->
        made.onTimersChanged { lock.withLock { timerListeners.toList() }.forEach { it() } }
        made.onNavigate { href -> lock.withLock { navigationListeners.toList() }.forEach { it(href) } }
        session = made
    }

    /** True once the call in progress has used the policy's time, or the runner is closing. */
    private fun deadlinePassed(): Boolean {
        if (closed) return true
        if (settingUp || policy.budgetMillis <= 0) return false
        return now() - callStartedAt > policy.budgetMillis
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!scriptThread.isInitialized()) return
        // A paused script cannot close; it stops at its next check of the deadline, and its call closes the runner.
        if (busy > 0 && !scriptThread.value.isOwnThread) {
            closeWaits = true
            return
        }
        closeHere()
    }

    private fun closeHere() {
        closeWaits = false
        val thread = scriptThread.value
        try {
            thread.call {
                try {
                    session?.close()
                } finally {
                    thread.leave(engineUser)
                }
            }
        } finally {
            thread.close()
        }
    }

    /**
     * A chapter's [engine], whose first script, the session's own DOM, runs outside the budget,
     * which starts again once it returns: the budget measures the book's scripts, and the DOM
     * takes hundreds of milliseconds to set up on a slow device (#554). The host functions that
     * the session defines before it are part of the setup too.
     */
    private inner class OwnDomFirst(private val engine: KiteScriptEngine) : KiteScriptEngine by engine {
        private var first = true

        override fun defineFunction(name: String, function: (List<Any?>) -> Any?) {
            if (first) settingUp { engine.defineFunction(name, function) } else engine.defineFunction(name, function)
        }

        override fun defineValue(name: String, value: Any?) {
            if (first) settingUp { engine.defineValue(name, value) } else engine.defineValue(name, value)
        }

        override fun evaluate(source: String, name: String): String? {
            if (!first) return engine.evaluate(source, name)
            first = false
            return settingUp { engine.evaluate(source, name) }
        }

        override suspend fun evaluatePausing(source: String, name: String): String? {
            if (!first) return engine.evaluatePausing(source, name)
            first = false
            return settingUp { engine.evaluatePausing(source, name) }
        }

        private inline fun <T> settingUp(block: () -> T): T {
            settingUp = true
            try {
                return block()
            } finally {
                settingUp = false
                callStartedAt = now()
            }
        }
    }

    public companion object {
        /** How many chapters keep their engines open at once where each has a thread of its own. */
        public const val LIVE_CHAPTERS: Int = 8
    }
}
