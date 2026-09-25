# XPS and OpenXPS

`kitepdf-xps` is a fixed-page handler for Microsoft XPS and OpenXPS packages.
It uses the same `KiteDocument`, `KitePage` and canvas APIs as the other
formats, so existing viewers and rasterizers can render it. It is published
from `0.11.0`.

## Open a document

The umbrella module re-exports XPS and detects its package structure before
trying to open the ZIP as a comic archive:

```kotlin
import io.github.yuroyami.kitepdf.document.KiteDoc
import io.github.yuroyami.kitepdf.xps.XpsDocument

val document = KiteDoc.open(bytes)
// When the format is already known:
val xps = XpsDocument.open(bytes)
val firstPage = xps.pages.first()
println("${firstPage.displayWidth} x ${firstPage.displayHeight} points")
val text = firstPage.textContent().plainText
```

Add `io.github.yuroyami:kitepdf:0.11.0` for all handlers, or
`io.github.yuroyami:kitepdf-xps:0.11.0` for XPS alone.

## Package and page model

Pages follow the package's fixed-document-sequence order, including sequences
that contain several documents. Relationships and part references resolve
inside the package. Split OPC parts are assembled in order; external resource
references are not fetched. Page markup and fonts are loaded as needed.

XPS markup uses 1/96-inch units. The public page API converts them to points
(1/72 inch), matching the other document handlers. Page rendering, extraction,
search and the shared viewer therefore use the same coordinate contracts.

Damaged drawing elements are skipped independently. An unreadable referenced
page retains its position as a placeholder, so later page numbers stay stable.
A package with no recoverable sequence or page references does not open.

## Rendering scope

The handler reads abbreviated and expanded paths, glyph runs, embedded
TrueType/OpenType CFF fonts and TTC faces, obfuscated font resources, and
resource dictionaries. Solid colours, pad gradients, image brushes and visual
brushes use the shared canvas, with transforms and clipping.

Remaining limits include repeated/reflected gradients (pad fallback), non-solid
stroke brushes, JPEG XR, external ICC colour conversion, StoryFragments reading
order, links/outlines, print tickets, signatures and editing. Sideways text,
synthetic font styles and extracted text boxes use approximations. Transparency
and gradients also depend on the selected backend's capabilities. The module's [API documentation](https://yuroyami.github.io/KitePDF/api/kitepdf-xps/index.html)
describes the supported brushes and the remaining fidelity limits.

This is document-reading support, not a claim of full XPS conformance or XPS
writing. The format reference is [ECMA-388, Open XML Paper Specification](https://ecma-international.org/publications-and-standards/standards/ecma-388/).
