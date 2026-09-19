package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitejs.api.JsException
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.api.obj
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException

/**
 * A [KiteScriptEngine] that runs on KiteJS.
 *
 * Document scripts are untrusted, so every call runs under [instructionBudget]:
 * a script that never returns fails with a [KiteScriptException] instead of
 * hanging the app. The built-ins are read-only, so one script cannot redefine
 * what another relies on. Scripts reach nothing outside the engine except the
 * functions and values the host defines.
 */
public class KiteJsScriptEngine(
    /** Interpreter steps one call may take before it stops. 0 means no limit. */
    public val instructionBudget: Int = DEFAULT_INSTRUCTION_BUDGET,
    /**
     * Asked now and then while a script runs. Answer true and the script stops at once. A viewer
     * uses it for a wall clock deadline, or for a stop button, which an instruction count cannot
     * express: a document that legitimately runs for minutes needs time, not steps.
     */
    deadline: (() -> Boolean)? = null,
    /**
     * Where `Date.now()` reads the time, in milliseconds since the epoch. Leave it unset and the
     * engine reads the real clock. A test sets it so a script that measures time is repeatable.
     */
    clock: (() -> Long)? = null,
) : KiteScriptEngine {

    private val js = KiteJs {
        this.instructionBudget = this@KiteJsScriptEngine.instructionBudget
        safeBuiltins = true
        sealBuiltins = true
        deadline?.let { interruptWhen = it }
        clock?.let { source -> this.clock = { source().toDouble() } }
    }

    override fun evaluate(source: String, name: String): String? = guarded(name) {
        val value = js.evaluate(source, name)
        if (value.isNullish) null else value.asString()
    }

    override fun defineFunction(name: String, function: (List<Any?>) -> Any?) {
        val (owner, key) = ownerOf(name)
        owner.function(key) { args -> function(args.map { it.toKotlin() }) }
    }

    override fun defineValue(name: String, value: Any?) {
        val (owner, key) = ownerOf(name)
        owner[key] = value
    }

    override fun close(): Unit = js.close()

    /** The object that holds the last part of a dotted [path], made on the way when missing. */
    private fun ownerOf(path: String): Pair<JsObject, String> {
        val parts = path.split('.')
        require(parts.none { it.isEmpty() }) { "not a property path: $path" }
        var owner = js.global
        for (part in parts.dropLast(1)) {
            owner = (if (owner.has(part)) owner[part].asObjectOrNull() else null) ?: owner.obj(part)
        }
        return owner to parts.last()
    }

    private inline fun <T> guarded(name: String, block: () -> T): T = try {
        block()
    } catch (e: JsException) {
        throw KiteScriptException("$name: ${e.message}", e)
    } catch (e: RuntimeException) {
        // A budget stop or any other failure inside the engine is still the script failing.
        throw KiteScriptException("$name: ${e.message ?: e::class.simpleName}", e)
    }

    public companion object {
        /** Enough for any form script, small enough that a runaway loop stops within about a second. */
        public const val DEFAULT_INSTRUCTION_BUDGET: Int = 10_000_000
    }
}
