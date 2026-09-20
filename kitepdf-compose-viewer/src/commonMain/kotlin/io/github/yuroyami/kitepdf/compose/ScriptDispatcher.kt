package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where a document's scripts run: one thread of their own, kept away from the thread that draws.
 *
 * A document may legitimately work for a long time. A form script finishes in milliseconds, but a
 * file that carries a program in a page's open action can run for tens of seconds before it shows
 * anything, and the reader must still be able to scroll, zoom and close the document while it
 * does. A script engine also belongs to one thread, so every call has to arrive on the same one.
 *
 * JavaScript and WebAssembly have one thread, so there the scripts share it with everything else.
 */
internal expect fun kitepdfScriptDispatcher(): CoroutineDispatcher
