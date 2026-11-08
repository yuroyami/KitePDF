package io.github.yuroyami.kitepdf.core

/** Receives one-line diagnostics from lenient salvage paths. */
public fun interface KiteWarningSink {
    public fun warn(message: String)
}

/** What a salvage warning is about, coarsely (#899). Most warnings are [Other]. */
public enum class KiteWarningKind { Parse, Font, Image, Filter, Script, Other }

/**
 * One salvage warning with its context (#899): the [document] it came from, the [pageIndex] of the
 * page being drawn or read when it came, null outside a page, and a coarse [kind]. The [message]
 * is a debugging aid whose text may change between releases.
 */
public class KiteWarning(
    public val message: String,
    public val document: Any?,
    public val pageIndex: Int?,
    public val kind: KiteWarningKind,
) {
    override fun toString(): String = "KiteWarning(${pageIndex?.let { "page $it, " } ?: ""}$kind: $message)"
}

/** Receives the warnings of one document, each with its context (#899). It must be thread-safe: pages may render at once. */
public fun interface KiteWarningListener {
    public fun warn(warning: KiteWarning)
}

/**
 * The `fz_warn` equivalent: a process-global, nullable warning sink for the
 * places where the engine silently salvages (cached-null resolves, repair
 * fallbacks, skipped page-tree kids, failed filters, placeholder images).
 *
 * This is a debugging aid, not a stable event stream: message texts may
 * change between releases. The default null sink costs nothing. An installed
 * sink must be thread-safe; pages may render concurrently.
 *
 * A document can have a [KiteWarningListener] of its own (#899), as `PdfDocument.warnings` and
 * `EpubDocument.warnings` take one: its warnings then go there, with the page they came from,
 * and not to [sink]. A document without one reports here, as before.
 */
public object KiteWarnings {
    // Volatile: installed from an app thread, read from every render/parse
    // worker. Without the release/acquire edge a worker could keep reading a
    // stale null (dropping diagnostics) or observe a partially published sink.
    @kotlin.concurrent.Volatile
    public var sink: KiteWarningSink? = null
}

/**
 * Where the warnings of the work on this thread go (#899): a document's entry points, such as
 * opening it and drawing or reading a page, run inside [with], so a warning that their code
 * raises, however deep, reaches the document's listener with the page. Scopes nest, and an
 * inner one takes the listener and the document of the outer one when it names none.
 */
public object KiteWarningScope {
    internal class Frame(val listener: KiteWarningListener?, val document: Any?, val pageIndex: Int?, val parent: Frame?)

    /** Runs [block] with its warnings going to [listener], about [document] and page [pageIndex]. */
    public inline fun <T> with(listener: KiteWarningListener?, document: Any?, pageIndex: Int?, block: () -> T): T {
        val token = enter(listener, document, pageIndex)
        try {
            return block()
        } finally {
            exit(token)
        }
    }

    /** Opens a scope, for [with]; returns what [exit] takes. */
    public fun enter(listener: KiteWarningListener?, document: Any?, pageIndex: Int?): Any? {
        val outer = WarningScopeSlot.current
        val frame = Frame(listener ?: outer?.listener, document ?: outer?.document, pageIndex ?: outer?.pageIndex, outer)
        WarningScopeSlot.current = frame
        return outer
    }

    /** Closes the scope that [enter] opened. */
    public fun exit(token: Any?) {
        WarningScopeSlot.current = token as Frame?
    }
}

/** The innermost [KiteWarningScope] of this thread. */
internal expect object WarningScopeSlot {
    var current: KiteWarningScope.Frame?
}

/**
 * Emit through the listener of the current [KiteWarningScope], or else [KiteWarnings.sink].
 * Message construction is lazy (free when there is nothing to report to) and a throwing sink can
 * never break the caller.
 */
public fun kiteWarn(message: () -> String): Unit = kiteWarn(KiteWarningKind.Other, message)

/** As [kiteWarn], with the [kind] of the warning. */
public fun kiteWarn(kind: KiteWarningKind, message: () -> String) {
    val frame = WarningScopeSlot.current
    val listener = frame?.listener
    try {
        if (listener != null) {
            listener.warn(KiteWarning(message(), frame.document, frame.pageIndex, kind))
            return
        }
        val sink = KiteWarnings.sink ?: return
        sink.warn(message())
    } catch (_: Exception) {
        // A diagnostics hook must never take down the render/parse path.
    }
}
