# JavaScript

PDF files can carry JavaScript: a form that computes a total, a field that formats what the reader types, a button that hides another field, a link that runs a script. EPUB chapters can carry it too, for example a quiz that checks an answer or a page whose button changes a picture.

The core library reads these scripts and runs none of them. The `kitepdf-javascript` artifact runs them on the QuickJS engine of [KiteJS](https://github.com/yuroyami/KiteJS):

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf-javascript:0.13.0")
}
```

- [PDF scripts](#pdf-scripts) covers `PdfScriptRunner`.
- [Scripts in the Compose viewer](#scripts-in-the-compose-viewer) covers `KiteDocView`.
- [Scripts in an EPUB](#scripts-in-an-epub) covers `EpubScriptRunner`.

## PDF scripts

### Fill a form as a viewer does

```kotlin
val doc = PdfDocument.open(bytes)
PdfScriptRunner(doc, onAlert = { alert -> showDialog(alert.message); 1 }).use { runner ->
    // The document's scripts, then its open action
    runner.runDocumentOpen()
    // Keystroke, validate, calculate, then format
    runner.setFieldValue("price", "1200")
    // What the form's own script worked out
    runner.formState.value("total")
    // What the field shows, such as "$1,200.00"
    runner.formattedValue("total")
}
```

The values go into `runner.formState`, a `PdfFormState`, and the file does not change. The viewer draws from that state. To write the values to a file, see [Save the values to a file](editing.md#save-the-values-to-a-file).

`runner.run(action)` runs one `PdfAction.JavaScript`, such as the action of a link that the reader tapped.

### Where a document keeps its scripts

| When the script runs | Where to find it |
| --- | --- |
| The document opens | `doc.openAction` |
| The document is saved, printed or closed | `doc.additionalActions`, with `willSave`, `didSave`, `willPrint`, `didPrint` and `willClose` |
| A page opens or closes | `page.openAction`, `page.closeAction` |
| The reader uses a field or a button | `field.additionalActions`, with `keystroke`, `format`, `validate`, `calculate`, `mouseDown`, `mouseUp`, `focus` and `blur` |
| Before any other script | `doc.documentJavaScripts`, the document's own library |

`PdfScriptRunner` runs the document and page scripts, and the field scripts while the reader fills in a form. `KiteDocView` calls the runner for each of these events when you [pass it a runner](#scripts-in-the-compose-viewer).

### What scripts can reach

The target is the PDF viewer of Chrome: a script that works there should work here. Chrome uses PDFium, which puts the members of the document on the global object, so both spellings work:

```js
getField("total").value = 42;        // a file written for Chrome
this.getField("total").value = 42;   // a file written for Acrobat
```

| Object | What it has |
| --- | --- |
| The document | `getField`, `getNthFieldName`, `numPages`, `numFields`, `pageNum`, `info`, `calculateNow`, `resetForm`, `submitForm`, `print`, `gotoNamedDest` and the rest of the document API |
| `Field` | `value`, `valueAsString`, `type`, `page`, `rect`, `hidden`, `display`, `readonly`, `required`, `checkThisBox`, `isBoxChecked`, `getItemAt`, `setFocus` and more |
| `event` | `value`, `change`, `rc`, `willCommit`, `selStart`, `selEnd`, `target`, `targetName`, `name`, `type` |
| `app` | `alert`, `response`, `beep`, `setInterval`, `setTimeOut`, `clearInterval`, `clearTimeOut`, `launchURL`, `viewerType` |
| `util` | `printf`, `printd`, `printx`, `scand`, `byteToChar` |
| `color`, `console`, `display`, `border`, `font`, `global` | The constants and helpers that a form script expects |
| The `AF` library | `AFNumber_Format`, `AFNumber_Keystroke`, `AFPercent_*`, `AFDate_*`, `AFTime_*`, `AFSpecial_*`, `AFSimple`, `AFSimple_Calculate`, `AFRange_Validate`, `AFMergeChange`, `AFMakeNumber`, `AFExtractNums` |

Most forms do not have formatting code of their own. They call the `AF` helpers, so the runner has them.

### Timers

`app.setInterval` and `app.setTimeOut` do not run on their own. The host runs them, so a document cannot keep the thread:

```kotlin
// Runs the timers that are due, and returns when to call again (null when none waits)
val waitMillis = runner.pumpTimers(nowMillis)
```

### Requests to the host

A script that wants to reach outside the document sends a `PdfScriptRequest` to `onRequest`. Nothing happens unless the host acts on it:

```kotlin
PdfScriptRunner(doc, onRequest = { request ->
    when (request) {
        is PdfScriptRequest.LaunchUrl -> askThenOpen(request.url)
        is PdfScriptRequest.SubmitForm -> refuse()
        else -> Unit
    }
})
```

The requests are `LaunchUrl`, `SubmitForm`, `Print`, `Mail`, `Document`, `Beep`, `GoTo`, `Focus` and `MenuItem`.

`onAlert` shows `app.alert` and returns the button that the reader pressed (1 is OK). `onResponse` answers `app.response`, or returns null when the reader cancels. `onConsole` gets `console.println`.

### Limits

The scripts of a document are untrusted input. `PdfScriptPolicy` decides what they can do:

```kotlin
// Stop each event after two seconds
PdfScriptRunner(doc, policy = PdfScriptPolicy(budgetMillis = 2_000))
// Run nothing
PdfScriptRunner(doc, policy = PdfScriptPolicy.DENY)
// A document that runs for minutes
PdfScriptRunner(doc, policy = PdfScriptPolicy.LONG_RUNNING)
```

| Property | Default | What it does |
| --- | --- | --- |
| `enabled` | true | False runs no script. |
| `budgetMillis` | 5,000 | How long one event can run before the engine stops it. 0 is no limit. |
| `documentBudgetMillis` | 0 | How long all events together can run. 0 is no limit. |
| `instructionBudget` | 0 | How many engine steps one call can take, for a loop that never reads the clock. 0 is no limit. |
| `onStillRunning` | null | Called when a script passes `budgetMillis`. Return true to give the script one more budget, or false to stop it. A viewer can ask the reader, as a browser does. |

- A script that goes over its budget stops, and `runner.failures` lists it. `onStillRunning` can give it more time first.
- The scripts of a PDF share one engine. So the built-in objects are read-only, and one script cannot change what another one needs.
- A script reaches nothing outside the engine except what the runner gives it.
- With `DENY`, the reader can still fill in the form. The values still go into the form state, and only the scripts do nothing.

### A document that carries a whole program

Some PDFs hold a program compiled from C with Emscripten, in the open action of a page. DoomPDF is a well-known one: the game runs in the script and draws itself into two hundred text fields, one for each screen row. Such a program runs as any other script does. Give it `PdfScriptPolicy.LONG_RUNNING`, because it runs for longer than the default budget.

## Scripts in the Compose viewer

`KiteDocView` runs no script unless you give it a script handler. `kitepdf-javascript` has one for a PDF and one for a book:

```kotlin
// A PDF
val runner = remember(doc) { PdfScriptRunner(doc, onAlert = { alert -> showDialog(alert.message); 1 }) }
DisposableEffect(runner) { onDispose { runner.close() } }
KiteDocView(state = state, scripts = runner)

