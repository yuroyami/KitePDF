# OPUS audit of KitePDF, KiteJS and ImageKodec

Audited on 2026-11-04 at KitePDF `c4e7574`, KiteJS `5882331` and ImageKodec `aba04d0`. Consumed into GitHub issues on 2026-11-05.

Each finding was checked against the code, proven by a probe or a complete trace, and checked against the existing issues. Findings that the four audits share became one issue. Every issue made from this file ends with a line "Found by the November 2026 audits: ... OPUS <id> ...", so a search of the issues for the id finds it. Proposals became `kind:feature` issues, which end with "Proposed by the November 2026 audits". What is below is all that is left.

## Not yet filed

### KP-129: On mingwX64, KiteDoc.openFile cannot open a path with a character outside ASCII

Status: could not be settled in the Linux container the audit was consumed in.

**On mingwX64, KiteDoc.openFile cannot open a path with a character outside ASCII**

- Repo: KitePDF
- Labels: area:core, kind:bug
- Severity: low
- Confidence: Likely

#### What happens

- `readPosixFile` calls `fopen(path, "rb")` (`src@mingw/.../PosixFileIo.kt:26`). Kotlin/Native passes a `String` to a `const char*` parameter as UTF-8.
- The Windows C runtime reads a narrow file name in the ANSI code page of the process, not UTF-8, unless the program opts into UTF-8. So `C:\Users\Zoë\book.pdf` reaches `CreateFileA` as `ZoÃ«` and fails, and `openFile` throws `cannot open file`. Every path under a user profile with a non-ASCII name fails.

#### What should happen

The mingw source set opens the file with `_wfopen` and the path as UTF-16 (`path.wcstr`), as the JVM and Apple openers already handle any name.

#### How to reproduce

On Windows, a mingwX64 test: write a PDF to `"${tmp}\\Zoë.pdf"` and call `KiteDoc.openFile` on it. Expected: the document. Likely: `KiteFormatException("cannot open file: …")`.

#### Where

- `kitepdf/src@mingw/io/github/yuroyami/kitepdf/document/PosixFileIo.kt:26`.

#### Notes

Read from source; not run on Windows. The step not proven here is that the Kotlin/Native mingw runtime does not switch the process to the UTF-8 code page. The comment in the three copies names `posixLlp64Main`, which is `src@mingw`.

## Could not be settled here

Claims that need a device, a Mac, Windows or a release build to prove or refute. Each says what would settle it.

| Where | Claim | What would settle it |
| --- | --- | --- |
| 6888, item 15 | CoreGraphics with a tiny dash pattern and a negative dash phase | Needs a Mac: render a 0.001-point dash pattern and a negative phase on CoreGraphicsCanvas and time it. Related: #770 and #979. |
| 6888, item 16 | The transparency layer of each translucent XPS element may be page-sized | Needs a measurement of layer sizes for an XPS page with many translucent elements on Skia or Android. |
| 6888, item 17 | A custom PdfScriptHandler that suspends mid-call may break the viewer's one-call-at-a-time contract | Needs a scene test with a handler that suspends in keystroke while the reader types again. Related filed issues: #741, #735, #739. |
| 6888, item 18 | Whether close() ends nested calculateNow rounds | Needs a probe that closes a runner from another thread during nested rounds; not run. |
| 6888, item 19 | Whether AGP packages KiteJS's .so files from the new plain natives jar | Needs an Android app build against the next KiteJS release. |

## Refuted

Claims that the code, a probe or a reference reader contradicted. They were not filed; a "(part)" finding was filed for the rest of its claims.

| Id | Why it was not filed |
| --- | --- |
| KP-18 (part) | A documented design choice, not a defect; KP-18 asked to document the choice either way, and it is documented. The owner/user distinction an app needs to enforce it is drafted separately. |
| KP-45 (part) | Only the SinglePage part is refuted: the slot keeps its rasterizer across page changes, except when it passes through a chapter placeholder. The Paged and Spread parts are filed as #660. |
| KP-85 (part) | Not a defect: the references disagree and only PDFium differs (CONTRIBUTING: a difference that only PDFium shows is not a failure). Also the AFPercent_Format variant of the same claim. |
| KP-89 (part) | Only this part of KP-89 is refuted; the defect itself is filed as #805 (KiteRasterCanvas measured, Compose/CoreGraphics/Canvas2D traced). |
| KP-99 (part) | Partial refutation; the viewer part is filed as #689. |
| KJ-01 (part) | A large join on Rhino ends in an OutOfMemoryError that the facade reports, not a hang; reverse, indexOf, lastIndexOf, includes and copyWithin are the Rhino hangs (drafted). |
| line 6888, item 10 | A module's freeCompilerArgs may replace the template's -Xexport-kdoc on native targets: The iOS klib download hit HTTP 429; macOS uses the same kind of override, so the lists merge. |
| line 6888, item 21 | A NaN opacity from SVG could fail the page: OPUS read Compose 1.13.0-alpha02's Color: a NaN alpha becomes 0, so the shape paints nothing instead of throwing. Found sound. |

## Proposals not filed

Proposals of this audit that were judged too general for an issue, or that need an owner decision first.

| Line | Proposal | Why |
| --- | --- | --- |
| 6867, item 14 | DRM (Readium LCP) | Owner decision: concrete scope, but licensing decides whether it can exist in KitePDF. |
