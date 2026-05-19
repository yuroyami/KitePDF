# KitePDF

![status](https://img.shields.io/badge/status-experimental-orange)
![kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)
![multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)
![compose](https://img.shields.io/badge/Compose%20Multiplatform-1.11-4285F4)
![dependencies](https://img.shields.io/badge/runtime%20deps-kotlin--stdlib%20only-2EA44F)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)

> A **pure-Kotlin** Multiplatform PDF library — no external runtime dependencies, no `java.util.zip`, no native code.

KitePDF reads PDF documents using nothing but `kotlin-stdlib`. It runs on Android, iOS, JVM desktop, JS (browser + Node), and Kotlin/Native, with a Compose Multiplatform renderer as the recommended UI binding.

## Why

Every Kotlin Multiplatform team that wants to handle PDFs hits the same wall: the good libraries (PDFBox, iText, MuPDF) are JVM/native-only and don't compose with iOS or JS targets. The KMP-friendly wrappers all delegate to platform PDF kits (Android `PdfRenderer`, iOS `PDFKit`, browser PDF.js), and you end up writing four parallel implementations and fighting four different sets of bugs.

KitePDF takes the other path: implement the PDF spec itself in Kotlin, share **one** codebase across every target.

## Status — honest scope

This is **v0.0.1**. What's working *today* (all of it lands in commonMain, all of it is exercised by unit tests):

| Area                          | Status        | Notes                                                                 |
| ----------------------------- | ------------- | --------------------------------------------------------------------- |
| RFC 1951 DEFLATE inflater     | ✅ Done        | Pure Kotlin, no `java.util.zip` — stored, fixed Huffman, dynamic Huffman, LZ77. |
| RFC 1950 zlib wrapper         | ✅ Done        | With Adler-32 verification.                                           |
| PDF lexer                     | ✅ Done        | Literals, hex strings, names with `#XX` escapes, comments, signs.     |
| PDF object parser             | ✅ Done        | Numbers, strings, names, arrays, dicts, streams, indirect refs.       |
| Classic xref + trailer        | ✅ Done        | Multi-subsection xref tables, free/in-use entries.                    |
| Cross-reference streams (1.5+)| ✅ Done        | `/Type /XRef`, fields `[W]`, optional `/Index`.                       |
| FlateDecode + predictors      | ✅ Done        | TIFF predictor 2, all PNG predictors (None/Sub/Up/Average/Paeth/Optimum). |
| ASCIIHex / ASCII85 / RunLength| ✅ Done        | Including `~>` and `z` shortcut in ASCII85.                           |
| Object streams (`/ObjStm`)    | ✅ Done        | Compressed object indirection, decoded lazily and cached.             |
| Page tree                     | ✅ Done        | Recursive `/Pages` walk with inherited `/MediaBox` / `/Resources`.    |
| Naive text extraction         | ✅ Done        | `Tj` / `TJ` / `'` / `"` operators, ASCII + UTF-16BE.                  |
| Content-stream parser         | ✅ Done        | Operand stack + operator dispatch; inline-image skip.                 |
| Renderer interface (`PdfCanvas`)| 🟡 Architecture | Operator → canvas dispatch wired; needs real backends.              |
| Compose Multiplatform binding | 🟡 Stub        | Architecture in place; full `Canvas` adapter is Session 2.          |
| Encryption (`/Crypt`)         | ❌ Not yet     | RC4 + AES-128/256, password & owner keys.                             |
| Font glyph rendering          | ❌ Not yet     | Standard 14 metrics, TrueType, CIDFont, ToUnicode CMaps.              |
| Embedded images               | ❌ Not yet     | DCTDecode (JPEG), CCITTFaxDecode, JBIG2.                              |
| Linearization                 | ❌ Not yet     | Progressive load for huge files.                                      |
| LZWDecode filter              | ❌ Not yet     | Legacy filter; rare in modern PDFs.                                   |
| Forms (`/AcroForm`)           | ❌ Not yet     | Field reading, value setting, flattening.                             |
| Annotations                   | ❌ Not yet     | Highlight, link, ink.                                                 |
| `/Prev` xref chaining         | ❌ Not yet     | Incremental update support.                                           |

Everything in the **❌ Not yet** column is on the roadmap, in roughly that priority order. The full PDF spec (ISO 32000-1, ~750 pages) is enormous; KitePDF is intentionally building outward from "open and read text" rather than racing to feature-parity with PDFBox.

## Install

Not yet published — clone and consume locally:

```kotlin
// settings.gradle.kts
includeBuild("/path/to/KitePDF")
```

```kotlin
// build.gradle.kts
commonMain.dependencies {
    implementation("com.yuroyami.kitepdf:kitepdf:0.0.1-SNAPSHOT")
}
```

## Usage

```kotlin
import com.yuroyami.kitepdf.KitePDF

val doc = KitePDF.open(pdfBytes)

println("PDF ${doc.version} — ${doc.pageCount} page(s)")

for ((i, page) in doc.pages.withIndex()) {
    println("Page ${i + 1}: ${page.width} × ${page.height} pt")
    println(page.extractText())
}
```

## Architecture

```
+------------------------------------------------------------------+
|                       Public API (commonMain)                    |
|       KitePDF.open() → PdfDocument → PdfPage.extractText()       |
+------------------------------------------------------------------+
                                |
        +-----------------------+----------------------+
        |                       |                      |
+-------v--------+    +---------v---------+   +--------v---------+
| parser         |    | content           |   | render           |
| - Lexer        |    | - ContentStream   |   | - PdfCanvas      |
| - Parser       |    | - Operation       |   | - PageRenderer   |
| - XrefParser   |    | - text/Extractor  |   |                  |
| - PdfObject    |    |                   |   |                  |
+-------+--------+    +---------+---------+   +--------+---------+
        |                       |                      |
        +-----------------------+----------------------+
                                |
                  +-------------v--------------+
                  | filters                    |
                  | - FlateDecode + predictors |
                  | - ASCIIHex / ASCII85 / RLE |
                  +-------------+--------------+
                                |
                  +-------------v--------------+
                  | compression                |
                  | - Inflate (RFC 1951)       |
                  | - Zlib (RFC 1950)          |
                  +-------------+--------------+
                                |
                  +-------------v--------------+
                  | core                       |
                  | - ByteReader               |
                  | - ByteArrayBuilder         |
                  +----------------------------+
```

The `:kitepdf` module is intentionally **Compose-free**. UI bindings (Compose Multiplatform, Android `View`, SwiftUI, plain Skia) live in *separate* modules so a CLI or server consumer doesn't pull in Compose. The `:sample` app shows a Compose Multiplatform consumer.

## Why no dependencies?

Every kotlinx library you pull in is *another* place to debug across all six targets. KitePDF aims for the lowest possible churn surface: `kotlin-stdlib` is shipped with the toolchain, version-locked to whatever your project already uses. No Okio, no kotlinx-io, no coroutines, no platform-specific deflate.

The cost is real work — a hand-written DEFLATE inflater is ~270 lines we own forever — but the payoff is that adding KitePDF to your project literally cannot increase your dependency tree.

## Modules

| Module       | Purpose                                                          |
| ------------ | ---------------------------------------------------------------- |
| `:kitepdf`   | The PDF library. Pure Kotlin. Common + Android + iOS + JS + JVM. |
| `:sample`    | Compose Multiplatform app demoing `open()` + `extractText()`.    |

## Running the sample

```bash
./gradlew :sample:run
```

The desktop sample opens two embedded mini-PDFs (built programmatically) and prints their parsed metadata + extracted text.

## Testing

```bash
./gradlew :kitepdf:jvmTest          # JVM
./gradlew :kitepdf:allTests         # All targets that have a runner
```

Tests cover the inflater (with real-world zlib byte fixtures), the lexer, the parser (including the tricky `N G R` lookahead), and an end-to-end document parse + text extraction.

## Roadmap

**Session 2** — text rendering you can put in front of a user:
- Standard 14 font metrics + glyph names
- `/ToUnicode` CMap parsing so non-Latin text decodes correctly
- Compose Multiplatform `PdfPageView` composable with real glyph painting
- `/Prev` incremental update chaining
- Indirect `/Length` recovery for streams

**Session 3** — graphics + images:
- TrueType font parser (the big one)
- JPEG decoder (`DCTDecode`) — sufficient for embedded photos
- Image XObjects + form XObjects (`Do` operator)
- Clipping (`W`, `W*`) and color spaces (`cs`, `CS`, `scn`)

**Session N** — the rest of the iceberg:
- AES-256 encryption / password handling
- AcroForm reading + flattening
- Annotations
- Linearization
- LZW / JBIG2 / CCITTFax filters

## Contributing

PRs welcome — especially for the items marked ❌ above. Two house rules:

1. **No new runtime dependencies.** Everything must run with only `kotlin-stdlib`. (Test dependencies are fine.)
2. **Be honest in this README.** If you add something that's partial, mark it 🟡 and explain what's not yet there.

## License

Apache 2.0.

---

*KitePDF is built and maintained by [yuroyami](https://github.com/yuroyami). It started life as "could we do this without leaning on PDFBox?"; the answer turned out to be yes, but slowly.*
