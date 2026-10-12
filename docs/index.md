# KitePDF

A pure-Kotlin document engine for Kotlin Multiplatform. You can read, create, edit and render PDF files, read and render EPUB 2 and 3 books (audio and video included), and run the JavaScript of both formats, all from `commonMain`. KitePDF can also read and render CBZ comics, SVG images, and XPS or OpenXPS documents.

```kotlin
val doc = PdfDocument.open(bytes)

// Read
val text = doc.pages[0].extractText()

// Edit
val redacted = doc.edit().apply {
    redactRegion(doc.pages[0], KiteRectangle(72.0, 700.0, 320.0, 720.0))
}.saveRewritten()

// Create
val fresh = PdfBuilder()
    .page { text(StandardFont.Helvetica, 24.0, 72.0, 720.0, "Hello, world!") }
    .build()
```

<div class="grid cards" markdown>

- :material-rocket-launch: **New here?** [Get started](getting-started.md)
- :material-book-open-variant: **Reading EPUBs?** [Reflowable EPUB 2 and 3](epub.md)
- :material-code-braces: **Looking for a signature?** [API reference](https://yuroyami.github.io/KitePDF/api/)

</div>

## Why KitePDF

KitePDF is a single engine written in Kotlin from the parser up. It doesn't rely on any platform PDF library, web view, JNI or native binary, which is why it behaves identically across Android, iOS, the desktop JVM, macOS, Linux, Windows and the web.

That means the logic lives in `commonMain` instead of being handed off to a different engine on each platform through expect/actual, which in many cases leads to inconsistent behavior.

| Format | View in Compose | Render to image | Edit | Create | JavaScript |
| --- | :---: | :---: | :---: | :---: | :---: |
| **PDF** | ✓ | ✓ | ✓ | ✓ | ✓ |
| **EPUB 2 and 3** | ✓ | ✓ | Not yet | Not yet | ✓ |
| **CBZ** | ✓ | ✓ | Not yet | Not yet | n/a |
| **SVG** | ✓ | ✓ | Not yet | Not yet | Not yet |
| **XPS and OpenXPS** | ✓ | ✓ | Not yet | Not yet | n/a |

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kitepdf:0.13.0")
}
```

The `kitepdf` artifact holds every format. [Getting started](getting-started.md) lists the optional artifacts (the Compose viewer, the headless renderers, JavaScript, media playback and network loading) with what each one needs.

!!! note "Not using Kotlin Multiplatform?"
    The same artifacts work in a plain Android or JVM project. Add them to your normal `dependencies { }` block.

## Guides

| Guide | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | Install, open a first document and show it |
| [Loading a document](loading.md) | Every source KitePDF can open, and how it detects the format |
| [Compose viewer](compose-viewer.md) | `KiteDocView`: layouts, zoom, render modes, navigation, export |
| [Reading PDFs](reading.md) | Text, metadata, outlines, annotations, forms, encryption |
| [Reading EPUBs](epub.md) | Pagination, reader settings, search, typography |
| [EPUB audio and video](media.md) | Playback of `<audio>`, `<video>` and media overlays |
| [XPS and OpenXPS](xps.md) | Fixed-layout XPS documents |
| [Creating PDFs](writing.md) | The builder DSL, fonts and images |
| [Editing and redaction](editing.md) | Forms, stamps, page changes, redaction, saving |
| [Headless rendering](rendering.md) | A page to PNG, `Bitmap` or `NSData` without a UI |
| [JavaScript](javascript.md) | The scripts of PDF forms and EPUB books |
| [Recipes](recipes.md) | Short patterns for common tasks |
| [Platform support](platforms.md) | What runs where |
