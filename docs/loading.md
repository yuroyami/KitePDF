# Loading a document

Every source ends the same way: a `ByteArray` handed to a handler. Bytes are read whole, so a file, a stream and a download all become one array in memory before parsing starts. Everything on this page is a thin adapter around that.

Reading the bytes is not what makes a big book slow to open; parsing and laying out its chapters is, and both of those are incremental. `KiteDoc.open` on a 9.9 MB EPUB takes well under a millisecond: it reads the container, the OPF and the table of contents and stops. See [Opening at a saved position](compose-viewer.md#opening-at-a-saved-position).

## When you know the format

Call the handler directly. This has always been the shortest route and it does not need the umbrella artifact.

```kotlin
val pdf  = PdfDocument.open(bytes)                // throws on a bad file
val book = EpubDocument.open(bytes)               // same, with EpubFormatException

val maybePdf  = PdfDocument.openOrNull(bytes)     // null instead of a throw
val maybeBook = EpubDocument.openOrNull(bytes)
```

## When you don't

`KiteDoc` reads the format out of the bytes and gives you a `KiteDocument`, which is what the Compose viewer and the shared search, selection and outline APIs take.

```kotlin
import io.github.yuroyami.kitepdf.document.KiteDoc

val doc = KiteDoc.open(bytes)          // whichever of the four it is
KiteDocView(doc, Modifier.fillMaxSize())
```

Check first without opening anything:

```kotlin
when (KiteDoc.formatOf(bytes)) {
    KiteDocFormat.Pdf  -> /* ... */
    KiteDocFormat.Epub -> /* ... */
    KiteDocFormat.Cbz  -> /* a comic archive */
    KiteDocFormat.Svg  -> /* one vector page */
    null               -> /* none of them */
}
```

`formatOf` reads the header for PDF, EPUB and SVG, and the ZIP central directory for CBZ, so it is cheap enough to run over a folder listing.

| Format | What it recognises |
| --- | --- |
| PDF | a `%PDF-` marker in the first kilobyte, leading junk allowed |
| EPUB | a ZIP whose first entry is the OCF `mimetype`, or that carries `META-INF/container.xml` |
| CBZ | any other ZIP whose real entries are all images |
| SVG | an `<svg>` element in the first half kilobyte, checked last |

!!! note "Which artifact"
    `KiteDoc` lives in `io.github.yuroyami:kitepdf`, the umbrella, because it is the only artifact that sees every handler. Depending on `kitepdf-pdf`, `kitepdf-epub`, `kitepdf-cbz` or `kitepdf-svg` alone still gets you that handler's own entry points.

Formats take their own extras, and each ignores the others':

```kotlin
KiteDoc.open(bytes, password = "secret")                       // PDF encryption
KiteDoc.open(bytes, epubSettings = EpubSettings(fontSize = 15.0))
```

## Every source

| Source | Call | Available on |
| --- | --- | --- |
| Byte array | `KiteDoc.open(bytes)` | everywhere |
| Base64 or `data:` URI | `KiteDoc.openBase64(text)` | everywhere |
| File path | `KiteDoc.openFile(path)` | JVM, Android, Apple, Linux, Windows, Android NDK |
| `java.io.File` | `KiteDoc.open(file)` | JVM, Android |
| `InputStream` | `KiteDoc.open(stream)` | JVM, Android |
| Android content Uri | `KiteDoc.open(context, uri)` | Android |
| `NSData` | `KiteDoc.open(data)` | Apple |
| `NSURL` | `KiteDoc.open(url)` | Apple |
| Remote URL | `KiteDoc.openUrl(url, client)` | `kitepdf-net` |

Every one of them has an `...OrNull` twin where a throw is the wrong shape for your call site.

Not available: file paths on JS and Wasm, because browsers have no filesystem. Read the bytes with the platform's own API (a `File` from an `<input>`, `fetch`, OPFS) and use `KiteDoc.open(bytes)`.

### Base64

Takes a bare payload or a whole `;base64` data URI, standard or URL-safe alphabet, padded or not, and ignores whitespace. Malformed padding and truncated/non-canonical tails are rejected instead of being silently decoded. That covers a JSON API response, an `<embed>` attribute and a clipboard paste.

```kotlin
KiteDoc.openBase64("JVBERi0xLjcKJc...")
KiteDoc.openBase64("data:application/pdf;base64,JVBERi0xLjcKJc...")
```

### Android file picker

`ACTION_OPEN_DOCUMENT` hands back a `content://` Uri, which has no file path. Read it through the content resolver:

```kotlin
val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri ?: return@registerForActivityResult
    val doc = KiteDoc.open(this, uri)
}
pick.launch(arrayOf("application/pdf", "application/epub+zip"))
```

It reads the whole document into memory, so keep it off the main thread for anything large.

### Remote URL

Networking lives in a separate artifact. The engine depends on `kotlin-stdlib` and KiteImage only, and `kitepdf-net` is the one place Ktor enters the build, so you pay for it only if you use it.

```kotlin
dependencies {
    implementation("io.github.yuroyami:kitepdf-net:0.7.0")
    implementation("io.ktor:ktor-client-cio:3.5.2")   // or OkHttp, Darwin, Js
}
```

```kotlin
import io.github.yuroyami.kitepdf.net.openUrl

val client = HttpClient()                    // your engine, your config
val doc = KiteDoc.openUrl("https://example.com/book.epub", client)
```

Downloads are streamed and capped at 128 MiB by default, because KitePDF holds
the downloaded bytes and document model in memory. Set a smaller application
limit, or deliberately raise it, through the overload that takes `maxBytes`:

```kotlin
val doc = KiteDoc.openUrl(url, client, maxBytes = 32 * 1024 * 1024)
val bytes = KiteDoc.downloadBytes(url, client, maxBytes = 32 * 1024 * 1024)
```

The limit is enforced against both `Content-Length` and the bytes actually
streamed. `openUrlOrNull` still propagates coroutine cancellation, so cancelling
a screen or request does not leave the download running.

The client is yours: KitePDF neither creates nor closes it, so timeouts, retries, auth and logging stay under your control. Per-request headers go in the trailing block. Failure messages redact URL user-info, query strings and fragments:

```kotlin
KiteDoc.openUrl(url, client) { header("Authorization", "Bearer $token") }
```

`downloadBytes(url, client)` gets you the raw body when you want to cache or hash it before deciding what to do.

Ktor does not ship for `androidNative*` or `wasmWasi`, so neither does `kitepdf-net`. The engine artifacts still do.

## Remembering where the reader was

`KiteDoc.open` gives you the document. Where the reader left off is a
`KiteBookmark`, which survives a font size or page size change:

```kotlin
val state = rememberKiteDocViewState(doc, savedBookmark)
val savedBookmark = state.currentBookmark()      // save this
```

Only the bookmark's chapter is parsed and laid out before the page appears. See
**[Reading EPUBs](epub.md)** for the detail.

## Re-flowing an EPUB after opening

Page size and font size are layout inputs, not parse inputs. Change them without re-reading the file:

```kotlin
val bigger = book.withFontSize(16.0)
val resized = book.withPageSize(600.0, 900.0)
```

The parse (unzip, OPF, CSS, fonts, TOC, and whichever chapters have been read
already) is shared, so this only re-paginates.
