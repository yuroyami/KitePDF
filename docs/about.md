# About KitePDF

**A pure-Kotlin PDF engine for every platform.** KitePDF is a standalone, multiplatform PDF library: no platform wrappers, no native binaries. One Kotlin codebase handles reading, viewing, editing, and building PDFs across Android, iOS, JVM, web, and beyond.

## Philosophy

Most Kotlin PDF "libraries" are thin wrappers around platform engines: `PdfRenderer` on Android, `PDFKit` on iOS, PDF.js in the browser. They're convenient but fragmented; each platform behaves differently, and bugs shift between layers.

KitePDF is the opposite. The entire stack: parser, renderer, editor, writer, crypto, fonts: is written in pure Kotlin. There is no JNI, no platform PDF engine to fall back on, no embedded web view. A PDF is just data; bugs are ours to fix.

This also means the Compose binding draws directly into a `DrawScope`. Pages scroll, zoom, and animate like any other composable, not as embedded platform views. **One codebase. Every target. Bugs are ours.**

## Current Status

KitePDF is **pre-1.0** and experimental. The core features are solid and in production use:

- **Viewing** - Compose viewer with continuous/paged layouts, pinch-zoom, pan, double-tap, and GPU-cheap layer transforms  
- **Text extraction** - grab text from any page  
- **Forms** - read and fill text/choice fields  
- **Annotations** - view and interact with highlights, links, and comments  
- **Encryption** - open and authenticate password-protected PDFs  
- **Editing & saving** - fill forms, stamp watermarks, redact (real removal, not just hiding), rebuild the file  
- **Building from scratch** - compose PDFs programmatically with text, shapes, and colors  

On the roadmap:

- Digital signatures (validation and signing)  
- Image codecs: JBIG2, JPEG 2000  
- Advanced color management (Lab, spot colors, rendering intents)  
- Less common form widgets (media players, rich text)  

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
