# About KitePDF

**A pure-Kotlin document engine for every platform.** KitePDF is a standalone, multiplatform library: no platform wrappers, no native binaries. One Kotlin codebase handles reading, viewing, editing, and building PDFs, plus a complete reflowable EPUB reader, across Android, iOS, JVM, web, and beyond.

## Philosophy

Most Kotlin PDF "libraries" are thin wrappers around platform engines: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser. They're convenient but fragmented; each platform behaves differently, and bugs shift between layers.

KitePDF is the opposite. The entire stack: parser, renderer, editor, writer, crypto, fonts: is written in pure Kotlin. There is no JNI, no platform PDF engine to fall back on, no embedded web view. A PDF is just data; bugs are ours to fix.

This also means the Compose binding draws directly into a `DrawScope`. Pages scroll, zoom, and animate like any other composable, not as embedded platform views. **One codebase. Every target. Bugs are ours.**

## Current Status

KitePDF is **pre-1.0**. The core features are solid and verified page-by-page against MuPDF:

- **Viewing** - Compose viewer with continuous/paged layouts, two-page spreads, RTL progression, pinch-zoom, pan, double-tap, text selection, search highlights, outline panels, and link taps  
- **Text** - extraction, structured text with geometry, and engine-level search for both formats  
- **EPUB** - a full reflowable EPUB 2/3 reader on the same core: CSS cascade, embedded fonts (TTF/OTF/WOFF/WOFF2), hyphenation in seven languages, CJK justification, ruby, vertical writing, floats, tables, and reader settings  
- **Forms** - read and fill text, checkbox, radio, and choice fields  
- **Annotations** - view and interact with highlights, links, and comments  
- **Encryption** - open, authenticate, EDIT, and CREATE password-protected PDFs (AES-256/R6 write support)  
- **Editing & saving** - fill forms, stamp watermarks, redact (real removal, not just hiding), incremental save or full rebuild  
- **Building from scratch** - text (standard or custom embedded fonts, with subsetting), shapes, images, and colors  
- **Image codecs** - pure-Kotlin PNG, JPEG, GIF, JBIG2 (full profile), and JPEG 2000  
- **Signing scaffold** - `/ByteRange` preparation and CMS embedding; the cryptography stays in your application  

On the roadmap:

- Signature validation  
- Advanced color management (ICC application, rendering intents)  
- Less common form widgets (media players, rich text)  
- More handlers on the shared core (XPS, CBZ, SVG)  

## Reporting Bad Renderings

If a PDF renders incorrectly in KitePDF, file an issue with the file attached. The project includes a [pixel-diff harness against MuPDF](https://github.com/yuroyami/KitePDF/blob/main/kitepdf-native-renderer/DIFFTEST.md); we compare pixel-perfect output to the reference engine and add regression tests for every fix.

## Contributing

Contributions are welcome. Check the [GitHub repository](https://github.com/yuroyami/KitePDF) for open issues and the developer guide. Code changes, test additions, and rendering fixes are especially valuable.

## License

KitePDF is licensed under the **Apache License 2.0**. This means you can freely use, modify, and distribute it in commercial and open-source projects.

A small number of source files contain encoding tables derived from [MuPDF](https://mupdf.com/) and retain their original AGPL-3.0 headers in comments. These are isolated to specific files and do not restrict the broader project. See the source comments for exact locations.

## Acknowledgements

- **MuPDF** by Artifex Software - architectural reference and rendering insights  
- **URW++ Fonts** - standard 14 font width metrics, via AFM files  
- **PDF specification** - thanks to all those who published the standard and reference materials that made this engine possible
