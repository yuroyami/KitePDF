# JavaScript

PDF files can carry JavaScript: document-level scripts that run when the file opens, and actions that run when a reader taps a link or a button. KitePDF reads those scripts but runs none of them by itself. The `kitepdf-javascript` artifact runs them on [KiteJS](https://github.com/yuroyami/KiteJS), a JavaScript engine written in Kotlin.

```kotlin
commonMain.dependencies {
    // Ships from the release after 0.9.0, at the same version as the other artifacts.
    implementation("io.github.yuroyami:kitepdf-javascript:<version>")
}
```

## Run a document's scripts

```kotlin
val doc = PdfDocument.open(bytes)
PdfScriptRunner(doc, onAlert = { message -> showDialog(message) }).use { runner ->
    val failures = runner.runDocumentScripts() // the scripts that threw; the rest still ran
}
```

`PdfScriptRunner.run(action)` runs one `PdfAction.JavaScript`, such as the action of a link a reader tapped.

## What scripts can reach

This is a first step. Scripts see:

- `app.viewerType`, which is `"KitePDF"`
- `app.alert(message)`, which calls your `onAlert`
- `console.println(text)`, which calls your `onConsole`

The form, field and document objects that Acrobat scripts use are not there yet. A script that reaches for them fails and is reported, instead of running with wrong results. The [plan](https://github.com/yuroyami/KitePDF/issues?q=label%3Aplan%3Ajavascript) lists what comes next.

## Limits

Document scripts are untrusted. Each call runs under an instruction budget, 10 million steps by default, so a script that never returns stops with a `KiteScriptException` instead of hanging your app. The built-in objects are read-only, and a script reaches nothing outside the engine except what the runner defines.

## Other engines

The runner talks to the `KiteScriptEngine` interface in `kitepdf-core`. `KiteJsScriptEngine` is the KiteJS implementation. Pass your own engine to `PdfScriptRunner` to use another one.

## Targets

JVM, Android, iOS, macOS, Linux, Windows, JavaScript and WebAssembly: the targets KiteJS builds for. tvOS, watchOS and Android native are not covered.
