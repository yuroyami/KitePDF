# KitePDF

![status](https://img.shields.io/badge/status-experimental-orange)
![kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)
![multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)
![compose](https://img.shields.io/badge/Compose%20Multiplatform-1.11-4285F4)
![dependencies](https://img.shields.io/badge/core%20deps-kotlin--stdlib%20only-2EA44F)
![tests](https://img.shields.io/badge/tests-92%20passing-2EA44F)
![license](https://img.shields.io/badge/license-Apache--2.0-blue)

> A **pure-Kotlin** Multiplatform PDF library — parses, decrypts, renders real glyphs, draws Compose UI. No `java.util.zip`, no FreeType, no native crypto, no PDFBox.

KitePDF parses, **decrypts**, **renders TrueType + CFF outlines**, decodes images via platform image loaders, and paints all of it into Compose Multiplatform. Core stays at `kotlin-stdlib` only.

## Why

Every Kotlin Multiplatform team that wants PDFs hits the same wall: the good libraries (PDFBox, iText, MuPDF) are JVM/native-only and don't compose with iOS or JS. The KMP-friendly wrappers all delegate to platform PDF kits (Android `PdfRenderer`, iOS `PDFKit`, browser PDF.js), and you end up shipping four parallel implementations.

KitePDF takes the other path: implement the PDF spec itself in Kotlin, share **one** codebase across every target. We use MuPDF as architectural reference (the cleanest open-source PDF engine) but every line is our own.

## Status — v0.0.6

| Area | Status | Notes |
| --- | --- | --- |
| **Parsing** | | |
| RFC 1951 DEFLATE inflater | ✅ | Pure Kotlin, no `java.util.zip` |
| RFC 1950 zlib wrapper | ✅ | With Adler-32 verification |
| PDF lexer, parser, classic xref + xref-streams + `/Prev` | ✅ | |
| Indirect `/Length`, ObjStm | ✅ | |
| **Filters** | | |
| FlateDecode + TIFF + PNG predictors | ✅ | |
| ASCIIHex / ASCII85 / RunLength | ✅ | |
| **LZWDecode** | ✅ | **NEW v0.0.4** — variable-width, MSB-packed |
| CCITTFax / JBIG2 | ❌ | Roadmap |
| **Colour spaces** | | |
| DeviceGray / DeviceRGB | ✅ | |
| **DeviceCMYK** (`k` / `K` operators + colourspace family) | ✅ | **NEW** — naïve subtractive conversion |
| **Indexed (palette)** | ✅ | **NEW** — for indexed images / shadings |
| CalGray / CalRGB / Lab / ICCBased | 🟡 | Fall back to a sensible device family |
| DeviceN / Separation / Pattern | ❌ | |
| **Encryption (NEW v0.0.4)** | | |
| Standard Security Handler V1 / V2 (RC4) | ✅ | **NEW** — round-trip tested |
| Standard Security Handler V4 (AES-128) | ✅ | **NEW** |
| Standard Security Handler V5 / V6 (AES-256) | ✅ | **NEW** — SHA-256 key derivation |
| Pure-Kotlin RC4 / MD5 / SHA-256 / AES-128 / AES-256 | ✅ | **NEW** — verified against NIST + RFC vectors |
| Per-object key derivation + `/Crypt` filter routing | ✅ | **NEW** |
| Public-key security | ❌ | |
| **Fonts** | | |
| Standard 14 widths (URW-derived) | ✅ | |
| Adobe Glyph List → Unicode (4 200 entries) | ✅ | |
| `/Encoding` (WinAnsi / MacRoman / Standard) + `/Differences` | ✅ | |
| `/ToUnicode` CMap | ✅ | |
| TrueType outline parser (`/FontFile2`) | ✅ | head / maxp / hhea / hmtx / cmap (0/4/6/12) / loca / glyf simple + composite |
| CFF / OpenType-CFF (`/FontFile3`) | ✅ | Type 1C + CIDFontType0C; full Type 2 charstring interpreter |
| **Type 1 (`/FontFile`) outlines** | ✅ | **NEW v0.0.5** — PostScript header scan + eexec decrypt + Type 1 charstring interpreter |
| **Type 0 composite fonts** (`/Type0` + CIDFontType0/2) | ✅ | **NEW v0.0.5** — Identity-H/V CMap, `/CIDToGIDMap` (Identity + stream), `/W` widths (both forms), per-CID glyph walk |
| Type 3 (synthetic) | ❌ | Rare; each glyph is its own content stream |
| **Rendering** | | |
| `PdfCanvas` device interface + GraphicsStack + CTM | ✅ | |
| Path + paint operators + colour | ✅ | |
| Full text state machine | ✅ | |
| Per-glyph outline rendering (TTF + CFF) | ✅ | |
| Clipping (`W` / `W*`) | ✅ | |
| **Form XObject recursion (`Do` for `/Subtype /Form`)** | ✅ | **NEW v0.0.4** — child resources + matrix concat |
| **Image XObject + JPEG decoding** | ✅ | **NEW** — `expect/actual` decoder per platform; JVM uses Skia, Android uses BitmapFactory, iOS uses Skia; JS draws placeholder |
| CCITTFax / JBIG2 / JPEG 2000 images | ❌ | Placeholder for now |
| **Annotations (NEW v0.0.4)** | ✅ | |
| `PdfAnnotation` model + 24 subtypes parsed | ✅ | **NEW** |
| Link / Highlight / Underline / StrikeOut rendered | ✅ | **NEW** — fallback drawing when `/AP` is absent |
| Custom appearance streams (`/AP /N`) rendered | ✅ | **NEW** — via Form XObject path |
| Action / URI / GoTo parsing | 🟡 | URI link parsing in; GoTo destinations are Session 5 |
| **Compose Multiplatform binding** | | |
| `@Composable PdfPageView(page)` | ✅ | |
| ComposeCanvas with embedded glyph rendering | ✅ | |
| Clipping via `clipPath` | ✅ | |
| Image bitmap painting via `ImageDecoder` expect/actual | ✅ | **NEW** |
| **Transparency (NEW v0.0.6)** | | |
| Extended Graphics State (`gs` operator, `/ExtGState`) | ✅ | **NEW** — `/ca` fill alpha, `/CA` stroke alpha, `/BM` blend mode, `/SMask`, `/LW` |
| All 16 PDF blend modes (Normal/Multiply/Screen/Overlay/Darken/Lighten/ColorDodge/ColorBurn/HardLight/SoftLight/Difference/Exclusion/Hue/Saturation/Color/Luminosity) | ✅ | **NEW** — 1:1 mapping to Compose `BlendMode` |
| Per-object alpha (multiplied through fill/stroke/text/image) | ✅ | **NEW** |
| Transparency groups (Form XObject `/Group /S /Transparency`) | ✅ | **NEW** — Compose `Canvas.saveLayer` / `restore` with paint blend mode + alpha |
| Soft masks (`/SMask`) | 🟡 partial | Parsed and tracked; mask compositing on the backend is a Session-8 deliverable |
| **Roadmap** | | |
| AcroForm reading + field appearance generation | ❌ | |
| XFA forms | ❌ | |
| Digital signatures (parse PKCS#7) | ❌ | |
| Full soft mask compositing (render mask group → DstIn) | ❌ | |
| Tiling + shading patterns | ❌ | |
| Document outlines / bookmarks | ❌ | |
| Linearization (progressive load) | ❌ | |
| Type 1 (`/FontFile`) outlines | ❌ | |
| Xref recovery for malformed files | ❌ | |
| PDF writing | ❌ | |

## Architecture

```
+--------------------------------------------------------------------------+
|                  :sample (Compose Multiplatform demo)                    |
+--------------------------------------------------------------------------+
                                ↓
+--------------------------------------------------------------------------+
|                              :kitepdf-compose                            |
|   ComposeCanvas — paths + glyphs (TTF & CFF) + clipping                  |
|   ImageDecoder (expect/actual) — JVM/Android/iOS use platform decoders   |
|   @Composable PdfPageView(page)                                          |
+--------------------------------------------------------------------------+
                                ↓
+--------------------------------------------------------------------------+
|                            :kitepdf  (pure Kotlin)                       |
|                                                                          |
|   PdfDocument.open(bytes, password?) → pages → renderTo() / extractText  |
|                                                                          |
|   ┌─ render ────────────────────────────────────────────────────────┐    |
|   │  PageRenderer + GraphicsStack + Matrix + PdfPath                │    |
|   │  ColorSpace (Gray / RGB / CMYK / Indexed) + ImageXObject        │    |
|   │  Form XObject recursion + Annotation rendering                  │    |
|   └─────────────────────────────────────────────────────────────────┘    |
|   ┌─ font ──────────────────────────────────────────────────────────┐    |
|   │  PdfFont (Standard 14 widths + AGL + encodings)                 │    |
|   │  TrueTypeFont + TtfCMap + GlyphOutline                          │    |
|   │  CffFont + CharstringInterpreter  ← NEW                          │    |
|   │  ToUnicode CMap                                                 │    |
|   └─────────────────────────────────────────────────────────────────┘    |
|   ┌─ crypto  ← NEW ─────────────────────────────────────────────────┐    |
|   │  MD5 + SHA-256 + RC4 + AES-128 + AES-256 (pure Kotlin)          │    |
|   │  StandardSecurityHandler (V1/V2/V4/V5/V6)                       │    |
|   │  Decryptor (walks objects, decrypts strings + streams)          │    |
|   └─────────────────────────────────────────────────────────────────┘    |
|   ┌─ parser ────────────────────────────────────────────────────────┐    |
|   │  Lexer + Parser + XrefParser + PdfObject hierarchy              │    |
|   └─────────────────────────────────────────────────────────────────┘    |
|   ┌─ filters + compression + core ──────────────────────────────────┐    |
|   │  Flate / ASCIIHex / ASCII85 / RLE / LZW / predictors            │    |
|   │  Inflate (RFC 1951) + Zlib (RFC 1950)                           │    |
|   └─────────────────────────────────────────────────────────────────┘    |
+--------------------------------------------------------------------------+
```

## Usage

### Open an encrypted PDF

```kotlin
import com.yuroyami.kitepdf.KitePDF

val doc = KitePDF.open(pdfBytes, password = "secret".encodeToByteArray())
if (!doc.isAuthenticated) {
    println("Wrong password — document is still readable but content stays encrypted.")
}
println("PDF ${doc.version} — ${doc.pageCount} page(s)")
println(doc.pages[0].extractText())
```

### Inspect annotations

```kotlin
for (annot in doc.pages[0].annotations) {
    when (annot.subtype) {
        PdfAnnotation.Subtype.Link -> println("Link: ${annot.uri} @ ${annot.rect}")
        PdfAnnotation.Subtype.Highlight -> println("Highlight: ${annot.contents}")
        else -> {}
    }
}
```

### Compose rendering

```kotlin
@Composable
fun MyScreen(pdfBytes: ByteArray) {
    val doc = remember(pdfBytes) { KitePDF.open(pdfBytes) }
    PdfPageView(page = doc.pages[0], modifier = Modifier.fillMaxWidth())
}
```

## Tests

```bash
./gradlew :kitepdf:jvmTest         # 74 tests, JVM
./gradlew :kitepdf:allTests        # All targets that have a runner
```

Highlights:
- **Encryption integration**: builds an encrypted PDF using our own crypto, opens it with KitePDF, verifies decrypted text.
- **Crypto primitives**: NIST + RFC test vectors for MD5, SHA-256, RC4, AES-128, AES-256.
- **Inflate**: real zlib bytes for stored, fixed Huffman, and dynamic Huffman with LZ77 back-references.
- **Foundation fixes**: `/Prev` chain merging, indirect `/Length` resolution.
- **TrueType**: binary reader, glyph outline → PdfPath conversion (on-curve, off-curve, implied midpoint).
- **Annotations**: link parsing with URI extraction; highlight colour decoding.

## Session 6 highlights — transparency + blend modes

PDF transparency is the spec's compositing model: every paint produces a
`(colour, alpha)` source that's blended onto a `backdrop` via a per-state
blend function. v0.0.6 wires the whole pipeline:

1. **`ExtGState` parsing** — every `/ExtGState /<name>` entry now produces a
   typed [ExtGState] data class with `/ca` fill alpha, `/CA` stroke alpha,
   `/BM` blend mode, `/SMask`, `/LW`.
2. **`gs` operator** — looks up the named ExtGState and merges its non-null
   fields into the current [GraphicsState]; only the entries the dict
   actually sets override (spec semantics, not full replacement).
3. **Alpha + blend mode plumbed through every paint** — `fillPath`,
   `strokePath`, `drawText` and `drawImage` now carry `alpha` + `blendMode`
   params. [PageRenderer] reads them off the live `GraphicsState` and
   passes through; backends paint accordingly.
4. **All 16 PDF blend modes** map 1:1 to Compose `BlendMode` (Multiply,
   Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn, HardLight,
   SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity).
5. **Transparency groups** — Form XObjects with `/Group /S /Transparency`
   open a Compose `Canvas.saveLayer` with a Paint carrying the requested
   alpha + blend mode; matching `endTransparencyGroup` calls `restore`,
   which composites the layer back onto the parent.

Soft masks (`/SMask`) are parsed and propagated through the graphics state
but the backend mask compositing (render mask group to luminosity → apply
as DstIn) is a Session-8 deliverable. Annotations + sample PDFs that use
plain alpha + blend modes now render correctly.

## Session 5 highlights — fonts are a closed problem

KitePDF now parses **every embedded outline format the PDF spec describes**:

1. **Type 1 (`/FontFile`)** — the legacy PostScript format. Header scan for `/Encoding`, eexec stream-cipher decryption with the canonical 55665 seed, charstring decryption with the 4330 seed and `/lenIV` strip, then a Type 1 charstring interpreter (rmoveto / rlineto / rrcurveto / vhcurveto / hvcurveto / closepath / endchar / callsubr / OtherSubr 0–3 for flex).
2. **Type 0 composite fonts** — proper `bytes → CID → GID → outline` pipeline instead of routing everything through Unicode cmap. Identity-H / Identity-V byte-pair CMap, `/CIDToGIDMap` (`/Identity` or an explicit u16 stream), `/W` widths in both the `[cid [w1 w2 …]]` array form and the `[cidStart cidEnd width]` range form.
3. **New `TextGlyph` + `PdfFont.layoutBytes()` API** — replaces per-byte iteration with a code-unit-aware walker so composite fonts contribute one glyph per CID (typically 2 bytes), not one per byte. `ComposeCanvas` and `PageRenderer` both walk this iterator.

The full v0.0.4 mega-push from last session is unchanged:

- Encryption: pure-Kotlin RC4 / AES-128 / AES-256 / MD5 / SHA-256, Standard Security Handler V1–V6, encrypted-PDF round-trip tested.
- JPEG via `expect/actual` (Skia on JVM/iOS, `BitmapFactory` on Android, placeholder on JS).
- CFF / OpenType-CFF (`/FontFile3`) parser + Type 2 charstring interpreter.
- DeviceCMYK + Indexed colour spaces.
- Form XObject recursion.
- LZW filter.
- Annotations: 24 subtypes parsed, Link / Highlight / Underline / StrikeOut rendered with fallback drawing.

## License

Apache 2.0. Encoding tables retain their AGPL-3.0 headers in source comments (ported from MuPDF).

## Acknowledgements

Architectural inspiration from **MuPDF** by Artifex Software (AGPL-3.0). Encoding tables and glyph list data ported from MuPDF source. Standard 14 font widths derived from URW++ AFM files MuPDF ships. CFF + Type 2 charstring impl from Adobe Tech Notes 5176 + 5177. Standard Security Handler from ISO 32000-1 §7.6.

---

*KitePDF is built and maintained by [yuroyami](https://github.com/yuroyami). Started as "could we do this without leaning on PDFBox?"; turns out you can — it's just a lot of careful Kotlin.*
