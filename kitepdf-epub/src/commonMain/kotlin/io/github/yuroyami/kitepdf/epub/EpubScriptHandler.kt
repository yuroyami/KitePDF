package io.github.yuroyami.kitepdf.epub

/**
 * What a viewer needs from a book's scripts, without knowing how they run (#41).
 *
 * [EpubScriptSession] runs them over any `KiteScriptEngine`, and `EpubScriptRunner` of
 * `kitepdf-javascript` over KiteJS on a thread of its own. Every method may do nothing: a handler
 * that runs no scripts is a valid handler, and a viewer without one shows the book as its markup
 * does.
 *
 * A viewer calls these methods off its drawing thread, one at a time and in the order the
 * reader acts, but not always from the same thread. A handler whose engine belongs to one thread
 * moves each call there itself, as `EpubScriptRunner` does. [hasTimers] is the exception: a
 * viewer reads it on its drawing thread every frame, so it must answer at once.
 *
 * The calls that run scripts suspend. On the web a handler may pause a long script in them and
 * let the page draw before it goes on (#489), so a viewer waits for each call before it makes
 * the next. Everywhere else they run to the end before they return.
 *
 * A script that changes its chapter changes the book: the chapter is laid out again, and
 * [EpubDocument.chapterChanges] tells the viewer to take the page counts again and draw.
 */
public interface EpubScriptHandler {

    /**
     * Gets the script engine ready. A viewer calls it once, before any other call: an engine that
     * compiles itself on the web does so here, as a browser compiles only asynchronously. Until
     * it returns, a call that needs the engine runs no script.
     */
    public suspend fun prepare() {}

    /**
     * The reader reached a page of [chapter]: run its scripts, the first time only, then fire
     * `DOMContentLoaded` and `load`. A viewer calls this for a chapter that
     * [EpubDocument.isScripted] says has scripts.
     */
    public suspend fun chapterOpened(chapter: Int) {}

    /**
     * The reader tapped [page] at ([x], [y]), in display space: the scripts of its chapter get a
     * `click` on the element there, after `pointerdown`, `mousedown`, `pointerup` and `mouseup`.
     * Runs the chapter's scripts first when it has not opened. Returns true when a script
     * prevented the default of the click, so that the viewer does not follow a link there.
     */
    public suspend fun tap(page: EpubPage, x: Double, y: Double): Boolean = false

    /**
     * Runs the timers and animation frames that scripts set and that are due, and answers how
     * long to wait before the next one in milliseconds, or null when none waits. [nowMillis] is
     * the viewer's frame time; a handler may measure its timers on a clock of its own.
     */
    public suspend fun pumpTimers(nowMillis: Long): Long? = null

    /** True when a script waits on a timer or an animation frame. */
    public val hasTimers: Boolean get() = false

    /**
     * Calls [listener] when a script sets or clears a timer, from the thread the script runs on,
     * and returns a function that stops the listening.
     */
    public fun onTimersChanged(listener: () -> Unit): () -> Unit = {}

    /**
     * Calls [listener] when a script asks to go somewhere: it set `location`, called `open`, or
     * clicked a link itself. A place in the book comes as a zip path with its fragment, as
     * [EpubLink.href] gives one, and anything else as written. Returns a function that stops the
     * listening.
     */
    public fun onNavigate(listener: (href: String) -> Unit): () -> Unit = {}
}
