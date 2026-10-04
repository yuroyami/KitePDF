package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.script.KiteScriptException
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubScriptHandler
import io.github.yuroyami.kitepdf.epub.EpubScriptSession

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
    /** How long one call may run before the engine stops it. Zero means no limit. */
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
 * runs every call on a thread of its own, one at a time and in order, as `PdfScriptRunner` does.
 * A KiteJS engine belongs to the thread that opened it, and a thread holds one, so each
 * chapter's engine opens on a thread of its own too, and the call goes there while it runs that
 * chapter's scripts (#498). [close] stops every one of them. A listener of [onNavigate] or
 * [onTimersChanged] runs on one of them while a script waits for it, so it hands its work on, as
 * `KiteDocView` does, rather than calling the runner.
 *
 * At most [LIVE_CHAPTERS] chapters keep their engines open, and on JavaScript and WebAssembly,
 * which have one thread for every engine, one: opening another closes the engine of the chapter
 * used least recently, whose scripts start over from its markup when it opens again.
 *
 * @param onConsole gets what scripts print with `console`, and what `alert`, `confirm` and
 *   `prompt` would have shown, on one of the runner's threads.
 * @param clock milliseconds on a clock that only goes forward, for the timers, the budget and
 *   `Date.now()`. Leave it unset for the real one; a test sets it so a timer is repeatable.
 */
public class EpubScriptRunner(
    public val document: EpubDocument,
    public val policy: EpubScriptPolicy = EpubScriptPolicy(),
    private val onConsole: (level: String, message: String) -> Unit = { _, _ -> },
    private val clock: (() -> Long)? = null,
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

    /** The session, made on the script thread by the first call that needs it. */
    @kotlin.concurrent.Volatile
    private var session: EpubScriptSession? = null

    private val lock = KiteLock()
    private val timerListeners = ArrayList<() -> Unit>()
    private val navigationListeners = ArrayList<(String) -> Unit>()

    /** Every script that failed since the runner opened, newest last. */
    public val failures: List<KiteScriptException> get() = session?.failures.orEmpty()

    override val hasTimers: Boolean get() = session?.hasTimers == true

    override fun chapterOpened(chapter: Int) {
        if (!policy.enabled) return
        call { it.chapterOpened(chapter) }
    }

    override fun tap(page: EpubPage, x: Double, y: Double): Boolean {
        if (!policy.enabled) return false
        return call { it.tap(page, x, y) } ?: false
    }

    override fun pumpTimers(nowMillis: Long): Long? {
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

    /** Runs [block] with the session on the script thread, and waits for it; null once closed. */
    private fun <T> call(block: (EpubScriptSession) -> T): T? {
        if (closed) return null
        return scriptThread.value.call {
            if (closed) return@call null
            callStartedAt = now()
            block(sessionHere())
        }
    }

    /** The session, made on first use on the script thread. Each chapter's engine opens on a thread of its own. */
    private fun sessionHere(): EpubScriptSession = session ?: EpubScriptSession(
        document,
        engineFor = {
            ThreadedScriptEngine(startScriptThread()) {
                // A book's scripts polyfill and patch the built-ins as in a browser, and each chapter has an
                // engine of its own, so the seal would guard nothing (#537).
                KiteJsScriptEngine(
                    instructionBudget = policy.instructionBudget,
                    deadline = ::deadlinePassed,
                    clock = clock,
                    sealBuiltins = false,
                )
            }
        },
        onConsole = onConsole,
        clock = ::now,
        liveChapters = if (scriptThread.value.isOwnThread) LIVE_CHAPTERS else 1,
    ).also { made ->
        made.onTimersChanged { lock.withLock { timerListeners.toList() }.forEach { it() } }
        made.onNavigate { href -> lock.withLock { navigationListeners.toList() }.forEach { it(href) } }
        session = made
    }

    /** True once the call in progress has used the policy's time, or the runner is closing. */
    private fun deadlinePassed(): Boolean {
        if (closed) return true
        if (policy.budgetMillis <= 0) return false
        return now() - callStartedAt > policy.budgetMillis
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!scriptThread.isInitialized()) return
        val thread = scriptThread.value
        try {
            thread.call { session?.close() }
        } finally {
            thread.close()
        }
    }

    public companion object {
        /** How many chapters keep their engines open at once where each has a thread of its own. */
        public const val LIVE_CHAPTERS: Int = 8
    }
}
