# JavaScript

PDF files carry JavaScript: a form that computes a total, a field that formats what is typed, a
button that hides another field, a link that runs a script. The core library reads those scripts
and runs none of them. The `kitepdf-javascript` artifact runs them, on
[KiteJS](https://github.com/yuroyami/KiteJS), a JavaScript engine written in Kotlin.

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf-javascript:0.10.0")
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
asked first, so a viewer can offer to keep waiting, the way a browser does. The built-in objects
are read-only, and a script reaches nothing outside the engine except what the runner defines.

Denying scripts does not stop a reader filling the form: values still go into the form state, and
only the scripts are silent.

## Threads

An engine belongs to one thread, so a runner does too: make it and use it from the same thread.
The engine itself is opened by the first script that runs, not when the runner is made, so a host
may construct a runner on one thread and hand it to the thread that will use it. The Compose
viewer does exactly that: it keeps a thread for scripts and posts every call to it.

`PdfFormState` is the exception, and deliberately so: it is written by the scripts and read by
whatever draws, so it is safe from two threads.

## Other engines

The runner talks to the `KiteScriptEngine` interface in `kitepdf-core`. `KiteJsScriptEngine` is the KiteJS implementation. Pass your own engine to `PdfScriptRunner` to use another one.

## Targets

JVM, Android, iOS, macOS, Linux, Windows, JavaScript and WebAssembly: the targets KiteJS builds for. tvOS, watchOS and Android native are not covered.
