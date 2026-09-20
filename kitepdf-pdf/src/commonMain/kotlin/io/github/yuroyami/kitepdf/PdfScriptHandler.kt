package io.github.yuroyami.kitepdf

/**
 * What a viewer needs from a document's scripts, without knowing how they run.
 *
 * `kitepdf-javascript` implements this over KiteJS, and a host may implement it over any other
 * engine. The viewer talks to this interface only, so an app that shows PDFs does not carry a
 * JavaScript engine unless it asks for one.
 *
 * Every method is allowed to do nothing: a handler that runs no scripts is a valid handler, and a
 * viewer with no handler behaves as it always did.
 */
public interface PdfScriptHandler {

    /** Where the form's live values are, which the viewer draws and the scripts write. */
    public val formState: PdfFormState

    /** The document opened: run its own scripts and its open action. */
    public fun documentOpened() {}

    /** The reader reached this page: run its open script. */
    public fun pageOpened(pageIndex: Int) {}

    /** The reader left this page: run its close script. */
    public fun pageClosed(pageIndex: Int) {}

    /** The reader tapped something whose action is a script, such as a link. */
    public fun runAction(action: PdfAction.JavaScript) {}

    /** A pointer went down on a widget. */
    public fun mouseDown(fieldName: String) {}

    /** A pointer came up on a widget. */
    public fun mouseUp(fieldName: String) {}

    /** A field took the caret. */
    public fun focus(fieldName: String) {}

    /** A field lost the caret. */
    public fun blur(fieldName: String) {}

    /**
     * The reader typed into a field. [change] is what is being inserted, replacing the text
     * between [selectionStart] and [selectionEnd].
     *
     * Returns the value the field should show, or null when a script refused the change.
     */
    public fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String? = null

    /**
     * The reader finished with a field, so its value is committed: validate, store, recalculate
     * and format. Returns false when a script refused the value.
     */
    public fun commit(fieldName: String, value: String): Boolean {
        formState.setValue(fieldName, value)
        return true
    }

    /**
     * Runs the timers a script set, and answers how long to wait before the next one, or null
     * when none is waiting. The viewer calls this once a frame while [hasTimers] is true.
     */
    public fun pumpTimers(nowMillis: Long): Long? = null

    /** True when a script is waiting on a timer. */
    public val hasTimers: Boolean get() = false
}