// An EPUB
val scripts = remember(book) { EpubScriptRunner(book) }
DisposableEffect(scripts) { onDispose { scripts.close() } }
KiteDocView(state = state, epubScripts = scripts)
```

With a PDF handler, the viewer does these things:

- It runs the document scripts and the open and close scripts of each page.
- It sends a tap on a form field to the scripts.
- It draws the form from its live values.
- It runs the timers that a script set, one frame at a time.

The viewer draws the page once and keeps it. It draws again only the fields that changed. So a script that writes a field twenty times a second costs twenty small redraws.

### Filling in a form

With a handler, a tap on a form field does what it does in a desktop viewer:

- A push button runs its press and release scripts.
- A check box or a radio button changes the value of the form.
- A text field takes the cursor and opens the keyboard. Each character that the reader types goes through the keystroke script of the field first, so a field for digits refuses a letter. When the reader leaves the field, the viewer runs validate, then calculate, then format.

The viewer shows a typed character at once. When the keystroke script refuses it, the field goes back to its earlier text.

`state.focusedField` is the name of the field that has the cursor, or null.

A choice field uses the control that its PDF flags ask for (ISO 32000-1, 12.7.4.4):

- A combo box that the reader cannot edit opens a list of its options. The list shows the labels. A choice stores the export value and opens no keyboard.
- An editable combo box also takes typed text, and shows the options whose labels match.
- A list box shows its rows in the field. In a list box with multiple selection, a tap selects or clears one row and keeps the other rows. With `CommitOnSelChange`, each change commits at once. Otherwise the selection commits when the reader leaves the list or presses Enter.

On a keyboard, the arrow keys, Home and End move through the options. Space selects or clears the current row. Enter accepts the selection and Escape discards it. Tab and Shift+Tab move to the next and the previous editable form field. A screen reader hears whether each option is selected.

### Choice fields in code

A choice option has an `index` (its place in the `/Opt` array of the file), an `exportValue` and a `label`. A `PdfChoiceSelection` names options by index, so two options with equal labels or equal export values stay different:

```kotlin
val field = requireNotNull(doc.formField("interests"))
val options: List<PdfChoiceOption> = field.choiceOptions
val proposed = PdfChoiceSelection(indices = options.take(2).map { it.index })

