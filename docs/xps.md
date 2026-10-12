# XPS and OpenXPS

`kitepdf-xps` reads Microsoft XPS and OpenXPS packages, which are fixed-page documents. An `XpsDocument` is a `KiteDocument` with `KitePage` pages, so the Compose viewer, the headless renderers, search and text selection work on it as on the other formats.

## Open a document

```kotlin
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.xps.XpsDocument

// When you do not know the format
val document = KiteDoc.open(bytes)

// When you know that the file is XPS
val xps = XpsDocument.open(bytes)
val firstPage = xps.pages.first()
println("${firstPage.displayWidth} x ${firstPage.displayHeight} points")
val text = firstPage.textContent().plainText
```

Add `io.github.yuroyami:kitepdf:0.13.0` for every format, or `io.github.yuroyami:kitepdf-xps:0.13.0` for XPS alone. `KiteDoc` checks a ZIP file for an XPS package before it tries the file as a comic archive, see [Loading a document](loading.md).

## Package and pages

- The pages follow the order of the package's fixed document sequence, also when the sequence has several documents.
- Relationships and part references resolve inside the package.
- KitePDF joins split OPC parts in order. It does not get resources from outside the package.
- KitePDF loads page markup and fonts when it needs them.

XPS markup uses units of 1/96 inch. The page API converts them to points (1/72 inch), as the other formats use. So rendering, text extraction, search and the viewer use the same coordinates for XPS as for PDF.

KitePDF skips each damaged drawing element on its own. A page that KitePDF cannot read keeps its place as an empty page, so the numbers of the later pages do not change. A package that has no sequence or page references that KitePDF can recover does not open.

## Links

`XpsPage.hyperlinks` gives each element that has a `FixedPage.NavigateUri` as a `KiteLink`. The `rect` of the link is the box of what the element draws, inside its clips, in points from the top-left corner of the page. A path counts when it has a fill or a stroke.

- A URI with a scheme (such as `https://`) goes outside the document. It is in `uri`.
- A relative URI goes to a place inside the package. It can name a page part, a fixed document, or an element by its `Name`, as the `LinkTarget` entries of the page references list it. `target` gives the page, and `targetY` gives the top of the named element on that page.

```kotlin
for (link in page.hyperlinks) {
    val uri = link.uri
    if (uri != null) {
        println("${link.rect} opens $uri")
    } else {
        println("${link.rect} goes to ${link.target} at ${link.targetY}")
    }
}
```

`KiteDocView` follows these links as it follows PDF links.

## What KitePDF renders

- Abbreviated and expanded path geometry.
- Glyph runs, with embedded TrueType and OpenType CFF fonts, TTC faces and obfuscated font resources.
- Resource dictionaries.
- Solid color brushes, gradient brushes with each spread method (pad, reflect and repeat), image brushes and visual brushes, with transforms and clipping.

Sideways text, synthetic font styles and the text boxes of extracted text are approximations. Transparency and gradients also depend on what the renderer of each platform can do. The [API reference of `kitepdf-xps`](https://yuroyami.github.io/KitePDF/api/kitepdf-xps/index.html) describes each brush.

KitePDF reads XPS documents. It does not write them, and it does not claim full XPS conformance. The open issues with the [`area:xps` label](https://github.com/yuroyami/KitePDF/issues?q=is%3Aissue+is%3Aopen+label%3Aarea%3Axps) list the parts of the format that it does not render yet. The format reference is [ECMA-388, Open XML Paper Specification](https://ecma-international.org/publications-and-standards/standards/ecma-388/).
