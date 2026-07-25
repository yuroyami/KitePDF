# About KitePDF

**A pure-Kotlin document engine for every platform.** KitePDF is a standalone multiplatform library. It reads, views, edits and builds PDFs, and it reads reflowable EPUB 2/3 books. One Kotlin codebase covers Android, iOS, JVM, web and Kotlin/Native. There are no platform wrappers and no native binaries.

## Design

Most Kotlin PDF libraries are thin wrappers around a platform engine: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser. Each platform then behaves differently, and a bug can sit in any layer.

KitePDF uses a single engine instead. The parser, renderer, editor, writer, crypto and fonts are written in Kotlin, and almost all of that code is common. `kitepdf-core` carries three `expect` declarations: a mutex, a thread id, and the deflate/inflate hook. Nothing else in the engine branches per platform. There is no JNI, no platform PDF engine underneath and no embedded web view.

The Compose binding draws into a `DrawScope`. Pages therefore scroll, zoom and animate like any other composable, not like an embedded platform view.

## Current status

KitePDF is **pre-1.0**. These features work today:

| Area | What works |
| --- | --- |
| Viewing | Compose viewer with continuous and paged layouts, two-page spreads, RTL progression, pinch-zoom, pan, double-tap, text selection, search highlights, outline panels, link taps |
| Text | Extraction, structured text with geometry, and engine-level search for both formats |
| EPUB | Reflowable EPUB 2/3 on the same core: CSS cascade, embedded fonts (TTF, OTF, WOFF, WOFF2), hyphenation in seven languages, CJK justification, ruby, vertical writing, floats, tables, reader settings |
| Forms | Read and fill text, checkbox, radio and choice fields |
| Annotations | View and interact with highlights, links and comments |
| Encryption | Open, authenticate, edit and create password-protected PDFs (AES-256/R6 on write) |
| Editing and saving | Fill forms, stamp watermarks, redact (the content is removed, not covered), incremental save or full rebuild |
| Building | Text with standard or custom embedded fonts, including subsetting, plus shapes, images and colors |
| Image codecs | Pure-Kotlin PNG, JPEG, GIF, the JBIG2 arithmetic generic-region path, and JPEG 2000 Part-1 baseline profiles |
| Signing scaffold | `/ByteRange` preparation and CMS embedding. The cryptography stays in your application. |

Planned:

- Signature validation
- Advanced color management (ICC application, rendering intents)
- Less common form widgets (media players, rich text)
- More handlers on the shared core (XPS, CBZ, SVG)

Known limits are listed in the [README](https://github.com/yuroyami/KitePDF#limits).

## Reporting a bad rendering

If a PDF renders incorrectly in KitePDF, file an issue with the file attached. The project includes a [pixel-diff harness](https://github.com/yuroyami/KitePDF/blob/main/kitepdf-native-renderer/DIFFTEST.md) for the JVM/AWT backend, and every rendering fix lands with a regression test.

## Contributing

Contributions are welcome. Check the [GitHub repository](https://github.com/yuroyami/KitePDF) for open issues and the developer guide. Code changes, test additions and rendering fixes are especially valuable.

## License

KitePDF is licensed under the **Apache License 2.0**. You can use, modify and distribute it in commercial and open-source projects.

A small number of source files contain encoding tables derived from [MuPDF](https://mupdf.com/) by Artifex Software, and those files keep their original AGPL-3.0 headers in comments. They do not restrict the rest of the project. See the source comments for the exact locations.

Standard-14 font width metrics come from URW++ AFM files.