// Run the keystroke script for the change, without changing the live value
val preview = runner.choiceKeystroke(field.fullyQualifiedName, proposed)
// Commit the selection that the script accepted
val accepted = preview != null && runner.commitChoice(field.fullyQualifiedName, preview)
val current: PdfChoiceSelection? = runner.formState.choiceSelection(field.fullyQualifiedName)
```

- `choiceKeystroke` returns the selection that the script accepted (a script can change it), or null when the script refuses it.
- `commitChoice` runs the commit keystroke, validate, calculate and format, and stores the selection.
- An editable combo box can hold `freeText` instead of indices. No indices and no text means no selection.
- A value in the file that matches no option stays in `unresolvedValues`, so a damaged form keeps its value until the reader changes it.
- `field.options` gives the labels only. Use `choiceOptions` and `choiceSelection` to tell options apart.

`PdfFormState.value(name)` gives one string. For a multiple selection, it gives the first selected export value in the order of the options. `setValue(name, value)` selects the first option with that export value.

`formState.setChoiceSelection(name, selection)` changes the live value without scripts. It returns false for a selection that the field cannot hold. For an edit of your own:

1. Read `formState.fieldRevision(name)` when the edit starts.
2. Pass that revision to `setChoiceSelection(name, selection, expectedRevision)`. The call refuses the change when the field changed in between, or when the field is hidden or read-only.
3. To drop an edit that has not committed, call `cancelChoiceTransaction(name, expectedRevision)`.

Check a selection from the reader with `field.validateChoiceSelection(selection)`, which returns null for a selection that the field cannot hold.

!!! warning
    `PdfEditor.setChoiceValue` saves one value. Do not save a multiple selection by passing `formState.value(name)` to it, because that saves only the first selected value.

A `PdfScriptHandler` of your own gets typed choices through `choiceKeystroke` and `commitChoice`. By default, these call your `keystroke` and `commit` with a single string, and refuse a multiple selection. To support multiple selections, override `commitChoice`, `choiceKeystroke` and `supportsMultipleChoices`.

### Scripts of a book in the viewer

The viewer runs the scripts of a chapter when the reader first gets to it:

- A tap on a page goes to the scripts first, as a `click` on the element under it. The viewer follows a link there only when no script prevented the click.
- A change of `location` that a script asks for goes the same way as a tapped link, through `onLinkTap`.
- The viewer runs the timers that the scripts set.
- When a script changes its chapter, the viewer lays the chapter out again, counts its pages again and draws the pages that changed.

[Scripts in an EPUB](#scripts-in-an-epub) tells what the scripts see.

## Threads

A runner can be called from any thread. It runs every call on a thread of its own, one at a time and in order, and `close()` stops that thread. The callbacks of the runner run on that thread too. A form script ends in milliseconds, but a document with a program in the open action of a page can work for tens of seconds before it shows anything. Meanwhile, the reader can still scroll and close the document.

`PdfFormState` can be read and written from two threads, because the scripts write it and the viewer reads it.

On JS and Wasm, the engine is a WebAssembly module that loads before its first use. Call `prepare()` on the runner once, and wait for it before the first script, as `KiteDocView` does. A script that runs before then fails with a message that tells you so. On the other targets, `prepare()` returns at once.

A browser page has one thread, so a long script would stop the page from drawing. For this reason, the event methods of a runner are `suspend` functions:

- On the web, a script that one of them runs pauses about every 16 ms, so the page can draw and run its timers.
- A call that comes while a script is paused waits until that script ends.
- The engine pauses only on a runtime with WebAssembly stack switching: Chrome 137 and newer, and Node 25 and newer. On other runtimes, the script runs to its end in one go.
- The other methods, such as `run` and `setFieldValue`, never pause.
- The budget of a script counts the pauses too, so it measures how long the reader waits.

## Other engines

The runners talk to the `KiteScriptEngine` interface of `kitepdf-core`. `KiteJsScriptEngine` is the KiteJS engine. To use another engine, pass it as `engine` to `PdfScriptRunner`. The runner then runs on the thread of the caller, and the caller must make every call on the thread that made the engine.

`EpubScriptSession` in `kitepdf-epub` runs the scripts of a book over any `KiteScriptEngine`, on the thread of the caller.

## Scripts in an EPUB

`EpubScriptRunner` runs the scripts of the chapters of a book on KiteJS, over the parse and the layout of KitePDF. So a chapter with scripts works on every target, with no web engine:

```kotlin
val book = EpubDocument.open(bytes)
EpubScriptRunner(book).use { scripts ->
    // The chapter's scripts, then DOMContentLoaded and load
    scripts.chapterOpened(0)
    val page = book.page(KiteLocation(0, 0))
    // A click on the element at this point, in display space
    scripts.tap(page, x = 52.5, y = 90.0)
}
```

`EpubScriptPolicy` sets how long each call can run: the opening of a chapter, a tap, a round of timers. The default is five seconds. `EpubScriptPolicy.DENY` runs nothing. The budget does not count the DOM that KitePDF sets up in the engine of a chapter before the first script of the book. That setup takes a few hundred milliseconds, or seconds on a slow device, so a tight budget stops the scripts of the book and never the DOM that they need.

`failures` keeps the last 100 failures, because a timer that fails each time would otherwise make the list grow while the book is open.

### Chapters and engines

- Each chapter is a window of its own, with an engine of its own.
- The scripts of a chapter run the first time that it opens, inline and from files of the book, in the order of the document.
- A module script does not run, and a script at an address outside the book does not run.
- After the scripts run, `noscript` content does not show.
- A chapter without scripts takes no engine.

The built-in objects of a chapter can be changed, as in a browser, so a polyfill can add a method that the engine does not have. A script that breaks a built-in object breaks only its own chapter. The DOM of KitePDF takes every built-in function that it calls before the first script of the book runs. So a script that changes a built-in method, or adds a getter to `Object.prototype`, does not change what the DOM does.

At most eight chapters keep their engines open at the same time (`EpubScriptRunner.LIVE_CHAPTERS`). When one more chapter opens, the engine of the chapter used least recently closes. That chapter keeps what its scripts made of it. When it opens again, its scripts start again from its markup, as a web page does when it loads again. `EpubScriptSession.unloadChapters()` closes them all at once.

`KiteJsScriptEngine` keeps the bytecode of every script of 32 KB or more, and a later engine loads that bytecode instead of parsing the script again. The DOM that each chapter sets up first is such a script, so each chapter after the first sets up faster.

### Frames

An `<iframe>` whose `src` is a document of the book shows that document. The scripts of the document run in a window of their own, with an engine of their own. An `<object>` whose `data` is an HTML or XHTML document of the book works the same way.

- The documents of the frames load before the `load` event of the chapter. Then each frame element gets its own `load` event.
- The window of a frame has the origin of the book and `navigator.epubReadingSystem`.
- A frame that a script adds loads and runs the same way.
- A tap on a frame goes to the document of the frame.
- The script of a frame can move to a fragment of its own document, and nowhere else. The reader keeps the place in the book.
- A chapter whose frames run scripts counts as a chapter with scripts, also when it has no script of its own.
- An engine that cannot share its thread, such as Rhino, runs no frame scripts.

The chapter and its frames see each other as windows of another origin, as EPUB 3.3 asks for a script in a container:

- `contentWindow`, `parent` and `top` have `postMessage`, `close`, `focus`, `blur`, `closed`, `length`, `parent` and `top`. Any other member throws a `SecurityError`.
- `contentDocument` is null.
- So the scripts of a frame cannot reach or change the chapter around it, or the size of the frame.
- A message between two windows is a structured clone. A `MessagePort` can move to another window and stays linked to its partner. A stream cannot move to another window.

### The DOM

The scripts see a DOM over the chapter.

**The document and its elements**

- `document` has `getElementById`, `querySelector`, `querySelectorAll` and the other finders, `createElement`, `createComment` and fragments.
- Nodes and elements have the tree walk, `appendChild` and its relatives, `textContent`, `innerHTML`, `outerHTML`, attributes, `id`, `className`, `classList`, `dataset`, `hidden`, `style`, `matches` and `closest`.
- Each object has the interface and the class string that a browser gives it. So `String(document.body)` is `[object HTMLBodyElement]`, and `document.body instanceof HTMLBodyElement` is true.
- An HTML element has the interface that HTML names for its tag, such as `HTMLDivElement`, or `HTMLUnknownElement` for a tag that HTML does not have. Each attribute that reflects its markup reads and writes it with the type, the default and the keywords of HTML.
- An SVG element has its SVG interface, and a MathML element is a `MathMLElement`.
- `childNodes`, `children`, the results of `getElementsByTagName`, `getElementsByClassName` and `getElementsByName`, and collections such as `elements`, `options`, `rows`, `forms` and `links` are live. Each one is the same object on each read and follows the tree as it changes. `querySelectorAll` gives a `NodeList` of the elements that it found when it ran.
- The tree has the comments of the chapter, as in a browser. The page draws none of them, and `textContent` leaves them out.
- The document starts with its document type. An XHTML chapter that is well-formed XML also has its processing instructions and CDATA sections.
- An HTML chapter with no document type, or with an old one, is in quirks mode, and `document.compatMode` is `BackCompat`. The layout does not change with the mode.

**XHTML and HTML chapters**

| | XHTML chapter (`application/xhtml+xml`) | HTML chapter (`text/html`) |
| --- | --- | --- |
| Document | `XMLDocument` | `HTMLDocument` |
| `tagName` | Keeps its case (`p`, `linearGradient`) | Upper case for HTML elements |
| `createElement` | Keeps the case of its argument | Makes its argument lower case |
| Attribute names | As XML names them. `xlink:href` is `href` in the XLink namespace once the prefix is declared. | As the HTML parser names them: lower case, with the case of SVG and MathML names such as `viewBox` given back |
| Names in selectors | Compared as written | Compared without case, as Blink does |
| `innerHTML`, `outerHTML`, `insertAdjacentHTML` | Read and write XML. Markup that is not well-formed throws a `SyntaxError` and changes nothing. | Read and write HTML |
| `document.write`, `writeln`, `open`, `close` | Throw an `InvalidStateError` | Work |

`document.contentType` tells which kind a chapter is. `getAttribute` finds an attribute by its qualified name, and `getAttributeNS` by its namespace and local name. `element.attributes` is a live `NamedNodeMap` of `Attr` nodes.

**Style and layout**

- `getComputedStyle` answers `display`, `visibility`, `color`, `background-color`, `font-size`, `font-weight`, `font-style`, `opacity`, `text-align`, `position`, `float`, `width`, `height`, `z-index`, `left`, `top` and `transform`, which it writes as a `matrix()`.
- The `style` of an element answers the CSS properties by their camel-case and dashed names. It writes a number back as a browser does, so `.5` reads as `0.5`. Any other name is a plain property of the object.
- `getBoundingClientRect`, `offsetWidth` and their relatives give the place of the element on its page, in CSS pixels. When the script changed the chapter after the last layout, the chapter lays out again first, as in a browser.
- `DOMPoint`, `DOMQuad`, `DOMMatrix`, their read-only versions and `WebKitCSSMatrix` follow the Geometry Interfaces. A `DOMMatrix` takes 6 or 16 numbers, a matrix dictionary, or a CSS transform list. A length in that list must be absolute, so `em` and percentages throw a `SyntaxError`.
- `querySelector`, `querySelectorAll`, `matches` and `closest` read Selectors 4, as the layout reads the style sheets of a book. A selector that a browser rejects throws a `SyntaxError`.
- The states of a form control, such as `:checked`, `:placeholder-shown` and `:indeterminate`, follow what the reader or a script did to the control, and the page restyles when they change. Setting `checked` or `selected` does not write an attribute. The input type of a control cleans its value, as HTML says.
- A pseudo-class that cannot be true in a book of pages, such as `:hover`, matches nothing.

**Events and timing**

- Events have `addEventListener`, `on` attributes and properties, capture and bubbling, `preventDefault` and `stopPropagation`.
- A tap goes to the element under it as `pointerdown`, `mousedown`, `pointerup`, `mouseup` and `click`. `tap` returns true when a script prevented the click, and a viewer then does not follow a link there.
- A check box, a radio button, a label, a `summary` and a submit button do what a click on them does in a browser.
- `setTimeout`, `setInterval` and `requestAnimationFrame` work.
- Each callback runs on its own: a listener, an `on` handler, a timer or an animation frame. The promise jobs that it queued run when it returns, before the next callback, as HTML does after each callback. A script's own `dispatchEvent` or `click()` runs the listeners at once, and their jobs wait until the script ends.
- Timers that are due at the same time run in the order in which they fell due. A timer or a frame that an earlier callback cancels does not run.
- An `on` handler is an entry in the list of listeners of its target, as in HTML. It takes its place when it is first set, keeps that place when it is set again, and leaves the list when it is set to null. A handler in the markup takes its place when the parser of a browser would make its element.
- The code of an `on` attribute runs with the names of the document, the form of its control and the element in scope.
- The window handlers of the body element, such as `onload`, belong to the window. `document.onreadystatechange` runs when the state of the document changes.

**Changes to the chapter**

When a script changes its chapter, the chapter lays out again from the changed tree as soon as the script returns. The chapter can gain or lose pages. `book.chapterChanges` changes so that a viewer counts the pages again, and `page.chapterVersion` changes so that it draws the page again. The changes belong to the book, so a new font size keeps them.

**Canvas and images**

- A `<canvas>` gives its 2D context, `CanvasRenderingContext2D`, with `Path2D`, `CanvasGradient`, `CanvasPattern` and `TextMetrics`. A context other than `2d` is `null`.
- What a script draws shows on the page where the canvas is, and a later drawing paints the page again. The context keeps its drawing as SVG, so a canvas looks the same on every target.
- Paths, fills, strokes, dashes, clips, transforms, gradients (linear, radial and conic), patterns, images and other canvases, text, shadows, `filter`, every `globalCompositeOperation` and every blend mode work.
- `isPointInPath` and `isPointInStroke` answer as Chromium does, with a point on an edge inside.
- Text uses the metrics of the standard fonts, so `measureText` is near the value of a browser but not equal to it.
- `ImageData`, `createImageData`, `getImageData` and `putImageData` read and write pixels, in `rgba-unorm8` or `rgba-float16`, and in sRGB or Display P3. The pixels come from `KiteRasterCanvas`, a rasterizer in plain Kotlin, so they are the same on every target. They keep each color multiplied by its alpha in 8 bits, so a value that a script writes reads back rounded as in Chromium.
- `toDataURL` and `toBlob` make a PNG, or a JPEG when the script asks for one. Any other type makes a PNG.
- Text reads back only when the host passes `fontOutlines` to `EpubScriptRunner`, for example the outlines of the canvas that the app draws with. Without it, text reads back blank, but the page still shows it.
- An `<img>` loads its picture as in a browser. Setting `src` makes it pending, and a later task fires `load` with its natural size, or `error` for a file that is missing or does not decode. The images of a chapter load before the `load` event of the window. `decode()` waits for the load. A canvas draws nothing of an image that has not loaded.

**Origin, location and navigation**

- Each book has an origin of its own, which its chapters share: `epub://` and a host made from the unique identifier of the package and the `instanceKey` of the runner.
- Pass the same `instanceKey` each time (for example an install ID that your app keeps) to give the book the same origin each time that it opens. Another reader's copy then gets another origin, as EPUB 3.3 asks. Without a key, each runner makes a random one.
- `self.origin` and `location.origin` give the origin. `location.href` is the origin and the path of the chapter.
- The fragment that the reader went to (through a link, the table of contents or a bookmark) is the fragment of the URL, so `location.hash` gives it. `EpubDocument.fragmentOf` gives it outside the scripts, and `KiteDocView` passes it to the book with `EpubDocument.setFragment`.
- The element that the fragment names matches `:target` from the moment the chapter is parsed.
- A move to another fragment of the chapter fires `popstate` at once and `hashchange` as a task. A link that the reader follows makes such a move, and so does a script that sets `location.hash` or clicks a link.
- Nothing reaches outside the book. There is no `fetch` and no `XMLHttpRequest`.
- A change of `location`, `window.open` and a click of a script on a link go to the listeners of `onNavigate`, as a zip path and its fragment.
- `alert`, `confirm` and `prompt` go to `onConsole` and answer as if the reader dismissed them.

