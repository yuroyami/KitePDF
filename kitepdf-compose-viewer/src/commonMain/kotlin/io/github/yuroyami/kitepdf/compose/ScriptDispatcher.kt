package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where the viewer calls a document's script handler: one call at a time, in the order asked, and
 * never on the thread that draws.
 *
 * A document may legitimately work for a long time. A form script finishes in milliseconds, but a
 * file that carries a program in a page's open action can run for tens of seconds before it shows
 * anything, and the reader must still be able to scroll, zoom and close the document while it
 * does. So a call may block the thread it runs on, and this dispatcher is one where blocking is
 * allowed.
 *
 * The thread can change from one call to the next. A handler whose engine belongs to one thread
 * moves the work there itself, as `PdfScriptRunner` does (#355).
 *
 * JavaScript and WebAssembly have one thread, so there the scripts share it with everything else.
 */
internal expect fun kitepdfScriptDispatcher(): CoroutineDispatcher
