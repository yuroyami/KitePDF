package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException

/**
 * Runs a PDF's JavaScript: its document-level scripts, and JavaScript actions
 * such as a link's, when the host asks.
 *
 * This is the first step. Scripts see `app.viewerType`, `app.alert` and
 * `console.println`. The form and document objects that Acrobat scripts use
 * come later, so a script that reaches for them fails with a
 * [KiteScriptException] instead of running wrong.
 *
 * ```kotlin
 * PdfScriptRunner(doc, onAlert = { showDialog(it) }).use { runner ->
 *     runner.runDocumentScripts()
 * }
 * ```
 */
public class PdfScriptRunner(
    private val document: PdfDocument,
    /** Called for `app.alert(message)`. */
    onAlert: (String) -> Unit = {},
    /** Called for `console.println(text)`. */
    onConsole: (String) -> Unit = {},
    private val engine: KiteScriptEngine = KiteJsScriptEngine(),
) : AutoCloseable {

    init {
        engine.defineValue("app.viewerType", "KitePDF")
        // Acrobat's alert returns the button the reader pressed. 1 is OK.
        engine.defineFunction("app.alert") { args -> onAlert(messageOf(args.firstOrNull())); 1 }
        engine.defineFunction("console.println") { args -> onConsole(messageOf(args.firstOrNull())); null }
    }

    /**
     * Runs every document-level script in the order of their names, as a
     * viewer does when the document opens (ISO 32000-1, 7.7.4 and 12.6.4.16).
     * A script that fails is reported, and the ones after it still run.
     */
    public fun runDocumentScripts(): List<KiteScriptException> {
        val failures = ArrayList<KiteScriptException>()
        for ((name, source) in document.documentJavaScripts.entries.sortedBy { it.key }) {
            try {
                engine.evaluate(source, name)
            } catch (e: KiteScriptException) {
                failures += e
            }
        }
        return failures
    }

    /** Runs one JavaScript action and returns its result as text, or null. */
    public fun run(action: PdfAction.JavaScript): String? = engine.evaluate(action.script, "action")

    override fun close(): Unit = engine.close()

    /** Acrobat takes a message either as text or as `{ cMsg: "..." }`. */
    private fun messageOf(arg: Any?): String = when (arg) {
        null -> ""
        is Map<*, *> -> arg["cMsg"]?.let { messageOf(it) } ?: ""
        is Double -> if (arg % 1.0 == 0.0 && kotlin.math.abs(arg) < 1e15) arg.toLong().toString() else arg.toString()
        else -> arg.toString()
    }
}
