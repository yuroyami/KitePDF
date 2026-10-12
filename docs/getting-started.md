# Getting started

This page takes you from an empty project to a document on screen. You add KitePDF, open a file, show it in Compose, read its text and render a page to an image.

## 1. Add the dependency

=== "Kotlin Multiplatform"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.yuroyami:kitepdf:0.13.0")
            }
        }
    }
    ```

=== "Android or JVM"

    ```kotlin
    dependencies {
        implementation("io.github.yuroyami:kitepdf:0.13.0")
    }
    ```

The `kitepdf` artifact holds every format (PDF, EPUB, CBZ, SVG, XPS and OpenXPS) and depends only on `kotlin-stdlib`, the pure-Kotlin ImageKodec decoders and `kotlinx-coroutines-core`. For one format only, you can add `kitepdf-pdf`, `kitepdf-epub`, `kitepdf-cbz`, `kitepdf-svg` or `kitepdf-xps` instead.

The other artifacts are optional:

| Artifact | Add it to | You also need |
| --- | --- | --- |
| `kitepdf-compose-viewer` | show documents in Compose (`KiteDocView`) | |
| `kitepdf-native-renderer` | render a page to an image on the platform canvas | |
| `kitepdf-skia-renderer` | render a page to an image on Skia | on Android, the repository `maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")`. On the JVM without Compose, the Skia runtime of each system, such as `org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.153.0` |
| `kitepdf-javascript` | run the JavaScript of PDF forms and EPUB books | |
| `kitepdf-media` | play EPUB audio and video in `KiteDocView` | on iOS, the linker flags and privacy manifest entries of [KitePlayer](https://github.com/yuroyami/KitePlayer#install) |
| `kitepdf-net` | open a document from a URL | a Ktor client engine, such as `io.ktor:ktor-client-cio:3.6.0` |

!!! warning
    Without the extra item in the last column, the build, the download or the first render fails. The one exception is a missing privacy manifest entry on iOS, which Apple flags only during app review.

Each optional artifact runs on its own set of targets, which [Platform support](platforms.md) lists.

## 2. Open a document

```kotlin
import io.github.yuroyami.kitepdf.PdfDocument
import java.io.File

val doc = PdfDocument.open(File("sample.pdf").readBytes())

println("${doc.pageCount} pages, PDF ${doc.version}")
```

On Android, you can read the bytes from a raw resource instead:

```kotlin
val bytes = context.resources.openRawResource(R.raw.sample).readBytes()
val doc = PdfDocument.open(bytes)
```

A locked PDF takes its password as the second argument:

```kotlin
val doc = PdfDocument.open(bytes, password = "secret")
```

!!! tip "Other formats"
    `EpubDocument.open(bytes)` opens an EPUB book. When you do not know the format, `KiteDoc.open(bytes)` detects it from the bytes. [Loading a document](loading.md) lists every source KitePDF can open (a file path, a stream, an Android `Uri`, `NSData`, a URL and more).

## 3. Show it in Compose

With `kitepdf-compose-viewer`, one composable can show the whole document as a vertical scroll:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.compose.KiteDocView

@Composable
fun Viewer(doc: PdfDocument) {
    KiteDocView(document = doc, modifier = Modifier.fillMaxSize())
}
```

Pass `page = 0` to show a single page (the index starts at zero) instead of the whole document.

For pages that turn, zoom limits, a dark theme or navigation controls, use the state-based `KiteDocView`:

```kotlin
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.compose.KiteDocLayout
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteNavigationControls
import io.github.yuroyami.kitepdf.compose.KiteZoomSpec
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState

@Composable
fun BookViewer(doc: PdfDocument) {
    val state = rememberKiteDocViewState(doc)

    KiteDocView(
        state = state,
        modifier = Modifier.fillMaxSize(),
        layout = KiteDocLayout.Paged(Orientation.Horizontal),
        zoomSpec = KiteZoomSpec(minZoom = 0.5f, maxZoom = 6f),
        overlay = {
            KiteNavigationControls(it, Modifier.align(Alignment.BottomCenter))
        },
    )
}
```

The [Compose viewer](compose-viewer.md) guide shows every parameter, with the trade-off between the two ways the viewer can draw a page.

## 4. Read the text

```kotlin
val page = doc.pages[0]

// The page as plain text
println(page.extractText())

// Blocks of lines of spans, each with its bounds
for (block in page.structuredText.blocks) {
    for (line in block.lines) {
        println("${line.text} at ${line.bounds}")
    }
}
```

The bounds are what you need to draw a selection or a search highlight. [Reading PDFs](reading.md) covers search, metadata, outlines, annotations and form fields.

## 5. Render a page to an image

With `kitepdf-native-renderer` on the JVM, you can turn a page into a PNG with no UI at all:

```kotlin
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File

// Twice the page size, for print or a high-DPI screen
val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 2.0)
File("page-1.png").writeBytes(png)
```

`encodeToJpeg` writes a JPEG, while `renderToImage` gives you the `BufferedImage` itself. [Headless rendering](rendering.md) shows the same job on Android, Apple platforms, the browser and Skia.

## Where to next

- [Compose viewer](compose-viewer.md): layouts, zoom, render modes, navigation and thumbnails
- [Creating PDFs](writing.md): the builder DSL, fonts and images
- [Editing and redaction](editing.md): forms, stamps, page changes and redaction
- [Recipes](recipes.md): short patterns for common tasks
