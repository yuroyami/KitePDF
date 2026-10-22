package io.github.yuroyami.kitepdf.javascript

/**
 * What a document's scripts are allowed to do, and for how long.
 *
 * A script inside a PDF is input from whoever made the file, so nothing runs unless the host says
 * so. This is where that decision lives: whether a document may run scripts at all, how long one
 * event may take, and what happens when a script asks to leave the document, for example by
 * submitting a form or opening a link.
 *
 * The defaults are the careful ones: scripts run, they stop after a few seconds, and everything
 * that reaches outside the document is refused until the host handles it.
 */
public class PdfScriptPolicy(
    /** Whether this document's scripts run at all. False makes every run a no-op. */
    public val enabled: Boolean = true,
    /**
     * How long one event may run before the engine stops it. A form script finishes in
     * milliseconds. A document that does real work in its open script, such as a game, needs a
     * larger budget, and the host is the one that decides to give it.
     */
    public val budgetMillis: Long = 5_000,
    /**
     * How long the whole document may spend in scripts, counted across every event. Zero means no
     * limit beyond the per-event budget.
     */
    public val documentBudgetMillis: Long = 0,
    /**
     * Interpreter steps one call may take, as a second line of defence for a loop that never asks
     * the clock. Zero means the wall clock is the only limit.
     */
    public val instructionBudget: Int = 0,
    /** Called every second or so while a script is still running, so a viewer can offer to stop it. */
    public val onStillRunning: ((elapsedMillis: Long) -> Boolean)? = null,
) {

    /** A policy that runs nothing, for a viewer that has not been told to trust the file. */
    public companion object {
        /** Scripts never run. */
        public val DENY: PdfScriptPolicy = PdfScriptPolicy(enabled = false)

        /** For a document that legitimately runs for minutes, such as a program embedded in a page. */
        public val LONG_RUNNING: PdfScriptPolicy = PdfScriptPolicy(budgetMillis = 0, instructionBudget = 0)
    }
}

/** What a script asked the host to do, when it wants something outside the document. */
public sealed class PdfScriptRequest {
    /** `app.launchURL(url)`: open a web address. */
    public data class LaunchUrl(val url: String) : PdfScriptRequest()

    /** `this.submitForm({ cURL: ... })`: send the form's values somewhere. */
    public data class SubmitForm(val url: String) : PdfScriptRequest()

    /** `this.print()`: print the document. */
    public data object Print : PdfScriptRequest()

    /** `this.mailDoc()` or `this.mailForm()`: send the document by mail. */
    public data object Mail : PdfScriptRequest()

    /** `this.closeDoc()` or `this.saveAs()`: close or save the file. */
    public data class Document(val what: String) : PdfScriptRequest()

    /** `app.beep()`. */
    public data object Beep : PdfScriptRequest()

    /** `this.pageNum = n` or `this.gotoNamedDest(name)`: move the reader. */
    public data class GoTo(val pageIndex: Int?, val namedDestination: String?) : PdfScriptRequest()

    /** `field.setFocus()`: put the caret in a field. */
    public data class Focus(val fieldName: String) : PdfScriptRequest()

    /** `app.execMenuItem(name)`. */
    public data class MenuItem(val name: String) : PdfScriptRequest()
}
