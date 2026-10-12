# About KitePDF

KitePDF is a Kotlin Multiplatform library for documents. It reads and shows PDF, EPUB 2 and 3, CBZ, SVG, and XPS or OpenXPS. It also creates and edits PDF files.

## Design

Many Kotlin PDF libraries wrap the PDF engine of each platform: `PdfRenderer` on Android, PDFKit on iOS and PDF.js in the browser. Each platform then behaves differently, and a bug can come from any of these engines.

KitePDF has one engine for every platform:

- The parser, the renderer, the editor, the writer, the cryptography and the font code are in Kotlin, and almost all of that code is common.
- `kitepdf-core` has three `expect` declarations: a lock (`KiteLock`), a thread id (`currentThreadId`) and a hook for deflate compression (`PlatformFlate`).
- The only other platform code opens a document from a file or from a platform type, such as an Android `Uri`.
- The engine uses no JNI, no platform PDF engine and no web view.

Two optional artifacts use native code:

- `kitepdf-javascript` runs scripts with QuickJS, through KiteJS.
- `kitepdf-media` plays audio and video with FFmpeg, through KitePlayer.

The Compose viewer draws each page into a `DrawScope`. So the pages scroll, zoom and animate like any other composable.

## What works

KitePDF is not at version 1.0 yet, so its public API can change in a minor release. The release notes list each breaking change.

| Area | What works |
| --- | --- |
| Formats | PDF, EPUB 2 and 3, CBZ comic archives, SVG, and XPS or OpenXPS. `KiteDoc.open` opens each of them. |
| Viewing | Continuous and paged layouts, two-page spreads, right-to-left page order, pinch zoom, pan, double-tap zoom, text selection, search highlights, an outline panel and link taps |
| Text | Text extraction, structured text with positions, and search, for PDF and EPUB |
| EPUB | Reflowable and fixed-layout books: the CSS cascade, embedded fonts (TTF, OTF, WOFF and WOFF2), hyphenation in more than 60 languages, CJK justification, ruby, vertical writing in both directions, floats, tables, absolute positioning, an accessible reading order and reader settings |
| Forms | Read and fill text, checkbox, radio and choice fields |
| JavaScript | The scripts of PDF forms and EPUB books, with `kitepdf-javascript` |
| Media | The audio and video of EPUB books and their media overlays, with `kitepdf-media` |
| Encryption | Open, edit and create PDF files with a password. New encryption uses AES-256 (revision 6). |
| Editing | Fill forms, stamp pages, change the page order, and redact content. A redaction removes the content, not only covers it. Save incrementally or rewrite the whole file. |
| Creating | Text in the standard fonts or in embedded fonts (as a subset), shapes, images and colors |
| Images | ImageKodec, a pure-Kotlin codec library, decodes PNG, JPEG, GIF, BMP, TIFF, WebP, AVIF, JPEG XL and JPEG 2000 images, and the JBIG2 and CCITT data of PDF files |
| Color | Device color spaces, CalGray and CalRGB, Lab, Indexed, Separation and DeviceN. ICC profiles for gray, RGB and CMYK, with matrix and TRC or with lookup tables, through the rendering intent of the document. A CMYK output intent converts DeviceCMYK. |
| Signing | `PdfSigner` prepares the signature field and the byte range, then embeds the CMS signature. Your app makes the signature with its own key. |
| Signature validation | CMS, CAdES and PKCS #1 signatures and document timestamps, with RSA and ECDSA. The result gives the signer, the certificate chain, the trust in the anchors that you give, and the changes made after signing. |

The README lists [a few limitations to know about](https://github.com/yuroyami/KitePDF#a-few-limitations-to-know-about). Missing features and known bugs are [open issues](https://github.com/yuroyami/KitePDF/issues).

## Report a rendering problem

If KitePDF renders a document wrong, [open an issue](https://github.com/yuroyami/KitePDF/issues/new) and attach the document.

## Contributing

Read [CONTRIBUTING.md](https://github.com/yuroyami/KitePDF/blob/main/CONTRIBUTING.md) before you open a pull request. It has the rules of the project and the checks to run before each commit.

## License

KitePDF uses the Apache License 2.0. You can use, change and distribute it in commercial and open-source projects.

KitePDF contains no third-party source code. It bundles third-party data, each under its own license:

- The URW base 35 fonts, which draw the 14 standard fonts, under the SIL Open Font License 1.1.
- The DeviceCMYK table of PDFium, under the BSD-style license of PDFium.
- The Adobe Glyph List, under the terms of Adobe.
- The ZapfDingbats glyph list and the CJK CMaps of Adobe, under BSD-3-Clause.
- The encoding indexes of the WHATWG Encoding Standard, under BSD-3-Clause.
- The hyphenation patterns of the hyph-utf8 project, each under the license in its file header.

The [NOTICE](https://github.com/yuroyami/KitePDF/blob/main/NOTICE) file lists each piece with its source.
