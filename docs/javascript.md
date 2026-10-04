# JavaScript

PDF files carry JavaScript: a form that computes a total, a field that formats what is typed, a
button that hides another field, a link that runs a script. EPUB chapters carry it too, as a quiz
that checks an answer or a page whose button changes a picture. The core library reads those
scripts and runs none of them. The `kitepdf-javascript` artifact runs them, on
[KiteJS](https://github.com/yuroyami/KiteJS), a JavaScript engine written in Kotlin. The scripts
of a PDF are below; those of an EPUB are in [Scripts in an EPUB](#scripts-in-an-epub).

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf-javascript:0.12.0")
}
```

## Fill a form the way a viewer does

```kotlin
val doc = PdfDocument.open(bytes)
PdfScriptRunner(doc, onAlert = { alert -> showDialog(alert.message); 1 }).use { runner ->
    runner.runDocumentOpen()                 // the document's scripts, then its open action
    runner.setFieldValue("price", "1200")    // keystroke, validate, calculate, then format
    runner.formState.value("total")          // what the form's own script worked out
    runner.formattedValue("total")           // what the field shows: "$1,200.00"
}
```

Values land in `runner.formState`, a `PdfFormState`. The file is not touched: the viewer draws
from that state and `PdfEditor` saves it when you ask.

`PdfScriptRunner.run(action)` runs one `PdfAction.JavaScript`, such as the action of a link a
reader tapped.

## Where a document keeps its scripts

A file puts its scripts in four places, and KitePDF reads all of them:

| Where | How to reach it |
|---|---|
| The document opens | `doc.openAction` |
| The document is saved or printed | `doc.additionalActions`, with `willSave`, `didSave`, `willPrint`, `didPrint` and `willClose` |
| A page opens or closes | `page.openAction`, `page.closeAction` |
| A field or a button | `field.additionalActions`, with `keystroke`, `format`, `validate`, `calculate`, `mouseDown`, `mouseUp`, `focus` and `blur` |
| The document's own library | `doc.documentJavaScripts` |

`PdfScriptRunner` fires the document and page triggers, and the field triggers while a form is
filled. The Compose viewer does not fire them on its own yet.

## What scripts can reach

The target is Chrome's PDF viewer: a script that works there is meant to work here. Chrome uses
PDFium, which puts the document's own members on the global object, so both spellings work:

```js
getField("total").value = 42;        // what a file written for Chrome says
this.getField("total").value = 42;   // what a file written for Acrobat says
```

Scripts see:

| Object | What is there |
|---|---|
| The document | `getField`, `getNthFieldName`, `numPages`, `numFields`, `pageNum`, `info`, `calculateNow`, `resetForm`, `submitForm`, `print`, `gotoNamedDest` and the rest of the document surface |
| `Field` | `value`, `valueAsString`, `type`, `page`, `rect`, `hidden`, `display`, `readonly`, `required`, `checkThisBox`, `isBoxChecked`, `getItemAt`, `setFocus` and more |
| `event` | `value`, `change`, `rc`, `willCommit`, `selStart`, `selEnd`, `target`, `targetName`, `name`, `type` |
| `app` | `alert`, `response`, `beep`, `setInterval`, `setTimeOut`, `clearInterval`, `clearTimeOut`, `launchURL`, `viewerType` |
| `util` | `printf`, `printd`, `printx`, `scand`, `byteToChar` |
| `color`, `console`, `display`, `border`, `font`, `global` | the constants and helpers a form script expects |
| The `AF` library | `AFNumber_Format`, `AFNumber_Keystroke`, `AFPercent_*`, `AFDate_*`, `AFTime_*`, `AFSpecial_*`, `AFSimple`, `AFSimple_Calculate`, `AFRange_Validate`, `AFMergeChange`, `AFMakeNumber`, `AFExtractNums` |

Most forms never write their own formatting code: they call the `AF` helpers, which is why they
are here.

## Timers

`app.setInterval` and `app.setTimeOut` do not run on their own. The host pumps them, so a
document can never take the thread:

```kotlin
val waitMillis = runner.pumpTimers(nowMillis)   // runs what is due, says when to come back
```

## What a script asks of the host

Anything that reaches outside the document arrives at `onRequest` as a `PdfScriptRequest`, and
nothing happens unless the host acts on it:

```kotlin
PdfScriptRunner(doc, onRequest = { request ->
    when (request) {
        is PdfScriptRequest.LaunchUrl -> askThenOpen(request.url)
        is PdfScriptRequest.SubmitForm -> refuse()
        else -> Unit
    }
})
```

## Limits

Document scripts are untrusted input, so `PdfScriptPolicy` decides what they may do:

```kotlin
PdfScriptRunner(doc, policy = PdfScriptPolicy(budgetMillis = 2_000))   // stop after two seconds
PdfScriptRunner(doc, policy = PdfScriptPolicy.DENY)                    // run nothing at all
PdfScriptRunner(doc, policy = PdfScriptPolicy.LONG_RUNNING)            // a document that runs for minutes
```

A script that passes its budget stops and is reported in `runner.failures`. `onStillRunning` is
asked first, so a viewer can offer to keep waiting, the way a browser does. A PDF's scripts share
one engine, so its built-in objects are read-only and one script cannot redefine what another
relies on. A script reaches nothing outside the engine except what the runner defines.

Denying scripts does not stop a reader filling the form: values still go into the form state, and
only the scripts are silent.

## A document that carries a whole program

Some PDFs hold a program compiled from C, put through Emscripten and dropped into a page's
open action. DoomPDF is the well known one: the game runs in the script and draws itself into
two hundred text fields, one per screen row.

Such a program is written in asm.js, a subset of JavaScript whose types are all known before it
runs, and KiteJS compiles it ahead of time to typed code. That is the difference between a frame
every few seconds and a frame that a reader would call playable. It happens on its own; nothing
has to be switched on.

`runner.asmReports` says what the engine did with each module, one line each:

```kotlin
runner.runPageOpen(0)
for (report in runner.asmReports) println(report)   // "asm: compiled"
```

A module that does not compile still runs and still gives the same answers, and the line names the
first thing in it that asm.js does not allow. The list is empty until the first script runs, and
for any engine other than KiteJS.

## Threads

An engine belongs to one thread, so a runner does too: make it and use it from the same thread.
The engine itself is opened by the first script that runs, not when the runner is made, so a host
may construct a runner on one thread and hand it to the thread that will use it. The Compose
viewer does exactly that: it keeps a thread for scripts and posts every call to it.

`PdfFormState` is the exception, and deliberately so: it is written by the scripts and read by
whatever draws, so it is safe from two threads.

## Other engines

The runner talks to the `KiteScriptEngine` interface in `kitepdf-core`. `KiteJsScriptEngine` is the KiteJS implementation. Pass your own engine to `PdfScriptRunner` to use another one.

## Scripts in an EPUB

`EpubScriptRunner` runs the scripts of a book's chapters on KiteJS, over the library's own parse
and layout, so a scripted chapter works on every target with no web engine:

```kotlin
val book = EpubDocument.open(bytes)
EpubScriptRunner(book).use { scripts ->
    scripts.chapterOpened(0)                    // the chapter's scripts, then DOMContentLoaded and load
    val page = book.page(KiteLocation(0, 0))
    scripts.tap(page, x = 52.5, y = 90.0)       // a click on the element there, in display space
}
```

Each chapter is a window of its own, with an engine of its own. Its scripts run the first time it
opens, inline and from the book, in document order. A module script does not run, and neither
does a script at an address outside the book. Once scripts run, `noscript` content no longer
shows. A chapter without scripts takes no engine.

A chapter's built-in objects are writable, as a browser's are, so a polyfill can add a method the
engine lacks and a library can wrap one it has
([#537](https://github.com/yuroyami/KitePDF/issues/537)). A script that breaks a built-in breaks
only its own chapter. The DOM below still calls the built-ins the book patched, so a patch that
changes what a method does changes the DOM too
([#540](https://github.com/yuroyami/KitePDF/issues/540)).

At most eight chapters keep their engines open at once (`EpubScriptRunner.LIVE_CHAPTERS`), and
one on JavaScript and WebAssembly, where every engine shares the page's one thread. Opening one
more closes the engine of the chapter used least recently, as a reading system unloads the
chapters the reader left. That chapter keeps what its scripts made of it, and when it opens
again its scripts start over from its markup, as a page does when it loads again.

They see a DOM over the chapter: `document` with `getElementById`, `querySelector`,
`querySelectorAll` and the other finders, `createElement` and fragments; nodes and elements with
the tree walk, `appendChild` and its relatives, `textContent`, `innerHTML` and `outerHTML`,
attributes, `id`, `className`, `classList`, `dataset`, `hidden`, `style`, `matches` and
`closest`; events with `addEventListener`, `on` attributes and properties, capture and bubbling,
`preventDefault` and `stopPropagation`; `setTimeout`, `setInterval` and `requestAnimationFrame`;
`console`; `localStorage` and `sessionStorage`, in memory for the book; and
`navigator.epubReadingSystem`, whose `hasFeature` answers true for `dom-manipulation`,
`layout-changes`, `spine-scripting` and `mouse-events`, false for `touch-events` and
`keyboard-events`, and undefined for a feature it does not know. A check box, a radio
button, a label, a `summary` and a submit button do what a click on them does in a browser.
`getComputedStyle` answers `display`, `visibility`, `color`, `background-color`, `font-size`,
`font-weight`, `font-style`, `opacity`, `text-align`, `position`, `float`, `width`, `height`,
`z-index`, `left` and `top`. An element's `style` answers the CSS properties by their camel-case
and dashed names, with a number as a browser writes it back, `0.5` for `.5`, and any other name
is a plain property of the object. `getBoundingClientRect`, `offsetWidth` and their relatives
answer where the element is on its page, in CSS pixels; when the script changed the chapter
since it was laid out, it is laid out again first, as a browser does, so an element the script
just added has its size.

A tap goes to the element under it as `pointerdown`, `mousedown`, `pointerup`, `mouseup` and
`click`. `tap` answers true when a script prevented the click, and then a viewer does not follow
a link there. A script that changes its chapter has it laid out again from the changed tree as
soon as the script returns, and the chapter may gain or lose pages: `book.chapterChanges` moves
for a viewer to take the page counts again, and `page.chapterVersion` for it to draw the page
again. The changes belong to the book, so a new font size keeps them.

Each callback that the reader calls itself runs in a call of its own: a listener or an `on`
handler of a tap or of the load events, a timer, an animation frame. The promise jobs it queued
run as soon as it returns, before the next callback, as HTML runs a microtask checkpoint after
each callback it invokes. A script's own `dispatchEvent` or `click()` runs its listeners at once,
and their jobs wait until the script is done, as in a browser. The timers that fall due together
run in the order they fell due, and a timer or a frame that an earlier callback cancels does not
run.

Each book has an origin of its own, shared by its chapters: `epub://` and a host made from the
package's unique identifier, so the book has it each time it opens. `self.origin` and
`location.origin` answer it, and `location.href` is the origin and the chapter's path.

`URL` and `URLSearchParams` are the WHATWG URL Standard's, with every getter and setter,
`URL.canParse`, `URL.parse` and `toJSON`, and a `searchParams` that writes itself back to the
query. A URL against the chapter's address resolves inside the book, and `..` stops at the
book's root as it stops at a site's, so a script cannot build an address above the container. A
URL of the book has the book's origin, so `new URL(location.href).origin` is `location.origin`.
Host names go through UTS #46 of Unicode 17, with Punycode, IPv4 and IPv6 hosts as a browser
reads them. The web-platform-tests of the standard check it: `WhatwgUrlTest` runs its URL,
setter and host data against the parser, and `WebPlatformTest` runs its JavaScript tests in a chapter.

`DOMException` is the one of Web IDL, with `name`, `message` and the legacy `code` as getters of
its prototype, the 25 legacy constants such as `NOT_FOUND_ERR` and the derived
`QuotaExceededError`. An instance is an `Error` underneath, so it has a stack where an `Error`
has one. The DOM methods throw one with the name the DOM Standard gives, such as `SyntaxError`
for a selector that does not parse, and `WebPlatformTest` runs the `DOMException` tests of
web-platform-tests in a chapter.

`TextEncoder` and `TextDecoder` are the Encoding Standard's, with every encoding and label of its
table: UTF-8, UTF-16, the 28 single-byte encodings, gb18030 and GBK, Big5, EUC-JP, ISO-2022-JP,
Shift_JIS, EUC-KR, `replacement` and `x-user-defined`, each with the error mode the standard
gives it. `fatal`, `ignoreBOM`, `{ stream: true }` across calls and `encodeInto` behave as the
standard says. `atob` and `btoa` are the HTML Standard's, with forgiving base64, and throw an
`InvalidCharacterError`. The decoders live in Kotlin over the standard's own indexes, and
`WebPlatformTest` runs the `encoding` tests and the `atob` tests of web-platform-tests in a
chapter. The `encoding` tests that fail wait on `MessageChannel`
([#534](https://github.com/yuroyami/KitePDF/issues/534)) or on the engine: `SharedArrayBuffer`
([KiteJS#72](https://github.com/yuroyami/KiteJS/issues/72)), `Float16Array`
([KiteJS#73](https://github.com/yuroyami/KiteJS/issues/73)) and a rest parameter in an arrow
function ([KiteJS#71](https://github.com/yuroyami/KiteJS/issues/71)).

`Blob`, `File` and `FileReader` are the File API's. A blob takes strings, buffers, views and
other blobs, with `endings` and a `type` kept as the standard keeps it, and `slice`, `text`,
`arrayBuffer` and `bytes`, whose promises settle in a task. A `FileReader` reads in tasks of its
own and fires `loadstart`, `progress`, `load` and `loadend` in that order, or `abort` and
`loadend` when `abort()` stops it; `readAsText` takes the encoding its argument names, else the
charset of the blob's type, else UTF-8, and a byte order mark wins over all three, through the
decoders of `TextDecoder`.

`URL.createObjectURL` gives a blob a `blob:` URL of the book's origin, and the reader loads an
image, a style sheet, an `@import` or a font from it as it loads a file of the book.
`URL.revokeObjectURL` takes it away from the scripts, but what the chapter shows keeps it, as a
browser keeps an image it loaded: an image whose script revoked its URL right after setting it
still shows, and still does at another font size. A chapter whose engine closes revokes the URLs
its scripts made, as a page that unloads does.

`WebPlatformTest` runs the File API tests of web-platform-tests in a chapter. Those that fail wait
on `MessageChannel` ([#534](https://github.com/yuroyami/KitePDF/issues/534)), on streams, as
`Blob.stream()` and `Blob.textStream()` do ([#536](https://github.com/yuroyami/KitePDF/issues/536)),
on an element's class string ([#538](https://github.com/yuroyami/KitePDF/issues/538)), or on the
engine: `Float16Array` and `async` functions, which nine of the files are written in.

Nothing reaches outside the book. There is no `fetch` or `XMLHttpRequest`. A change of
`location`, `window.open` and a script's own click on a link go to the listeners of
`onNavigate`, with a zip path and its fragment for a place in the book; an address under the
book's origin is such a place. `alert`, `confirm` and `prompt` go to `onConsole`, and answer as
dismissed. `failures` keeps the last hundred, since a timer that throws each time it runs would
otherwise grow it for as long as the book is open.

Not there yet: the documents of `<iframe>` and `<object>` elements, whose boxes stay as they are;
form controls drawn on the page, since an `<input>` has no box yet, though scripts read and set
its value and checkedness; drawing on a `<canvas>`, whose `getContext` answers `null`
([#501](https://github.com/yuroyami/KitePDF/issues/501)); `ReadableStream` and the rest of the
Streams Standard, so `Blob.stream()` ([#536](https://github.com/yuroyami/KitePDF/issues/536));
`postMessage`, which drops each
message, `MessageChannel` and `structuredClone`
([#534](https://github.com/yuroyami/KitePDF/issues/534)); `FormData`
([#531](https://github.com/yuroyami/KitePDF/issues/531)); and the syntax KiteJS does not have yet, `class`
([KiteJS#2](https://github.com/yuroyami/KiteJS/issues/2)), `const` in the head of a `for` loop
([KiteJS#11](https://github.com/yuroyami/KiteJS/issues/11)) and `async`
([KiteJS#12](https://github.com/yuroyami/KiteJS/issues/12)). A script that uses one fails to
parse and is listed in `failures`, and the chapter goes on as its other scripts leave it.

Real books keep this true: `ScriptedBookGateTest` runs the scripted books of the public corpus,
the W3C tests of spine-level scripting and an IDPF sample that drives its pages with jQuery 1.7.1,
and checks what each page shows afterwards. A failure a book is known to hit is listed there with
its issue.

In `KiteDocView`, pass the runner as `epubScripts`, and the viewer opens chapters, sends taps,
pumps timers and follows the scripts' changes itself; see
[A book's scripts](compose-viewer.md#a-books-scripts).

`EpubScriptPolicy` sets how long each call may run: opening a chapter, a tap, a round of timers.
`EpubScriptPolicy.DENY` runs nothing. The runner runs its calls on a thread of its own, as
`PdfScriptRunner` does, and opens each chapter's engine on a thread of its own too, since a
KiteJS engine holds the thread that opened it. A listener of `onNavigate` or `onTimersChanged`
runs on one of them while a script waits for it, so it hands its work on rather than calling the
runner. `EpubScriptSession` in `kitepdf-epub` is the same thing over any `KiteScriptEngine`, on
the caller's thread; its `liveChapters` says how many chapters' engines stay open.

## Targets

JVM, Android, iOS, macOS, Linux, Windows, JavaScript and WebAssembly: the targets KiteJS builds for. tvOS, watchOS and Android native are not covered.
