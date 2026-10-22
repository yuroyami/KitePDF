package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitejs.api.JsEngineError
import io.github.yuroyami.kitejs.api.JsException
import io.github.yuroyami.kitejs.api.JsObject
import io.github.yuroyami.kitejs.api.JsScript
import io.github.yuroyami.kitejs.api.KiteJs
import io.github.yuroyami.kitejs.api.function
import io.github.yuroyami.kitejs.api.obj
import io.github.yuroyami.kitejs.quickjs.QuickJs
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.core.script.KiteScriptException

/**
 * A [KiteScriptEngine] that runs on KiteJS.
 *
 * Document scripts are untrusted, so every call runs under [instructionBudget]:
 * a script that never returns fails with a [KiteScriptException] instead of
 * hanging the app. By default the built-ins are read-only, so one script cannot
 * redefine what another relies on. Scripts reach nothing outside the engine
 * except the functions and values the host defines.
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
    /**
     * Whether the built-in objects are read-only. On by default, so a PDF's scripts, which share
     * one engine, cannot redefine what another relies on. A host that gives each document its own
     * engine and runs web scripts, which polyfill and patch the built-ins as they do in a browser,
     * turns it off.
     */
    sealBuiltins: Boolean = true,
    /**
     * Bytes of stack a script may use before deep recursion throws a RangeError it can catch. The
     * default fits the smallest stack a thread gets, 512 KiB on an Apple secondary thread. The
     * runners pass more, since their threads have stacks of their own. On JavaScript and
     * WebAssembly the browser's stack sets a lower cap.
     */
    maxStackBytes: Long = DEFAULT_STACK_BYTES,
) : KiteScriptEngine {

    private val js = KiteJs(ENGINE) {
        this.instructionBudget = this@KiteJsScriptEngine.instructionBudget
        this.sealBuiltins = sealBuiltins
        deadline?.let { interruptWhen = it }
        clock?.let { source -> this.clock = { source().toDouble() } }
        // A document cannot take the app's memory: past the limit its allocation fails and the call throws.
        memoryLimit = MEMORY_LIMIT
        maxStackSize = maxStackBytes
    }

    override fun evaluate(source: String, name: String): String? = guarded(name) {
        val value = if (source.length >= MIN_CACHED_SOURCE) script(source, name).run() else js.evaluate(source, name)
        if (value.isNullish) null else value.asString()
    }

    /** On the web, with WebAssembly stack switching, a long script pauses about once a frame (#489). */
    override suspend fun evaluatePausing(source: String, name: String): String? = guarded(name) {
        val value = if (source.length >= MIN_CACHED_SOURCE) script(source, name).runPausing() else js.evaluatePausing(source, name)
        if (value.isNullish) null else value.asString()
    }

    /**
     * [source] compiled, from the bytecode another engine of the process wrote for it when there
     * is one (#555). A large script, such as the DOM that each chapter's engine sets up first,
     * then is parsed once for the whole process.
     */
    private fun script(source: String, name: String): JsScript {
        Bytecodes.find(source, name)?.let { bytes ->
            try {
                return js.loadBytecode(bytes)
            } catch (_: JsEngineError) {
                // Bytecode this engine cannot read is dropped, and the source is parsed again.
                Bytecodes.forget(bytes)
            }
        }
        return js.compile(source, name).also { script -> script.bytecode()?.let { Bytecodes.keep(source, name, it) } }
    }

    override fun defineFunction(name: String, function: (List<Any?>) -> Any?): Unit = guarded(name) {
        val (owner, key) = ownerOf(name)
        owner.function(key) { args -> function(args.map { it.toKotlin() }) }
    }

    override fun defineValue(name: String, value: Any?): Unit = guarded(name) {
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

    /**
     * The bytecode of the large scripts that engines of this process compiled, the one used last
     * at the end. Bytecode is only ever read back for the very source that it was written from.
     */
    private object Bytecodes {
        private class Entry(val source: String, val name: String, val bytes: ByteArray)

        private val lock = KiteLock()
        private val entries = ArrayList<Entry>()

        fun find(source: String, name: String): ByteArray? = lock.withLock {
            // The same string object is the usual case, so a hit rarely compares the text itself.
            val i = entries.indexOfFirst { it.name == name && (it.source === source || it.source.length == source.length && it.source == source) }
            if (i < 0) null else entries.removeAt(i).also { entries.add(it) }.bytes
        }

        fun keep(source: String, name: String, bytes: ByteArray) = lock.withLock {
            entries.add(Entry(source, name, bytes))
            var total = entries.sumOf { it.bytes.size.toLong() + it.source.length * 2L }
            while (entries.size > MAX_CACHED_SCRIPTS || total > MAX_CACHED_BYTES && entries.size > 1) {
                total -= entries.removeAt(0).let { it.bytes.size.toLong() + it.source.length * 2L }
            }
        }

        fun forget(bytes: ByteArray) = lock.withLock { entries.removeAll { it.bytes === bytes } }
    }

    public companion object {
        /** Sources at least this long keep their bytecode for the other engines of the process (#555). */
        private const val MIN_CACHED_SOURCE = 32 * 1024

        /** How many sources keep their bytecode at most, and how much memory they and their bytecode take. */
        private const val MAX_CACHED_SCRIPTS = 16
        private const val MAX_CACHED_BYTES = 64L shl 20

        /** The engine underneath. Nothing else in this module names it. */
        internal val ENGINE = QuickJs

        /**
         * Gets the engine ready before the first [KiteJsScriptEngine] opens. On JavaScript and
         * WebAssembly it compiles the engine's WebAssembly, which a browser does only
         * asynchronously; everywhere else it returns at once. Calling it again costs nothing.
         */
        public suspend fun load(): Unit = ENGINE.load()

        /** True once [load] has finished, and from the start on every platform but the web. */
        public val isLoaded: Boolean get() = ENGINE.isLoaded

        /** Whether a thread holds one open engine at a time, so a runner sharing a thread with another must take turns. */
        internal val oneEnginePerThread: Boolean get() = ENGINE.oneEnginePerThread

        /** Why a runner ran nothing: the engine has to load first, which only the web asks for. */
        internal const val NOT_LOADED: String = "the script engine is not loaded yet; call prepare() first, which loads it on the web"

        /** Enough for any form script, small enough that a runaway loop stops within about a second. */
        public const val DEFAULT_INSTRUCTION_BUDGET: Int = 1_000_000

        /** The most memory one engine may allocate, in bytes: room for a compiled program's heap, and no more. */
        public const val MEMORY_LIMIT: Long = 512L shl 20

        /** What [maxStackBytes] is unless a caller says otherwise. */
        public const val DEFAULT_STACK_BYTES: Long = 256L shl 10
    }
}
