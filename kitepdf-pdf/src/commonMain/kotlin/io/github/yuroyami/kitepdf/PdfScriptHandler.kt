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
 *
 * A viewer calls these methods off its drawing thread, one at a time and in the order the events
 * happen, but not always from the same thread. A handler whose engine belongs to one thread moves
 * each call there itself, as `PdfScriptRunner` does. [hasTimers] is the exception: a viewer reads
 * it on its drawing thread every frame, so it must answer at once.
 */
public interface PdfScriptHandler {

    /** Where the form's live values are, which the viewer draws and the scripts write. */
    public val formState: PdfFormState

    /**
     * The document opened: run its own scripts and its open action.
     *
     * A viewer calls this once for each handler, however often its view leaves and comes back. A
     * viewer made with a new state calls it again, for example after a configuration change that
     * did not keep the state, so a handler that outlives its viewer runs the scripts on the first
     * call only, as `PdfScriptRunner` does.
     */
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
     * A pointer went down on one widget of a field: [widgetIndex] is its place in
     * [PdfFormField.widgets]. Each button of a radio group, and each copy of a field on another
     * page, has scripts of its own, so a handler that runs scripts runs that widget's (#359).
     * The default passes the call on without the widget.
     */
    public fun mouseDown(fieldName: String, widgetIndex: Int) {
        mouseDown(fieldName)
    }

    /** A pointer came up on one widget of a field. See the other [mouseDown]. */
    public fun mouseUp(fieldName: String, widgetIndex: Int) {
        mouseUp(fieldName)
    }

    /** One widget of a field took the caret. See [mouseDown] with a widget. */
    public fun focus(fieldName: String, widgetIndex: Int) {
        focus(fieldName)
    }

    /** One widget of a field lost the caret. See [mouseDown] with a widget. */
    public fun blur(fieldName: String, widgetIndex: Int) {
        blur(fieldName)
    }

    /**
     * The reader released a widget of [fieldName] whose `/A` entry holds [action], or chains it
     * through `/Next` (ISO 32000-1, 12.5.6.19). A viewer calls this once for each action of the
     * chain, in order, and the chain takes the place of the widget's mouse up script (Table 194).
     *
     * Performs a script or a form reset and returns true. Returns false for any other action,
     * which the viewer performs itself: a go-to or a page turn in the document, and a link, a
     * submit or a print through the host.
     */
    public fun runWidgetAction(fieldName: String, action: PdfAction): Boolean = when (action) {
        is PdfAction.JavaScript -> {
            runAction(action)
            true
        }
        is PdfAction.ResetForm -> {
            formState.resetForm(action)
            true
        }
        else -> false
    }

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
