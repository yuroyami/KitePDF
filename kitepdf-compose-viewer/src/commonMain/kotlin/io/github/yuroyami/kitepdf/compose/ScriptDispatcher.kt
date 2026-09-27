package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A new lane for one viewer's calls to a document's script handler: one call at a time, in the
 * order asked, and never on the thread that draws.
 *
 * A document may legitimately work for a long time. A form script finishes in milliseconds, but a
 * file that carries a program in a page's open action can run for tens of seconds before it shows
 * anything, and the reader must still be able to scroll, zoom and close the document while it
 * does. So a call may block the thread it runs on, and a lane is one where blocking is allowed.
 *
 * Each viewer takes a lane of its own, so a document that works for a long time holds up only its
 * own viewer, and the calls that a closed viewer left waiting go with it (#365).
 *
 * The thread can change from one call to the next. A handler whose engine belongs to one thread
 * moves the work there itself, as `PdfScriptRunner` does (#355).
 *
 * JavaScript and WebAssembly have one thread, so there the scripts share it with everything else.
 */
internal expect fun newScriptLane(): CoroutineDispatcher