**Web APIs**

| API | Follows | Notes |
| --- | --- | --- |
| `URL`, `URLSearchParams` | WHATWG URL Standard | A URL against the address of the chapter resolves inside the book. `..` stops at the root of the book. Host names use UTS #46 of Unicode 17. |
| `DOMException` | Web IDL | With `name`, `message`, the legacy `code` and constants such as `NOT_FOUND_ERR`. DOM methods throw the name that the DOM Standard gives. |
| `TextEncoder`, `TextDecoder` | Encoding Standard | Every encoding and label of the standard, with `fatal`, `ignoreBOM`, `stream` and `encodeInto` |
| `atob`, `btoa` | HTML Standard | Forgiving base64. Bad input throws an `InvalidCharacterError`. |
| `Blob`, `File`, `FileReader` | File API | `Blob.stream()` gives chunks of 64 KiB. `readAsText` takes the encoding of its argument, then the charset of the blob type, then UTF-8. A byte order mark wins over all three. |
| `FormData` | HTML Standard | A submit fires `submit` and `formdata`, but the book goes nowhere after them. |
| `structuredClone`, `postMessage`, `MessageChannel` | HTML Standard | A clone keeps cycles. A function or a node throws a `DataCloneError`. `postMessage` delivers to a target origin of `*`, `/` or the origin of the book, and drops any other. |
| `AbortController`, `AbortSignal` | DOM Standard | With `abort`, `timeout` and `any` |
| Streams | Streams Standard | Readable, writable and transform streams, both queuing strategies, `TextEncoderStream` and `TextDecoderStream`. A stream in a `transfer` list moves. |
| `URL.createObjectURL` | File API | A `blob:` URL of the book's origin. Images, style sheets, `@import` and fonts load from it. A revoked URL keeps what the chapter already shows. |
| `DOMParser`, `XMLSerializer` | HTML Standard, DOM Parsing and Serialization | HTML for `text/html`, XML for the four XML types. XML that is not well-formed gives a document with one `parsererror` element. The scripts of a parsed document do not run. |
| `localStorage`, `sessionStorage` | HTML Standard | In memory, for the book |

`navigator.epubReadingSystem.hasFeature` answers true for `dom-manipulation`, `layout-changes`, `spine-scripting` and `mouse-events`, false for `touch-events` and `keyboard-events`, and undefined for a feature that it does not know.

A script that calls a method that the DOM does not have fails, and `failures` lists it. The chapter continues as its other scripts leave it.

### Threads of a book

On the JVM, Android and the native targets, `EpubScriptRunner` runs its calls on a thread of its own, as `PdfScriptRunner` does, and opens the engine of each chapter on a thread of its own too. On the web, it runs them on the thread of the page, and a long script pauses as [Threads](#threads) says. Call `prepare()` once before the first chapter opens.

A listener of `onNavigate` or `onTimersChanged` runs on one of these threads while a script waits for it. So the listener must hand its work on to another thread and must not call the runner.

## Targets

`kitepdf-javascript` runs on the targets that KiteJS builds for: JVM, Android, iOS, macOS, Linux, Windows, JS and Wasm. It does not run on tvOS, watchOS or Android native.
