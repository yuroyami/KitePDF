package io.github.yuroyami.kitepdf.core

/** Receives one-line diagnostics from lenient salvage paths. */
public fun interface KiteWarningSink {
    public fun warn(message: String)
}

/**
 * The `fz_warn` equivalent: a process-global, nullable warning sink for the
 * places where the engine silently salvages (cached-null resolves, repair
 * fallbacks, skipped page-tree kids, failed filters, placeholder images).
 *
 * This is a debugging aid, not a stable event stream: message texts may
 * change between releases. The default null sink costs nothing. An installed
 * sink must be thread-safe; pages may render concurrently.
 */
public object KiteWarnings {
    // Volatile: installed from an app thread, read from every render/parse
    // worker. Without the release/acquire edge a worker could keep reading a
    // stale null (dropping diagnostics) or observe a partially published sink.
    @kotlin.concurrent.Volatile
    public var sink: KiteWarningSink? = null
}

/**
 * Emit through [KiteWarnings.sink]. Message construction is lazy (free when
 * no sink is installed) and a throwing sink can never break the caller.
 */
public fun kiteWarn(message: () -> String) {
    val sink = KiteWarnings.sink ?: return
    try {
        sink.warn(message())
    } catch (_: Exception) {
        // A diagnostics hook must never take down the render/parse path.
    }
}
