# Loading a document

Every source ends as a `ByteArray` that a format reader parses, so a file, a stream and a download all sit in memory as one array before parsing starts. Opening a big book is still quick, because KitePDF reads only the container, the package file and the table of contents at first, and lays out each chapter when the reader gets to it (see [Opening at a saved position](compose-viewer.md#opening-at-a-saved-position)).

## When you know the format

Call the reader of that format directly. You don't need the `kitepdf` umbrella artifact for this.

```kotlin
// Each one throws on a file it cannot read
val pdf = PdfDocument.open(bytes)
val book = EpubDocument.open(bytes)
```

## When you don't

`KiteDoc` detects the format from the bytes and gives you a `KiteDocument`, which is what the Compose viewer and the shared search, selection and outline APIs take.

```kotlin
import io.github.yuroyami.kitepdf.document.KiteDoc

val doc = KiteDoc.open(bytes)
KiteDocView(doc, Modifier.fillMaxSize())
```

You can also check the format without opening anything:

```kotlin
when (KiteDoc.formatOf(bytes)) {
    KiteDocFormat.Pdf -> { }
    KiteDocFormat.Epub -> { }
    KiteDocFormat.Cbz -> { }
    KiteDocFormat.Svg -> { }
    KiteDocFormat.Xps -> { }
    null -> { }
}
```

| Format | KitePDF detects it when |
| --- | --- |
| PDF | the first kilobyte holds a `%PDF-` marker (bytes before the marker are allowed) |
| EPUB | the file is a ZIP whose first entry is the OCF `mimetype`, or a ZIP that holds `META-INF/container.xml` |
| XPS and OpenXPS | the file is a ZIP whose package relationships lead to a fixed document sequence |
| CBZ | the file is any other ZIP whose entries are all images (hidden files and `ComicInfo.xml` do not count) |
| SVG | the first 512 bytes hold an `<svg>` element |

KitePDF checks a ZIP for EPUB, then XPS, then CBZ. It checks any other file for PDF, then SVG. When no format matches, `KiteDoc.open` still tries the bytes as a damaged PDF, because the PDF reader can rebuild a file that lost its header. [XPS and OpenXPS](xps.md) explains how an XPS package is found.

!!! note "Which artifact"
    `KiteDoc` is in the `kitepdf` umbrella artifact, because only that artifact sees every format. With `kitepdf-pdf`, `kitepdf-epub`, `kitepdf-cbz`, `kitepdf-svg` or `kitepdf-xps` alone, you open the document through the reader of that format.

Each format takes its own options and ignores the options of the others:

```kotlin
// The password of an encrypted PDF
KiteDoc.open(bytes, password = "secret")

// The layout settings of an EPUB
KiteDoc.open(bytes, epubSettings = EpubSettings(fontSize = 15.0))
```

## Every source

| Source | Call | Platforms |
| --- | --- | --- |
| Byte array | `KiteDoc.open(bytes)` | all |
| Base64 text or a `data:` URI | `KiteDoc.openBase64(text)` | all |
| File path | `KiteDoc.openFile(path)` | JVM, Android, Apple, Linux, Windows, Android Native |
| `java.io.File` | `KiteDoc.open(file)` | JVM, Android |
| `InputStream` | `KiteDoc.open(stream)` | JVM, Android |
| Android content `Uri` | `KiteDoc.open(context, uri)` | Android |
| `NSData` | `KiteDoc.open(data)` | Apple |
| `NSURL` | `KiteDoc.open(url)` | Apple |
| Remote URL | `KiteDoc.openUrl(url, client)` | with `kitepdf-net` |

A browser has no file system, so JS and Wasm have no file path call. Read the bytes with the browser's own API (a `File` from an `<input>`, `fetch` or OPFS) and pass them to `KiteDoc.open(bytes)`.

### Base64

`openBase64` takes a bare payload or a whole `data:` URI, in the standard or the URL-safe alphabet, with or without padding, and it ignores line breaks. That covers what a JSON API, an `<embed>` tag or a clipboard paste hands you.

```kotlin
KiteDoc.openBase64("JVBERi0xLjcKJc...")
KiteDoc.openBase64("data:application/pdf;base64,JVBERi0xLjcKJc...")
```

### Android file picker

`ACTION_OPEN_DOCUMENT` gives back a `content://` Uri, which has no file path, so KitePDF reads it through the content resolver:

```kotlin
val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri ?: return@registerForActivityResult
    val doc = KiteDoc.open(this, uri)
}
pick.launch(arrayOf("application/pdf", "application/epub+zip"))
```

!!! tip
    This call reads the whole document into memory. Call it off the main thread for a large file.

### Remote URL

Network loading is in its own artifact, so Ktor enters your build only when you add `kitepdf-net`.

!!! warning
    `kitepdf-net` needs a Ktor client engine. Without one, the build fails.

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-net:0.13.0")

    // Or the OkHttp, Darwin or Js engine
    implementation("io.ktor:ktor-client-cio:3.6.0")
}
```

```kotlin
import io.github.yuroyami.kitepdf.net.openUrl

// Your engine, with your own configuration
val client = HttpClient()
val doc = KiteDoc.openUrl("https://example.com/book.epub", client)
```

KitePDF keeps the whole download in memory, so it stops a download at 128 MiB. You can set a lower or a higher limit with `maxBytes`, which KitePDF checks against both the `Content-Length` header and the bytes it receives:

```kotlin
val doc = KiteDoc.openUrl(url, client, maxBytes = 32 * 1024 * 1024)
```

The client stays yours (KitePDF neither creates nor closes it), which keeps timeouts, retries, authentication and logging under your control. Headers for one request go in the trailing block:

```kotlin
KiteDoc.openUrl(url, client) { header("Authorization", "Bearer $token") }
```

When you want the raw bytes (to cache or hash them first), `KiteDoc.downloadBytes(url, client)` gives you the body with the same limit. An error message never shows the user name, password, query or fragment of the URL, and cancelling the coroutine also stops the download.

A book can also name images and fonts by URL. `EpubResourceFetcher(client)` fetches them on the same client, over `https` only and up to 16 MiB each, once you put it in the book's settings (see [Resources on the web](epub.md#resources-on-the-web)):

```kotlin
val settings = EpubSettings(resourceFetcher = EpubResourceFetcher(client))
val doc = KiteDoc.openUrl("https://example.com/book.epub", client, epubSettings = settings)
```

Ktor does not ship for Android Native or WASI, so `kitepdf-net` does not either.

## Coming back to the same place

A `KiteBookmark` keeps the reading position, even when the font size, the page size or the margins change in between. [Opening at a saved position](compose-viewer.md#opening-at-a-saved-position) shows how the viewer opens a book at a bookmark. [Changing settings without losing the place](epub.md#changing-settings-without-losing-the-place) shows how to lay out a book again at a new size, without reading the file again.
