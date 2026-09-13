package io.github.yuroyami.kitepdf.core.script

/**
 * A JavaScript engine for the scripts that documents carry: PDF JavaScript
 * actions and document-level scripts today, EPUB scripting later.
 *
 * KitePDF ships no engine of its own. Add `kitepdf-javascript` for one that runs
 * on KiteJS, or implement this interface over another engine. Code that only
 * needs to run scripts should take this interface, so it never depends on an
 * engine directly.
 */
public interface KiteScriptEngine : AutoCloseable {

    /**
     * Runs [source] and returns its result as text, or null when the result is
     * `undefined` or `null`. [name] labels the script in error messages.
     *
     * @throws KiteScriptException when the script throws, does not parse, or runs
     *   past the engine's limits.
     */
    public fun evaluate(source: String, name: String = "<script>"): String?

    /**
     * Makes [function] callable from scripts under [name], which may be a dotted
     * path such as `app.alert`: missing objects on the way are created. Arguments
     * arrive as Kotlin values (String, Double, Boolean, List, Map or null), and
     * the return value goes back the same way.
     */
    public fun defineFunction(name: String, function: (List<Any?>) -> Any?)

    /** Sets [name], a plain or dotted path, to [value]: a string, number, boolean, list, map or null. */
    public fun defineValue(name: String, value: Any?)
}

/** A script failed: it threw, did not parse, or ran past the engine's limits. */
public class KiteScriptException(message: String, cause: Throwable? = null) : Exception(message, cause)
