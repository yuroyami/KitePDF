# Module kitepdf-javascript

Runs the JavaScript of PDF forms and EPUB books on the QuickJS engine of KiteJS.

- `PdfScriptRunner` runs the scripts of a PDF document: its form actions, calculations, formats and validations. `PdfScriptPolicy` sets its limits.
- `EpubScriptRunner` runs the scripts of an EPUB chapter against the DOM of `kitepdf-epub`. `EpubScriptPolicy` sets its limits.
- `KiteJsScriptEngine` wraps a KiteJS engine. A runner opens one itself, unless you pass an engine of your own.

See [JavaScript](https://yuroyami.github.io/KitePDF/javascript/).
