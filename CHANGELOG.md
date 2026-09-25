# Changelog

All notable changes to KitePDF are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `KiteDocView.onHighlightTap`, `KiteDocViewState.highlightAt`, and optional
  `KiteHighlight.id`: hosts can open edit/delete controls when a saved mark is tapped.
  Hit testing follows painted quads through zoom and pan, prefers the topmost mark,
  and excludes transient search hits. Returning false preserves normal link/page taps.
- `KiteDocLayout.Continuous.contentPadding` provides scrollable clearance for floating
  reader controls without cutting the document viewport off at the system safe area.
- `KiteDocView.onEpubReferenceTap` receives a tapped EPUB reference to a note, a
  glossary entry or a bibliography entry before the viewer scrolls to it. A host can
  show the note in place with `EpubDocument.linkTarget` and keep the reader on the
  page (#277).

### Fixed

- Viewer tap handlers now use current host callbacks after recomposition, so annotation
  actions do not retain the state from before a mark was created or edited.

### XPS and issue fixes

- Added the `kitepdf-xps` XPS/OpenXPS handler with OPC package discovery,
  fixed pages, paths, glyphs, obfuscated fonts, brushes and resource dictionaries.
  The umbrella opener recognizes XPS before the CBZ fallback. The module is
  unreleased; its documentation lists rendering and conformance limits (#205).
- Added lazy ComicInfo.xml metadata and page bookmarks for CBZ archives (#206).
- Added `KiteScrollPosition` and continuous viewport-offset save/restore in both
  scroll axes, including reopening and right-to-left layout direction (#251).
- EPUB font stacks try later embedded families before their generic fallback
  (#104). Underlines, strikethroughs and inline backgrounds paint across styled
  spaces and line wrapping (#103, #168).
- SVG percentage geometry resolves against the nearest viewport, including
  nested SVGs and viewBox user coordinates (#177).
- Android repeats odd-length PDF dash arrays before passing them to the native
  path effect (#105). Inline images resolve scoped colour-space resources and
  retain exact sample boundaries (#114). View usage rules hide print-only PDF
  layers in the default display configuration (#57).
- AWT soft masks preserve the existing backdrop and work on RGB destinations
  while retaining per-paint blend modes (#78, #80). Mask colours no longer
  paint over the page on AWT; the other backend work in #79 remains open.
- CI pins a colour-managed MuPDF oracle instead of using distribution packages
  whose colour configuration changes the differential result (#223).
- Platform documentation separates declared targets from tests, compile checks
  and targets with no CI verification (#193).
- Two generated EPUB sweep fixtures cover inline decorations and backgrounds,
  adding one page each without changing the existing fixture bytes.

### Additional reader fixes

- SVG embedded stylesheets support type, class, ID, universal and compound
  selectors with specificity, source order, inline styles and `!important`.
  Unsupported selectors and at-rules are skipped without leaking styles (#89).
- EPUB images stay upright in vertical writing and keep CSS width/height in
  physical axes. Block and inline `object-fit: cover` images crop to their
  frames in horizontal and vertical writing (#100, #170).
- Matrix scalar coordinate methods remove per-point `Pair` and boxed-number
  allocations from all six canvas backends, retaining `transformPoint` for
  existing callers (#185).
- `KiteCanvasDecorator` exposes the page paint pass through both viewer render
  specs and the public rasterizer. Replacing a decorator invalidates cached
  pixels; system-font retries use fresh wrappers (#132).
- Two additional EPUB sweep fixtures add one page each. All 26 existing books
  retain their page counts.

### Follow-up fixes

- A reference to a missing object no longer fails every page of a PDF with
  layers. Optional content reads it as null and hides nothing (#252).
- An inline image whose computed length does not end at `EI` no longer drops
  the rest of the page. Text extraction, the editor and redaction split inline
  images where the renderer does (#254, #266).
- The Deflate oracle test reads the CI oracle through `MUTOOL`, and reports
  skipped instead of passed when the oracle is absent (#260).
- AWT soft masks composite the masked content as one layer. Masked pages
  render faster than before #78, one blend mode survives the mask, and mixed
  blend modes keep the exact backdrop path (#256).
- An AWT soft mask whose `/BBox` cannot be read covers the page instead of
  erasing the content, and a mask past the raster budget applies at a lower
  resolution instead of being skipped (#255, #264).
- AWT blend modes work on RGB surfaces (#272).
- Inherited page boxes, resources and rotation, form and appearance geometry,
  annotation and widget rectangles, article beads and Type 3 font matrices may
  be indirect objects. A reference to a missing object reads as absent (#273).
- `CssValues.alpha` reads the alpha of a CSS colour. EPUB backgrounds keep
  their alpha, and a transparent one paints nothing. A forced reader text colour
  removes author backgrounds, and the Sepia theme keeps light fills light (#253).
- EPUB `system-ui` and the `ui-*` font families resolve to their generic face,
  and `font-family: inherit` keeps the parent's family (#257).
- A space between two inline elements no longer grows the line, and copied text
  keeps it (#259). An inline image in `vertical-lr` text sits in its own column
  (#261).
- Underlines and line-throughs skip inline blocks, floats and positioned boxes,
  and keep the colour and thickness of the element that draws them. EPUB reads
  `text-decoration-color` (#265, #271).
- An SVG `<image>` with an auto, empty or unreadable width or height takes its
  intrinsic size or ratio (#263). Clip path content inherits style from the clip
  path's own ancestors, not from the element that uses the clip (#269).
- XPS text with a gradient or image fill and a missing font paints in one colour
  of the brush instead of disappearing (#267). XPS clips leave out figures that
  are marked as unfilled (#268).
- CI uploads the XPS raster test output when that test fails (#270).
- The continuous viewer keeps the centre page as the reading position when it
  leaves the screen, and a start page past the end opens at the last page (#258,
  #262).

### More fixes

- SVG text reads its `x`, `y`, `dx`, `dy` and `rotate` lists per character, and
  `text-anchor` aligns each text chunk as one piece across its `<tspan>`s
  (#181).
- An image whose soft mask has a `/Matte` colour has that preblend undone, so its
  edges lose the matte-coloured fringe. `KiteImageData.softMaskMatte` carries
  the colour (#159).
- Text notes, file attachments, carets, stamps and free text annotations with no
  appearance stream get one: the note or attachment icon, the caret, the stamp
  name in a frame, and the free text in its `/DA` font and colour (#164).
- A shading pattern follows the space of the stream that paints it, so a pattern
  inside a form moves with the form, and the painted path clips it under the
  current matrix (#93). An uncoloured tiling pattern paints in the colour given
  with its name, and its cell cannot set a colour of its own (#94).
- Stroked and clipping text in a font without an embedded program uses the
  outlines of the host face on AWT, Skia, Compose and Android 14 or later. The
  new `KiteCanvas.hostGlyphOutline` supplies them. A canvas without host
  outlines fills stroked text in the stroke colour instead of drawing nothing
  (#85).
- Search and selection find text that fills nothing: invisible text such as an
  OCR layer, outlined and clipping text, and text in a colour that paints
  nothing. Type 3 text reaches the text layer too (#274).
- A block image with a border, padding or background paints them, and the picture
  sits inside them (#101).
- An SVG chapter reads as one image named by its `<title>`, else its `<desc>`, and
  a fixed-layout SVG chapter takes its page size from its `viewBox` (#26).
- An `<svg>` inside an inline element, such as a MathJax equation in a `<span>`,
  flows on the line like an `<img>`, sized by its `width` and `height` in the
  element's own font. An `<svg>` with `display: none` takes no space (#275).
- An `<svg>` written in a chapter loads its `<image>` from the chapter's folder,
  so cover pages in a folder, as Project Gutenberg books have them, draw their
  cover (#276).
- A JPEG keeps the colour space and `/Decode` array of its image, so an inverted,
  spot colour or ICC-tagged JPEG shows its own colours. Eight-bit images in a grey
  space, an ICC matrix profile or CalRGB convert through tables, about twenty times
  faster than before (#72).
- `EpubLink.kind` says whether a link is a note, glossary or bibliography
  reference. `EpubDocument.linkTarget` reads the text, kind and a reflow-safe
  bookmark of the element a link points at, so a reader can show a footnote in
  place (#227).
- Redaction finds text where the renderer draws it. Text could survive a region
  drawn over it when the page carried the font size in the text matrix, set
  leading or spacing before a text object, raised text with `Ts`, used a Type 3
  font or a font missing from the resources, or drew a form that inherits its
  font (#278).
- Vertical CJK text in a PDF, drawn with a Type 0 font in writing mode 1 such
  as `Identity-V`, stands in columns instead of one row across the page. Text
  extraction, search and redaction follow the columns. `PdfFont.isVertical` and
  `PdfFont.verticalMetrics` expose the writing mode and the `/W2` metrics (#124).
- An embedded Type 1 font keeps every glyph. The glyph count after `/CharStrings`
  was read as the length of a glyph program, so the first glyphs of the font drew
  nothing (#279).
- A CFF or Type 1 font draws at the size its own `FontMatrix` gives. A font of
  2048 units per em drew about twice too large (#140).
- A Type 0 font over a CID-keyed CFF program finds each glyph through the charset
  of the program (ISO 32000-1, 9.7.4.2). Text in such a font drew the wrong glyphs
  or fell back to a system font. This hit most CJK subset fonts and every OpenType
  CFF font that `PdfBuilder` embeds (#280).
- `PdfBuilder` embeds an OpenType CFF font at its true size when the font is not
  1000 units per em. `CffSubsetter.subset` takes the units per em in a new
  `unitsPerEm` parameter and writes them into the `FontMatrix` of the subset (#281).
- A font embedded as a whole OpenType file (`/FontFile3` with `/Subtype /OpenType`)
  draws from its own CFF or TrueType outlines instead of a system font. An OpenType
  CFF font without a `FontMatrix` takes its units per em from its `head` table, as in
  FreeType (#282).
- An SVG shape filled with a gradient shows the gradient inside the shape. The
  shape was transformed twice, so the gradient painted far from the shape, or off
  the page, and the shape stayed empty (#283).
- Axial and radial shadings keep their shape under a non-uniform or skewed
  transformation on every backend. A stretched radial shading drew circles instead
  of ellipses, and a skewed axial shading drew its bands at the wrong angle (#69).
- A radial shading keeps its start circle on AWT, Compose and Android. The
  highlight of a sphere or a button no longer turns into a plain fade around one
  centre. Android draws both circles from API 31 on; below that, only circles that
  share a centre draw exactly (#71).
- Compose, Android, CoreGraphics and Canvas2D honour the `/Extend` flags of axial
  and radial shadings. A gradient that the document confines to a band or a ring
  flooded the whole clip region (#70).
- Text filled in a pattern colour shows the pattern inside its glyphs. A title
  filled with a gradient drew as flat black type (#88).
- Mesh shadings (free-form, lattice, Coons and tensor) no longer show a grid of
  pale lines between their cells. Opaque cells now overlap by about half a device
  pixel, as the cells of a function shading already did (#126).
- A stroke in a pattern colour paints the pattern inside the stroke. A closed
  path was filled instead, an open line painted nothing, and stroked text used
  the fallback colour. The new `KitePath.strokeOutline` builds the area that a
  stroke paints, with its width, dashes, caps and joins (#284).
- SVG gradients honour `spreadMethod`, `stop-opacity` and the alpha of a stop
  colour, and a gradient stroke shows the gradient along the stroke instead of
  one flat colour. XPS gradient brushes honour `SpreadMethod`. The new
  `KiteShading.spreadOver` repeats or mirrors an axial or radial shading over a
  region (#179, #180, #286, #287).
- Every canvas samples an image the same way, by MuPDF's rules. An image drawn
  smaller than its pixels is averaged down first, so thin lines in a scanned page
  fade to grey instead of dropping out. An image enlarged more than twice keeps
  hard pixel edges unless its `/Interpolate` entry is true, and Compose no longer
  blurs it. Images from EPUB, SVG, XPS and CBZ files stay smooth, as in a browser.
  The new `imageSampling` holds the rules (#122, #123).
- A PDF keeps at most 32 MB of decoded images for reuse, and drops the image used
  least recently first. Before, it kept every image it had drawn, so a 40-page
  photo book held 230 MB, enough to run an Android app out of memory. Set
  `PdfDocument.imageCacheBudgetBytes` to change the budget, and call the now public
  `dropDecodedImageCache` when the app runs low on memory (#116).
- An image drawn many times on a page, such as a tiled background, a watermark or a
  row of icons, converts to a platform bitmap once instead of once per draw. On AWT,
  a page that stamps one image 32 times renders in 1.9 ms instead of 25 ms. Each
  canvas keeps at most 16 MB of these bitmaps (#117).
- AWT applies the constant alpha and blend mode of a transparency group once, to
  the whole group. The first paint in a group replaced the group's settings, so a
  half-transparent logo or shadow drew fully opaque and a Multiply group drew as
  a normal paint (#77).
- Text in a font that the document does not embed lands where the document puts
  it on Skia and Compose. Skia drew each run with the widths of the host face, so
  a line laid out to a fixed width ended far short of its place. Compose ignored
  character and word spacing (#121).
- A soft mask on CoreGraphics and Android gates the content by the alpha or the
  luminosity of its group. On CoreGraphics the group painted onto the page in its
  own colours, so a mask drawn in blue turned the masked artwork blue. Android read
  a luminosity mask as an alpha mask (#79).
- Canvas2D composites a transparency group once, with its alpha and blend mode, and
  gates a soft mask by the alpha or the luminosity of its group. Before, shapes that
  overlap in a translucent group drew darker where they overlap, and a Multiply group
  drew as a normal paint. A soft mask painted its own colours onto the page (#77, #161).
- EPUB tables with `border-collapse: collapse` resolve each shared border by the
  rules of CSS 2.1. A `hidden` border hides the edge, the wider border wins, and the
  style decides between equal widths. The table's own border joins the collapse
  instead of painting next to the outer cell borders, and a collapsed table has no
  padding (#210).
- A stroke under a matrix that scales x and y differently has an elliptical pen on
  every canvas, as ISO 32000-1, 8.4.3.2 requires. Before, each canvas drew a round
  pen of the mean scale, so a chart stretched to fit drew its vertical lines too thin
  and its horizontal lines too thick. Dash lengths now stretch with the matrix too.
  The new `strokePen` and `KiteMatrix.keepsCircles` hold the rule (#108).
- `onPageRendered` fires once for each fresh page bitmap. It could fire twice for one
  bitmap when the raster finished just as the viewer started to watch for it, which
  happens more often on a busy machine (#229).
- Images on CoreGraphics stand the same way as on the other canvases. Each image
  drew upside down, because the canvas flipped it for a y-down bitmap while
  CoreGraphics already draws the first row at the top of the image (#289).
- An image paints with the blend mode of the graphics state on every canvas, as
  ISO 32000-1, 11.3.5 requires. Before, an image under Multiply drew as a normal
  paint and hid what was under it. A new `KiteCanvas.drawImage` overload carries
  the blend mode. A canvas that does not override it paints the image alone in
  an isolated group with that blend mode (#113). The renderer calls the new
  overload only for an image with a blend mode other than Normal, so a canvas
  wrapper that overrides only the old overload still sees each Normal image (#290).
- `ApplePdfRasterizer.renderToPngData` returns a PNG with the page the right way up.
  It threw on every call, because it cast Objective-C objects to Core Foundation
  pointers, which Kotlin/Native checks at run time. It also drew the page upside
  down (#288).
- A soft mask reads its /BC backdrop colour and its /TR transfer function, as
  ISO 32000-1, 11.6.5.2 requires. A luminosity mask with a white backdrop hid the
  content outside its group instead of showing it, and an inverting transfer
  function swapped the parts that show and the parts that hide. A mask dictionary
  given as an indirect object was ignored. A new `KiteCanvas.applySoftMask`
  overload takes the transfer function as a `KiteMaskTransfer` table. Compose and
  Android apply it as the straight line closest to the table, which is exact for
  an inverter (#68).
- An image whose `/Mask` is a grey image of more than 1 bit per sample, without the
  stencil flag, is masked by it. Its grey levels become alpha, as in MuPDF. Before,
  the image painted unmasked. ISO 32000-1, 8.9.6 requires the flag, so the file is
  out of spec, and a 1-bit mask without the flag still reads as a stencil (#160).
- A page's DefaultGray, DefaultRGB and DefaultCMYK colour spaces replace the device
  spaces that its content selects, as ISO 32000-1, 8.6.5.6 requires. This covers
  `g`, `rg`, `k`, `cs`, images and shadings. Before, a calibrated document drew its
  device colours uncalibrated. The defaults of a form XObject come from its own
  resources, as the clause says. MuPDF also lets a form inherit the page's (#150).
- Each published module keeps a dump of its public API in its `api/` directory, and
  CI fails when the code and the dump differ. An API change now shows up as a diff
  in review (#202).
- Every backend gives a thin stroke the same weight. A line width of 0 is one device
  pixel, as ISO 32000-1, 8.4.3.2 requires. Any other line is at least a fifth of a
  pixel, as in MuPDF. Before, Skia and Compose drew every thin line one pixel wide,
  so an ECG grid of 0.15-unit lines turned black. AWT, Android, CoreGraphics and
  Canvas2D drew a zero-width line at a tenth of a pixel. `hairlineWidthPx` in the
  Compose viewer is now the width of a zero-width stroke, still 1 by default
  (#109, #110).
- An SVG `stroke-width` of 0 paints no stroke, as SVG 1.1, 11.4 requires. Before, it
  drew a thin outline (#292).
- Mesh shadings match MuPDF to within a few colour levels. Free-form and lattice
  meshes blend their vertex colours at every pixel, where each cell had one flat
  colour. Coons and tensor patches follow their curved surface, and a tensor patch
  uses its four interior points, which were read and then dropped. Each mesh draws
  as one image, so a translucent mesh has no darker seams where cells overlap. A
  mesh with a `/Function` blends the parametric value and then looks it up, as
  ISO 32000-1, 8.7.4.5.5 requires. `KiteShading.PatchMesh` holds `MeshPatch`
  control points in place of `FlatQuad` cells (#196).
- AWT anti-aliases the edges of a clip path, as MuPDF, Skia and Compose do. A
  gradient, an image, a mesh or a pattern stroke inside a curved clip had jagged
  edges, and so did text in a clipping render mode. A rectangular clip keeps every
  whole pixel that it touches, as in MuPDF (#285).
- The lexer reads a real number and an operator without building a String. On a
  dense page of 140,000 operators, a content stream parses in 21 ms instead of 28 ms
  and allocates 30 MB instead of 87 MB. On Kotlin/Wasm a real is now the nearest
  double: the String parse there can be one unit off in the last place (#119).
- The content stream parser lexes each token once. It lexed each operand twice, and
  an integer up to four times. On the same page, a content stream parses in 14 ms
  instead of 21 ms and allocates 24 MB instead of 30 MB. Arrays and dictionaries in
  the document body also stop lexing tokens twice. An integer operand is always a
  number, and a dictionary operand is never a stream, as ISO 32000-1, 7.8.2
  requires (#293).
- Text in one of the standard 14 fonts that a PDF does not embed, such as
  Helvetica, Times or Courier, draws from real outlines. KitePDF bundles the URW
  fonts that MuPDF uses, which have the metrics of the Adobe originals. Before,
  a system font drew the glyphs at the standard widths, so letters crowded or
  left gaps. Names that stand for a standard font, such as `Arial,Bold` or
  `Times,Italic`, find it too. The bundle adds about 500 KB. `PdfFont.hasOutlines`
  tells whether a font draws from outlines, embedded or bundled. The
  DifferentialTest mean falls from 0.0029 to 0.0017 (#298).
- Accented letters and many symbols in an embedded Type1C font draw again. The
  table of CFF standard strings stopped at `germandbls`, so é, ü, ©, ° and every
  other glyph from SID 150 up had no name (#303).
- An image drawn without rotation, or turned by a multiple of 90 degrees, covers
  every pixel it touches, as in MuPDF: its edges move outwards onto whole pixels
  before it is drawn. On AWT, an edge pixel was filled only when the image
  covered its centre, so image edges and scaled images sampled differently from
  MuPDF. Every canvas now applies `gridFitImage`. Three image fixtures match
  MuPDF to the pixel, and the DifferentialTest mean falls from 0.0017 to 0.0016
  (#300, #301).
- DeviceCMYK converts to the colours that PDFium and MuPDF draw. KitePDF used
  the polynomial of pdf.js, which was 5 to 10 times further from both engines
  than they are from each other. It now uses PDFium's table, which approximates
  the Adobe conversion from US Web Coated (SWOP) to sRGB. The initial colour of
  DeviceCMYK, black ink alone, is now the converted colour instead of pure black.
  The DifferentialTest mean falls from 0.0016 to 0.0010 (#299).
- ZapfDingbats codes from 128 up draw the right symbols. The built-in encoding put
  the bracket ornaments of codes 128 to 141 at code 161, so 121 codes named the
  wrong glyph (#304).
- Text extraction returns ZapfDingbats symbols, such as ✔ and ♠, through Adobe's
  ZapfDingbats glyph list. Before, it returned the raw byte (#305).
- On AWT, text renders about three times faster. Each glyph is filled once for
  each size, subpixel position and colour, and copied after that, as MuPDF and
  PDFium do. Glyphs also snap to MuPDF's subpixel grid, so a line of text sits on
  one pixel row. The DifferentialTest mean falls from 0.0010 to 0.0007 (#306).
- Every backend honours whether a transparency group is isolated, when a paint
  inside blends in a mode other than Normal. An isolated group blends against a
  transparent backdrop, and a non-isolated group against the page. AWT and
  Canvas2D painted every such group straight onto the page, and Skia, Compose,
  Android and CoreGraphics isolated every group (ISO 32000-1, 11.4.5, #125).
- A knockout group draws as ISO 32000-1, 11.4.6 describes: where two of its paints
  overlap, the later paint replaces the earlier one instead of compositing over it.
  No backend read the knockout flag before (#125).
- A non-isolated group with an alpha below 1 blends its paints with the page, and
  then mixes the result with the page by that alpha. Before, its paints blended
  against a transparent backdrop. Android and CoreGraphics cannot start a layer
  from the page, so there the group keeps the old result. #308 lists this and two
  rarer cases that are still approximate (#125).
- Every predefined CJK CMap of ISO 32000-1, Table 118 now maps codes to the right
  CIDs. The Unicode-keyed CMaps, such as UniJIS-UCS2-H and UniGB-UTF16-H, fell back
  to CID = code, so an embedded font drew the wrong glyphs. All CMap tables now use
  a compact encoding: with the new ones they take 212 KB, against 157 KB before
  (#198).
- Text in a CJK font that is not embedded and has no ToUnicode map draws and
  extracts. The text comes from the code of a Unicode-keyed CMap, or from the CID
  through Adobe's CID-to-Unicode table of the font's collection, as ISO 32000-1,
  9.10.2 describes. Before, such text drew nothing and extracted as U+FFFD. The
  four tables add 148 KB (#309).

## [0.10.0] - 2026-09-13

A Chinese novel still ran a 192 MB Android heap out of memory after 0.9.0,
because each of its 41 chapters parsed its own copy of the book's font. This
release fixes that and more than 80 rendering and layout bugs found by
auditing PDF, SVG and EPUB output. It also adds `kitepdf-javascript`, which
runs the JavaScript inside PDFs.

### Added

- `PdfAnnotation.isInvisible` and `PdfAnnotation.isNoZoom` expose two more
  annotation flags, both of which the renderer now honours.
- `PdfAnnotation.borderStyle` and `borderDash` carry the border style and
  dash from `/BS`, or the dash from the older `/Border` array.
- `ExtGState.dashArray` and `dashPhase` carry a graphics state dictionary's
  `/D`, and `RecordingCanvas.Call.Stroke` records the dash it was given.
- `EpubSettings.hyphenate` turns hyphenation on or off for the whole book,
  whatever its CSS says. Each chapter uses the patterns of its own language,
  and the book itself is not changed.
- `GraphicsState.softMaskCtm` keeps the matrix a soft mask was set under.
- `KiteColorSpace.paintsNothing` is true for a colour space that paints
  nothing, such as a Separation named `/None`.
- `kitepdf-javascript`, a new artifact, runs the JavaScript that PDFs carry
  on KiteJS. `PdfScriptRunner` runs the document-level scripts and
  JavaScript actions under an instruction budget, so a script cannot hang
  the app. It covers the targets KiteJS builds for.
- `KiteScriptEngine` in `kitepdf-core` is the interface the rest of KitePDF
  talks to, so no other module depends on a script engine.
- `PdfPage.renderTo(canvas, deviceCtm, annotations)` draws only the
  annotations a filter accepts. `{ false }` renders the page without its
  markup, for printing, a clean thumbnail, or an editor that redraws its
  annotation layer on its own.

### Changed

- Built with Kotlin 2.4.20. The viewer now depends on Compose Multiplatform
  1.12.0, the stable release, instead of 1.12.0-beta02, and the Skia renderer
  on Skiko 0.150.1, the version that Compose release is built on. An app that
  uses both gets one Skiko, not two.
- `EpubSettings`, `PdfAnnotation`, `ExtGState`, `GraphicsState` and
  `RecordingCanvas.Call.Stroke` gained constructor parameters, and
  `PageRenderer.render` gained one. Source stays compatible, but anything
  compiled against 0.9.0 needs recompiling.

### Fixed

- A book whose chapters each declare the same `@font-face` in their own
  `<style>` block, which is how some converters write embedded fonts, parsed
  that font once per chapter and kept every copy, with its own glyph caches,
  for the life of the book. The layout budget never saw it: on a 41-chapter
  Chinese novel the budget reported 23 MB while the process held 340 MB, and
  a 192 MB Android heap died with `OutOfMemoryError` however small the budget
  was set. A font file is now parsed once per book and shared by every rule
  that names it. The same book now holds 32 MB.
- An embedded TrueType font kept two copies of every glyph it had drawn, the
  boxed points and the path built from them, about 4 KB per glyph for a glyf
  record of 200 bytes. Only the path is kept now.
- An indexed colour set by a colour operator picked the wrong palette entry:
  every index above zero became the last one, and selecting the space painted
  black instead of entry 0.
- An indexed palette over a Lab base, and a Lab image with no `/Decode`, were
  scaled 0 to 1 instead of Lab's own ranges and rendered near black.
- Painting in a Separation named `/None` covered the artwork with opaque
  white. It now paints nothing, as the specification requires.
- Lab, CalGray and CalRGB colours with a D50 white point had a yellow cast.
- An ICC profile that cannot be applied now falls back to the declared
  `/Alternate` space instead of a device space guessed from `/N`.
- A malformed PDF function aborted the whole page. It now degrades, and no
  function can produce a NaN colour.
- The PostScript calculator's `not` was logical only, and a stitching
  function took its output count from its first piece instead of its own
  `/Range`.
- A form, tiling cell, Type 3 glyph or annotation appearance that left a `q`,
  a clip or a marked-content section open leaked it into the rest of the
  page: the clip, the matrix or a hidden layer outlived the stream, and one
  malformed stamp displaced every annotation drawn after it.
- Past 64 nested `q`, every restore consumed a real saved state. The limit
  is now 4096, and saves and restores keep pairing past it.
- Reusing one `PageRenderer` for several pages painted a page black after a
  page with a stray `d1`.
- An unclosed hidden layer in the page content erased every annotation on
  the page, and an annotation on a switched-off layer was drawn anyway.
- A cyclic visibility expression overflowed the stack, a form with partial
  resources showed hidden layer content, and
  `PdfOptionalContent.isVisibleByDefault` disagreed with the renderer for the
  `/Unchanged` base state.
- A checkbox whose `/AS` names a missing state rendered checked, an indirect
  `/F` or `/AS` was ignored, an Invisible vendor annotation was drawn, a
  NoZoom stamp was stretched to its rectangle, and `/CA` was not applied to
  appearance streams.
- A synthesized highlight washed out the text under it and covered the
  bounding box of a rotated quadrilateral instead of the quadrilateral.
- Text in a font missing from the page resources painted nothing. It now
  paints in Helvetica, as other readers do.
- Invisible Type 3 text (render modes 3 and 7) was painted over the page,
  and a Type 3 font with a name for its `/Encoding` drew no glyphs.
- Arial, Times New Roman and Courier New without `/Widths` were measured at a
  flat half em. They now use the matching Standard 14 metrics.
- A pattern colour space with no pattern named painted black, `sh` ignored
  the active soft mask, and a tiling cell that drew past its box flooded the
  whole fill.
- A dash set through a graphics state dictionary drew solid.
- A line cap, join or miter operator with no operand reset the parameter to
  zero, and a curve after a close or a rectangle started from the wrong
  point.
- A zero-length stroke with square caps painted a square, and a move-then-close
  with round caps drew nothing on AWT but a dot on Skia.
- An inline image using the `/I` abbreviation for Indexed painted its raw
  indices as grey.
- Gradients are sampled at 256 stops instead of 32, so a gradient with many
  bands keeps them, and type 4 mesh shadings read their padded vertex
  records correctly.
- A standalone SVG file drew upside down in the viewer.
- An SVG whose viewBox has a different shape from its width and height was
  stretched. It now follows `preserveAspectRatio`, whose default scales
  evenly and centres, so a circle stays a circle. `slice` clips to the
  viewport.
- SVG lengths in `pt`, `pc`, `in`, `cm` and `mm` were read as points, so
  they came out at three quarters of their size, and `em` ignored the font
  size. They now use CSS pixels, 96 to the inch, as SVG requires. The
  file's own width and height follow the same rule, so a drawing that is
  `210mm` wide opens at its real size instead of its viewBox size.
- Dashed SVG strokes drew solid, and round caps and joins drew square. The
  miter limit now starts at 4, the SVG default.
- An SVG shape whose only paint is a gradient stroke drew nothing. It now
  strokes in the middle colour of the gradient.
- SVG text lost the space before a `<tspan>`, so a label read "Helloworld",
  and a `<tspan>` inside another `<tspan>` lost its text.
- An SVG group with `opacity` showed darker seams where its shapes overlap.
  The group is now composited once, as one layer.
- An SVG `<switch>` drew all of its children instead of the first one whose
  conditions pass.
- An SVG clip path made only of `<use>` or `<text>` elements, or with no
  shapes at all, let the content through unclipped, and a clip path in
  `objectBoundingBox` units clipped to almost nothing.
- An SVG `transform` written in a `style` attribute was ignored.
- An EPUB horizontal rule painted nothing, so scene breaks disappeared. It
  now draws a thin line across the column.
- A block image sized with the HTML `width` and `height` attributes drew a
  third too large.
- A fixed-layout page treated CSS pixels as points, so its content drew at
  75 percent of its authored size and drifted off the page art. The page size
  is now in points too: a viewport of 800 by 1200 pixels makes a page of 600
  by 900 points.
- An inline image right after a word, with no space between them, was never
  drawn.
- A block image was always centred. It now starts at the left edge, or the
  right edge in right-to-left text, unless both margins are `auto`, and its
  margins narrow the room it can fill.
- In a right-to-left list the marker stayed at the left edge, far from its
  item.
- An explicit `text-align: left` inside right-to-left text was flipped to
  the right.
- A link wrapped around a block had no clickable area over the block.
- A comic page did not call the canvas page begin and end hooks, unlike every
  other page type.
- A WebP comic page drew nothing and opened at 800 by 1200 whatever its real
  size. Page sizes now come from the WebP and TIFF headers, lossless WebP and
  TIFF images decode wherever an image is read (comics, EPUB and SVG), and a
  comic page that cannot decode shows a grey sheet instead of nothing. Lossy
  WebP still has no decoder.
- A `ReaderTheme` built inline in a composable was a new cache key on every
  recomposition, so the viewer rendered its pages again each time. Themes now
  compare by value.
- `KitePageRasterizer.rasterize` now says in its docs that the page fills the
  bitmap width, and logs a warning when the height does not match the page's
  aspect ratio. The docs of the vectorized render mode now say that it draws
  on the UI thread.
- Clipped text over a gradient or an image got a solid bar in every word gap.
  A space in a font with outlines now adds nothing to a text clip.
- A matrix change inside a clipping text object moved the clip, and stroked
  and clipping text ignored each glyph's own offset.
- A Type 3 glyph was not clipped to the box its `d1` declares.
- A dotted line (`[0 12] 0 d` with round caps) drew as dashes, and a line
  with a zero gap drew dashed, on AWT and Compose.
- On AWT, a clip and a transparency group that did not nest restored each
  other's state, and blending onto a transparent surface blended against
  black.
- A soft mask was applied to each object inside a transparency group instead
  of once to the group, so every overlap came out darker, and a soft mask
  moved with any `cm` after the `gs` that set it.
- Axial and radial shadings painted outside their `/BBox`, a shading
  pattern's `/Background` was never painted, and its own graphics state was
  ignored.
- A tiling pattern fill under a constant alpha or a blend mode painted
  opaque and unblended.
- A soft mask finer than its image was sampled down to the image's grid, so
  soft edges came out blocky. A soft mask stored as JPEG or JPEG 2000, or at
  2, 4 or 16 bits, was dropped and the image painted opaque, and a soft
  mask's `/Decode` was ignored.
- A square, circle, line, polygon or ink annotation without an appearance
  stream drew a 1-point solid line whatever its border said. It now strokes
  at its declared width and dash, and a square or circle stays inside its
  rectangle.
- A nested SVG `<svg>` element ignored its position, size and viewBox.

## [0.9.0] - 2026-09-08

A reader app that opened a normal-length EPUB on Android ran out of memory a
few seconds later and died. This release fixes that, along with the blank
base-14 text on Linux that had been waiting behind it.

### Added

- `EpubSettings.layoutCacheBytes`: a memory budget for laid-out chapters,
  48 MB by default. The chapters the reader has not used for the longest
  drop their pages and lay out again on the next visit; page counts, anchors
  and bookmarks survive the drop.

### Fixed

- Opening a reflowable book laid out every chapter in the background and
  kept all of them, about 165 bytes per character of text, so a
  normal-length novel filled a 192 MB Android heap within seconds and the
  host app died with `OutOfMemoryError`. Laid-out pages now stay within the
  budget above.
- Every visible EPUB page was rasterized again each time a chapter finished
  laying out, and faded in again, because the document handed the viewer a
  new page object per lookup and the viewer keys its bitmap cache on the
  object. On a 40-chapter book the two visible pages were rendered 30 times
  each. A location now answers the same page object for the life of the
  document.
- An `OutOfMemoryError` inside the rasterizer escaped the failure guard,
  which caught only `Exception`, and killed the host app instead of leaving
  the page placeholder in place.
- Each glyph in a laid-out chapter owned its own one-character `String`;
  glyphs for the same character now share one.
- The Skia renderer drew no text at all for a Standard-14 font on a host
  without Helvetica, Times New Roman or Courier New. It asked for those three
  by name, which macOS and Windows carry and a plain Linux box does not, and
  Skia answers a family it cannot find with a font that paints nothing and
  reports no error. A page of base-14 text came out blank on Linux while
  rendering correctly everywhere else. Each family now lists the
  metric-compatible stand-ins other systems ship (Liberation, Nimbus, DejaVu,
  Noto and friends), falls back to any face the host does have, and skips the
  run rather than drawing with a font that cannot paint.

## [0.8.2] - 2026-08-30

The second half of the 0.8.1 story, found by auditing rather than by a report.

### Fixed

- Structured text: the synthesised-space threshold (a quarter of the font
  size) had rescaled with 0.8.0's effective font size the same way the line
  tolerance had. On fonts that pad their em box it grew past real word gaps,
  so dense chart text glued words together (`HIRL(60m)`, `RVR150m`). The
  threshold is now a tenth of the font size, floored at 0.25pt, calibrated
  so that a normal document keeps its exact mutool-matching spacing and the
  0.8.0-regressed chart returns to its 0.7.0 spacing, byte for byte. Both
  the line text and the search/selection path share the rule.

## [0.8.1] - 2026-08-30

One fix, shipped fast because 0.8.0 broke real parsers.

### Fixed

- Structured text: 0.8.0's effective font size silently rescaled the
  line-clustering tolerance from an accidental 0.5pt to half the em box.
  On dense layouts (aeronautical charts, tables) whose fonts pad the em,
  distinct rows packed 1-1.4pt apart merged into one line and their spans
  interleaved in reading order (#23). A line is a shared baseline, so the
  tolerance is now a small slack around it: 5% of the font size, floored at
  0.5pt. This restores 0.7.0's grouping on the reported file exactly, while
  keeping 0.8.0's correct `fontSize` values.

## [0.8.0] - 2026-08-29

Two new formats. Comic archives and SVG each get their own artifact, and
`KiteDoc.open` recognises them the way it already recognised PDF and EPUB, so a
comic reader can pull `kitepdf-cbz` without the EPUB engine coming with it.

Text geometry got a pass as well. A span now reports the size a reader actually
sees rather than the number in the font operator, and character and word
spacing move the pen between glyphs on every backend instead of only at the
start of the next run.

The rest is hardening. Archive reading, remote downloads and page rasterizing
all gained ceilings and fail closed on malformed input, and creating an
encrypted document now demands a real platform random source instead of
quietly accepting a non-cryptographic one.

### Added

- Russian hyphenation patterns (hyph-utf8, contributed by ksokolovskiy in
  #7); a ru-tagged book with `hyphens: auto` now breaks long words instead
  of falling back to the inert en-US set.
- SVG, as a format of its own. A new `kitepdf-svg` module holds the renderer
  that was buried in the EPUB handler, and `SvgDocument` opens a standalone
  `.svg` as a one-page document at its own viewport, drawn as vectors.
  `KiteDoc` recognizes it as `KiteDocFormat.Svg`.
- SVG drawing catches up: `<use>` and `<symbol>` (with a cycle guard),
  `<image>` from a `data:` URI or a file the caller loads, `<text>` and
  `<tspan>` measured against standard-font metrics, linear and radial
  gradients as fill, `clip-path`, `display`, `visibility`, `fill-opacity`,
  `stroke-opacity`, and a `style=""` declaration outranking the matching
  attribute. A fixed-layout comic whose pages are an SVG wrapping one JPEG
  now draws.
- ICC colour. An `/ICCBased` space was resolved by component count alone, so a
  wide-gamut photo rendered as if it were sRGB. Matrix/TRC profiles (RGB and
  grey) are now read and applied. A profile that IS sRGB stays on the device
  path, where the colour passes through byte-exact.
- EPUB: `EpubPage.readingOrder()`, one page's content in the order a reader
  that speaks would say it, with the role each element declared. `aria-hidden`,
  `role="presentation"` and `alt=""` are left out; `aria-label` replaces the
  text; `aria-hidden` and `epub:type` inherit down the subtree.
- EPUB: `table-layout: fixed`, and real `position: absolute` / `fixed`
  placement (the nearest positioned ancestor is the containing block, and
  `right`, `bottom` and a height from `top`+`bottom` all resolve).
- EPUB: `writing-mode: vertical-lr` lays out, and selection, search and link
  rectangles follow the columns on any vertical page.
- Text: GPOS mark-to-mark and mark-to-ligature attachment, so two stacked
  diacritics sit one above the other instead of overprinting.
- Forms: a checkbox or radio widget with no `/AP` of its own gets one drawn
  from its `/MK` colours, so a ticked box actually looks ticked.
- `ZipReader` reads ZIP64 records and entries sized only by a trailing data
  descriptor, and verifies every entry's CRC-32 (lenient by default, strict on
  request, or ask with `verify`).
- `TextEncoding` in core: text is decoded by weighing a byte order mark, a
  UTF-16 NUL pattern, the document's own declaration, the caller's hint, and
  finally UTF-8 validity, falling back to Windows-1252. Books that ship
  1252 while claiming UTF-8 now read correctly.
- CBZ comic archives. A new `kitepdf-cbz` module reads a ZIP of images as a
  document, one page per image, in natural filename order (`page2` before
  `page10`). `KiteDoc.open` recognizes a CBZ on its own; `CbzDocument.open`
  is the direct route. Pages size themselves 1 px = 1 pt, sizes come from
  image headers so opening does not decode the archive, and packaging noise
  (`ComicInfo.xml`, `Thumbs.db`, hidden files) is ignored. WebP pages are
  detected but render blank until the image engine learns WebP.
- The full MacExpertEncoding vector. Expert-set fonts (old-style figures,
  small capitals) selected the right glyphs for only six codes before.
- CalGray and CalRGB colour spaces apply their gamma, whitepoint and matrix
  instead of collapsing to the device space.
- EPUB: each spine document hyphenates in its own language, so a bilingual
  anthology stops applying chapter one's patterns to everything.
- EPUB: a book whose OPF declares `primary-writing-mode: vertical-rl` and no
  `page-progression-direction` now reads right to left, as vertical books do.

### Changed

- Remote document loading now streams into a bounded buffer instead of calling
  an unbounded `bodyAsBytes`; the default ceiling is 128 MiB and every URL API
  has a `maxBytes` overload. ZIP entries likewise have configurable 128 MiB
  decompressed-size and 100,000-record ceilings.
- Page rasterizers reject non-finite scales and default to a 40-megapixel
  allocation ceiling, published once as
  `io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS`. The
  Compose viewer raises its rasterizer's ceiling to cover
  `maxBitmapLongSide` squared, so a larger configured cap keeps rendering.
  `EpubSettings` now rejects non-finite, negative, and geometrically
  impossible page settings at construction time.
- AES-256 creation now requires an explicitly supplied platform CSPRNG, and
  encrypted editing fails at startup without one. The write path no longer
  silently uses Kotlin's non-cryptographic `Random.Default` for keys or IVs.
- Pull requests now run the JVM, Apple and JS gates, CI jobs have least-privilege
  permissions and timeouts, and Android host tests are attached for modules
  with common tests.
- The XML reader and the CSS value parsers moved from `kitepdf-epub` to
  `kitepdf-core` as `KiteXml`, `KiteXmlNode` and `CssValues`, all public. The
  EPUB cascade and the SVG renderer read the same syntax, and the SVG module
  needed an XML parser that was not inside a book handler.
- `KiteTextLine` knows whether it is a vertical column, so its char edges run
  down the page and hit-testing swaps axes to match.
- `ZipReader` moved from `kitepdf-epub` to `kitepdf-core`
  (`io.github.yuroyami.kitepdf.core.zip`). The old name still compiles as a
  deprecated typealias for one release.
- `KiteDoc.open`'s unreadable-bytes error now names all three formats.
- `PdfDocument.bytes` returns a defensive copy, making the documented
  immutability true. Zero-copy access moved to `rawBytes` behind the
  `KiteRawApi` opt-in.

### Fixed

- Structured text: `PdfTextSpan.fontSize` now reports the effective rendered
  size (the `Tf` size times the text-matrix scale). Producers that write
  `/F1 1 Tf` and carry the size in `Tm` reported 1.0 for every span (#22),
  which also collapsed the line-clustering tolerance and the synthesised-space
  threshold that scale off it.
- Text rendering: `Tc` character spacing and `Tw` word spacing now move the pen
  between glyphs inside a run, on every backend. Before, only the start of the
  next run honoured them, so spaced text painted condensed and then jumped,
  and structured-text bounds and char edges came out narrow.
- `extractText`: the line-break and word-gap heuristics now scale with the
  effective (Tm-scaled) font size, so size-in-Tm documents stop growing
  spurious newlines and word breaks.
- Malformed ZIP offsets, truncated central records and ZIP64 extras now fail
  closed instead of indexing outside the archive. Stored entries are bounded,
  encrypted/header-mismatched entries are refused, false EOCD signatures in a
  comment are ignored, and hostile names cannot inject control characters into
  CRC warnings. A directory holding more records than it declares is refused,
  while a literal 65,535-entry archive and ZIP64 writers that sentinel the
  EOCD disk fields stay readable.
- PDF object parsing caps container nesting at 256 levels, so a hostile
  content stream of thousands of `[` tokens is skipped as garbage instead of
  overflowing the call stack.
- Remote `openUrlOrNull` no longer swallows coroutine cancellation. URL
  credentials, query tokens and fragments are redacted from HTTP status,
  size-limit and transport failures, including nested transport exceptions.
- Oversized Compose page bitmaps are no longer retained above the cache budget;
  byte accounting and raw-image row/pixel arithmetic cannot overflow, and
  truncated huge images are rejected before allocating an RGBA buffer.
- Cyclic SVG gradient references and deeply nested SVG trees no longer consume
  the call stack. Non-finite SVG viewports, gradient stops and coordinates are
  rejected or safely normalized.
- CBZ natural sorting compares arbitrarily long digit runs exactly, without
  overflowing `Long`. Base64 loading now rejects malformed padding, impossible
  tails and non-zero discarded bits without boxing every decoded byte.
- The umbrella artifact's custom POSIX source-set graph is attached after the
  default hierarchy, so Linux, Windows and Android Native receive the documented
  native file APIs instead of silently omitting those source sets. Its `stdio`
  actuals are separated into LP64, ILP32 and LLP64 families, preventing Kotlin
  metadata and Dokka from commonizing incompatible `CLong`/`size_t` signatures.
- Compose viewer: the Paged layout no longer jumps while background chapters
  land, opens directly at a saved Flow bookmark's chapter, keeps zoom and
  selection through landings, and no longer loses the bookmark if opening is
  interrupted. Navigating to a location that no longer exists now clamps to
  the nearest page instead of doing nothing (#5).
- EPUB: first lines of indented paragraphs no longer overflow the content box
  and clip at the page edge. The line breaker fitted every line against the
  full width and the `text-indent` was added afterwards at placement, so a
  packed first line stuck out by up to the indent width (#6).
- EPUB: Cyrillic fallback text is measured with the exact Standard-14 widths
  instead of a flat half-em, so Russian text no longer draws squeezed and
  line-fit decisions use real advances (#6).
- A form XObject or a Type3 char proc with no `/Resources` of its own now
  reads the page's, as the spec asks. Both were looking at an empty map, so
  every name they used resolved to nothing and the stream painted nothing.
- An out-of-flow inline box is blockified (CSS 9.7), which is what gives
  `<img style="position:absolute">` a box of its own instead of a line slot.
  The anonymous inline container also stopped inheriting its parent's
  `position`, which was shifting relative boxes twice.
- Redaction removes a shading (`sh`) whose visible region touches a redacted
  area. A shading paints its whole clipping region, so it is judged by the
  clip's boundary the same way vector paths are judged by their segments; a
  page-wide background shading survives under the black box like any other
  background.
- The Skia differential harness can no longer pass falsely: it runs on the
  same hardened MuPDF oracle as the AWT harness (new internal
  `kitepdf-difftest` module), a failed oracle render or a page-size mismatch
  now fails the test instead of scoring perfect, the drop-in corpus is the
  repo `corpus/pdf`, and the stale allowance for blank Standard-14 text is
  gone.
- The native (non-Skia) renderers reach content parity. Android and
  iOS/macOS draw decoded (RAW) images, which is what every successful JPEG,
  JPEG 2000 and JBIG2 decode produces; the browser Canvas2D backend paints
  them too instead of a placeholder for everything. iOS and macOS render
  Standard-14 (non-embedded) text through the system font instead of leaving
  it blank. The Android and CoreGraphics image transforms also placed the
  unit square wrong for the standard device transform, drawing outside the
  page; both now use the same mapping Skia does, proven by pixel tests.
- Compose no longer flattens a rotated, reflected or sheared image to its
  scale: the full transform reaches the bitmap.
- Filling a form field no longer resurrects a field an earlier redaction call
  in the same editor removed, and `/NeedAppearances` is cleared even when the
  form dictionary is written straight into the catalog.
- On iOS and macOS, an image drawn with an alpha value now actually renders
  translucent.

## [0.7.0] - 2026-08-24

Big EPUBs open at the page the reader left off, instead of paginating the whole
book first.

A reflowable book has no pages until it is laid out, and KitePDF laid out every
chapter before it would show one. On the local corpus that was 86% to 96% of
the time between opening a file and seeing a page: a 9.9 MB book spent 986 ms
there. A reader resuming at chapter 20 paid for chapters 0 to 19 as well.

Now each chapter is laid out on its own, on demand, and the one being read goes
first. Resuming at the last chapter, desktop JVM, local corpus:

| book | chapters | before | after |
|---|---|---|---|
| 6.epub (9.9 MB) | 26 | 986 ms | 3 ms |
| 11.epub (5.0 MB) | 11 | 2085 ms | 71 ms |
| 1.epub | 19 | 729 ms | 12 ms |
| 18.epub | 35 | 320 ms | 7 ms |

The rest of the book lays out in the background, nearest chapter first, and a
chapter landing above the reader does not move the page they are on. A reader
who scrolls ahead onto a chapter that is not ready yet waits on its
placeholder, and lands at the start of that chapter when it arrives.

Chapters are also read and parsed one at a time now. Opening a book reads the
container, the OPF and the table of contents, and nothing else. Two things paid
for that: a chapter's HTML is parsed when that chapter is first laid out, and a
stylesheet is parsed once per file instead of once per chapter that links it
(one corpus book was reading and parsing the same three sheets 42 times over
its 11 chapters).

| book | chapters | open before | open after |
|---|---|---|---|
| 11.epub (5.0 MB) | 11 | 26.2 ms | 0.4 ms |
| 13.epub (5.8 MB) | 7 | 18.9 ms | 0.4 ms |
| 16.epub (6.0 MB) | 8 | 18.6 ms | 0.2 ms |
| 6.epub (9.9 MB) | 26 | 10.2 ms | 0.1 ms |

End to end, opening a book and showing its last chapter: 6.epub 11.3 ms to
2.0 ms, 18.epub 12.1 ms to 4.3 ms, 11.epub 87.4 ms to 58.3 ms. Laying out a
whole book costs the same as before.

Closes the request in
[issue #3](https://github.com/yuroyami/KitePDF/issues/3).

EPUB footnote links land on the note. An inline element wrapping block
children (the shape FB2 conversions emit for footnotes) used to flatten into
one paragraph and anchor to the top of its document, so tapping a footnote
opened the start of the notes section. The inline now splits around its
blocks as CSS requires, the note keeps its own block structure, and the link
lands on the note itself. Reported in
[issue #4](https://github.com/yuroyami/KitePDF/issues/4).

Redaction stops leaving content behind.

`redactRegions` promised that redacted content is removed rather than covered.
In several shapes it was not, and each shape was a different route to the same
outcome: a file the caller ships believing it is clean.

**A second edit to a page discarded the first.** `editPageContent`, `stampPage`
and `redactRegions` each rebuilt from the page as it was opened, never from what
an earlier call had staged. Two stamps left only the second. Worst of all, two
`redactRegion` calls left the first region's content in the file. All three now
read what is already staged, so calls compose in the order they are made.

**A form drawn in several places was rewritten once.** One form XObject can be
invoked many times, and each place sees the redaction rectangle in a different
part of the form. Rewriting the shared stream once was wrong in both directions
at the same time: the other places were never tested against the region, and
because they draw the same object they lost content that was never in a region.
Each place that needs a different redaction now gets its own copy, and a place
no region touches keeps drawing the original untouched.

**A redacted widget's form field stayed reachable.** Dropping the widget from
the page's `/Annots` left `/AcroForm /Fields` and `/AcroForm /CO` pointing at
the same object, so its value, default value, name and appearance stream
survived the rewrite. The field is now detached from both.

**Vector paths in a region were left in the stream.** A signature or a chart
drawn as vector art *is* its coordinates, so painting a black box over it left
the shape recoverable. Paths whose ink reaches a region are now removed, with
the pen width accounted for, and a path that also sets a clip keeps its
construction and stops painting rather than disappearing and letting everything
after it escape the clip.

Underneath all of that, one change of approach: **redaction no longer relies on
the garbage collector to delete a secret.** An object taken off the page is
emptied as well as unlinked, so a reference somewhere the editor does not
rewrite cannot bring its contents back. That covers the paths nobody enumerated,
which is the only way this kind of guarantee holds.

`docs/editing.md` now lists what redaction removes and the limits that remain,
replacing three stated limitations of which two had already been fixed.

### Breaking changes and migration

- **Every spine item now starts on a fresh page.** A short chapter used to
  share its page with the next one, which no mainstream reader does and which
  is what made whole-book layout unavoidable. Page counts rise by roughly one
  per chapter boundary (6.epub 480 to 491, 18.epub 502 to 515).
- **`EpubDocument.outline` no longer paginates the book.** Entries carry a
  `target` bookmark instead, and `pageIndex` is filled in only once the book is
  fully laid out. `KiteOutlinePanel` navigates by `target`, so a table of
  contents opens instantly and a tap lays out that one chapter.
- **`KiteDocViewState.currentPage` counts slots, not pages.** For a PDF, and
  for a book that has finished laying out, it is still the page index. While a
  reflowable book is paginating, each chapter that is not ready holds one slot,
  so it reads low until they land. `currentLocation` is the exact answer.
- `KiteDocViewState.nextPage`/`previousPage` cross chapter boundaries and lay
  out the next chapter when they need to.
- **An `@font-face` inside a document's own `<style>` block now belongs to that
  document.** It used to reach every chapter in the book, which is not what a
  `<style>` block does anywhere else. Faces declared in a stylesheet are still
  the whole book's, which is where real books put them.

### Fixed

- A `url()` in a stylesheet resolves against that stylesheet's folder, as CSS
  says. It used to resolve against the folder of whichever document linked the
  sheet, so an `@font-face` in a book that keeps its CSS and its chapters at
  different depths silently fell back to a system font.
- A fixed-layout document with no `<meta name=viewport>` falls back to the
  reader's current page size. The fallback used to be frozen at whatever the
  book was first opened with, so `withPageSize` did not reach it.

### Added

- **`KiteLocation(chapter, page)`** for a position in the current layout, and
  **`KiteBookmark`** for one that survives a re-flow. Save a bookmark when the
  reader leaves, hand it back when they return, and they land in the same
  paragraph even if the font size changed.
- **`rememberKiteDocViewState(document, bookmark)`**, plus
  `state.currentLocation`, `state.currentBookmark()`, `state.scrollTo(location)`,
  `state.scrollTo(bookmark)`, `state.knownPageCount` and `state.isComplete`.
- **The chapter API on `KiteDocument`**: `chapterCount`, `prepareChapter`,
  `isChapterReady`, `pageCountIn`, `page(location)`, `pageIndexOf`,
  `locationOf`, `bookmarkOf`, `locate`, `isComplete`, `knownPageCount`. Every
  one has a one-chapter default, so `PdfDocument` and any third-party handler
  answer them without writing code.
- **`KiteDocView(chapterPlaceholder = ...)`** for what a chapter shows while it
  is still being laid out.
- **`EpubDocument.bookmarkOf(href)`**, which turns an internal link into a
  position without laying anything out.
- `KitePageIndicator` marks the total with `~` until the book is complete.

### Notes

- `pageCount` and `pages` still lay out every chapter and still return exactly
  what they did. Use `knownPageCount` with `isComplete` for a running total.
- `KiteDocLayout.Spread` pairs pages by index, so it lays the document out
  fully before composing. It is meant for fixed-layout content anyway.
- Laying out any chapter also reads chapter 1, because the writing mode and the
  hyphenation language are one decision per book and both come from it. That is
  two chapters, never the whole book.
- Byte-level streaming (reading parts of the file instead of all of it) is a
  separate problem. Reading the bytes was never what made a book slow to open:
  it is 0 to 11 ms on the corpus.

---

An API naming pass, earlier in the same cycle. Two things were wrong. The
viewer called itself PDF while serving both formats: the Compose viewer, its
state, its layouts and its widgets were all named `Pdf*` even though every one
of them drives an EPUB exactly as it drives a PDF. And six core types carried bare names that collide
with Compose, Android, Skia and AWT types, which forced aliased imports at
almost every call site.

The prefix now means what it says: `Kite*` for anything that serves both
formats, `Pdf*` and `Epub*` only where the type really is one format, and no
shared type left with a name a host app already uses.

### Breaking changes and migration

Every old name still resolves, deprecated, for this release cycle. Most are
`typealias`es or one-line wrappers, so `ReplaceWith` fixes call sites in the
IDE.

| old | new |
|---|---|
| `PdfView(state, ...)` | `KiteDocView(state, ...)` |
| `PdfView(document = pdf, ...)`, `EpubView(document = book, ...)` | `KiteDocView(document = anyKiteDocument, ...)` |
| `rememberPdfViewState(pdf)`, `rememberEpubViewState(book)` | `rememberKiteDocViewState(anyKiteDocument)` |
| `PdfViewState` | `KiteDocViewState` |
| `PdfViewColors` | `KiteDocViewColors` |
| `PdfLayout` | `KiteDocLayout` |
| `PdfZoomSpec` | `KiteZoomSpec` |
| `PdfRenderSpec` | `KiteRenderSpec` |
| `PdfHighlight` | `KiteHighlight` |
| `PdfMarkerSide` | `KiteMarkerSide` |
| `PdfSelectionMenu`, `PdfSelectionMenuItem`, `PdfSelectionMenuDefaults` | `KiteSelectionMenu`, `KiteSelectionMenuItem`, `KiteSelectionMenuDefaults` |
| `PdfSelectionHandleEdge`, `PdfSelectionHandlePainter`, `PdfSelectionHandleDefaults` | `KiteSelectionHandleEdge`, `KiteSelectionHandlePainter`, `KiteSelectionHandleDefaults` |
| `PdfRasterizer`, `rememberPdfRasterizer()` | `KitePageRasterizer`, `rememberKitePageRasterizer()` |
| `PdfPageIndicator`, `PdfNavigationControls`, `PdfThumbnailStrip`, `PdfOutlinePanel` | `KitePageIndicator`, `KiteNavigationControls`, `KiteThumbnailStrip`, `KiteOutlinePanel` |
| `PdfDocument.outlines` | `PdfDocument.bookmarks` |
| `WrongPasswordException` | `KiteWrongPasswordException` |
| `TrueTypeFont.GlyphOutline.toPdfPath()` | `toKitePath()` |

Six core types also lost bare names that collide with types every consumer
already imports. The codebase itself was the evidence: `Matrix` was imported
`as PdfMatrix` in fifteen files and `BlendMode` `as PdfBlendMode` in six,
purely to dodge the Compose, Android, Skia and AWT types of the same name.
Those aliases are gone now.

| old | new | collided with |
|---|---|---|
| `core.Rectangle`, `core.render.Rectangle` | `core.KiteRectangle` | `java.awt.Rectangle` |
| `core.render.Matrix` | `core.render.KiteMatrix` | `androidx.compose.ui.graphics.Matrix`, `android.graphics.Matrix` |
| `core.render.BlendMode` | `core.render.KiteBlendMode` | Compose, Android and Skia `BlendMode` |
| `core.render.ColorSpace` | `core.render.KiteColorSpace` | Compose and Android `ColorSpace` |
| `core.font.FontFamily` | `core.font.KiteFontFamily` | `androidx.compose.ui.text.font.FontFamily` |
| `core.render.ImageXObject` | `core.render.KiteImageData` | (PDF jargon; EPUB builds these too) |
| `compose.TextSelection` | `compose.KiteTextSelection` | |
| `compose.PageHit` | `compose.KitePageHit` | |

`Rectangle` was reachable from two packages; only `io.github.yuroyami.kitepdf.core.KiteRectangle`
remains. One caveat on the aliases: Kotlin expands a type alias for type
positions, constructors, companion members and enum entries, but not for
nested classifiers, so `ImageXObject.Kind` has to become `KiteImageData.Kind`
by hand.

`KitePDF.open(bytes)` is deprecated. It read as the entry point for the whole
library while only ever returning a `PdfDocument`; use `PdfDocument.open` for a
PDF or `KiteDoc.open` for either format. `KitePDF.VERSION` stays.

Two changes are more than a rename:

- **`onLinkTap` now receives a `KiteLinkAction`, not a `PdfAction`.** An EPUB
  href used to be wrapped in a fabricated `PdfAction.Uri` carrying an empty
  dictionary, so an EPUB-only app had to import PDF types to read a URL back
  out. EPUB links now arrive as `KiteLinkAction.Uri`; PDF links arrive as
  `KiteLinkAction.Pdf` with the parsed `PdfAction` untouched, so nothing is
  lost. Both answer `link.uri`, so opening web links needs no `when`. The
  deprecated `PdfView`/`EpubView` wrappers keep the old callback type.
- **`PdfDocument.outlines` is now `bookmarks`.** It sat one letter away from
  the format-neutral `PdfDocument.outline`, with a different element type.

Also removed, both long past the one cycle they were promised: the
`PdfCanvas`/`PdfPath`/`PdfShading`/`PdfPattern`/`PdfFunction` aliases from
0.2.0, and `IosPdfRasterizer` from 0.0.2 (use `ApplePdfRasterizer`).

`EpubPage.width`/`height` are deprecated in favour of `displayWidth`/
`displayHeight`, which every `KitePage` answers. They were the same numbers
under a name that means something else on `PdfPage`, where `width` is the
`/MediaBox` and ignores `/CropBox` and `/Rotate`.

### Added

- **`KiteDoc`, the format-neutral opener**, in `io.github.yuroyami.kitepdf.document`
  in the `kitepdf` umbrella artifact. `KiteDoc.open(bytes)` reads the format out
  of the bytes and returns a `KiteDocument`, and `KiteDoc.formatOf(bytes)` answers
  `Pdf`, `Epub` or null from the header alone. Both handlers' own `open` are
  unchanged and are still the direct route when you already know the format.
- **More ways in than a byte array.** Every one is a thin adapter that ends in
  the same `open(bytes)`, since the engine has no incremental reader.

  | source | call | targets |
  |---|---|---|
  | Base64 or a `data:` URI | `KiteDoc.openBase64(text)` | all |
  | file path | `KiteDoc.openFile(path)` | JVM, Android, Apple, Linux, Windows, Android NDK |
  | `java.io.File`, `InputStream` | `KiteDoc.open(file)` / `open(stream)` | JVM, Android |
  | Android content `Uri` | `KiteDoc.open(context, uri)` | Android |
  | `NSData`, `NSURL` | `KiteDoc.open(data)` / `open(url)` | Apple |
  | remote URL | `KiteDoc.openUrl(url, client)` | `kitepdf-net` |

  The Base64 reader takes a bare payload or a whole data URI, either alphabet,
  padded or not, and ignores line breaks.
- **`EpubDocument.openFile(path)`** on JVM, Android and Apple, matching the
  `PdfDocument` one that already existed.
- **`io.github.yuroyami:kitepdf-net`**, a new optional artifact: `KiteDoc.openUrl`
  and `KiteDoc.downloadBytes`. It is the only place Ktor enters the build, so
  the engine artifacts keep their kotlin-stdlib + KiteImage dependency set. You
  supply the `HttpClient`, so timeouts, auth, retries and logging stay yours.
  Ktor does not ship for `androidNative*` or `wasmWasi`, so neither does this.
- **`KiteDocView(document = ..., theme = ...)`.** The convenience overload takes
  a reading theme, which used to be reachable only through `EpubView` or by
  building `KiteDocViewColors` by hand. Themes have always worked for PDF too.

### Fixed

- Internal ticket codes (`T-14`, `T-80`, `T-32/T-82`, ...) are gone from the
  KDoc and comments. They meant nothing to anyone reading the published API.
- The Maven descriptions for `kitepdf-compose-viewer` and
  `kitepdf-skia-renderer` said "PDF" only. Both have handled EPUB since 0.2.0.

## [0.6.3] - 2026-08-19

A switch to turn text selection off, for viewers that show a document as a
picture rather than as prose.

### Added

- **`PdfView(selectionEnabled = ...)`**, on both the state-based composable and
  the `PdfView(document = ...)` shorthand. The default, `true`, is exactly the
  behaviour every existing caller already has. `false` removes text selection
  outright: the long-press gesture is not attached, so it cannot compete with
  panning; nothing anchors a selection; no wash and no thumbs are painted; and a
  selection already on screen is dropped, which hands back the pan and scroll
  locks it was holding. Zoom, pan, tap, links and page navigation are untouched.
  Intended for a chart, a scan, a trace, a generated report, where selecting a
  text label means nothing to the reader.

## [0.6.2] - 2026-08-19

Draggable selection thumbs. The two markers bounding a text selection were
paint only, which read as a broken control: they look exactly like the grab
handles every other reader has, and they did nothing.

### Changed

- **The selection thumbs are grab targets.** Pressing within 24.dp of either
  marker drags that end of the selection while the other end stays anchored,
  and hauling one past the other swaps the two ends instead of collapsing the
  selection. The gesture sits in front of the long-press detector and only
  claims a press that landed on a thumb, so long-press selection, tap, pan and
  pinch are unchanged everywhere else; the finger's offset from the boundary is
  captured on grab, so the selection does not jump when a thumb is touched. No
  new API and nothing to enable. A selection still lives on one page.
- The grab region is the boundary line, not whatever a custom
  `PdfSelectionHandlePainter` draws around it, so a replacement marker cannot
  paint itself out of reach. It is measured in screen pixels, which keeps the
  touch target one size at every zoom level while the marker itself keeps
  scaling with the text.

## [0.6.1] - 2026-08-08

Link annotations. A consumer reported that links inside a PDF were neither
visible nor clickable; all three causes below are additive fixes, so this is a
drop-in upgrade from 0.6.0.

### Fixed

- **Rectangle corners are normalised (§7.9.5).** A rectangle array holds two
  *diagonally opposite* corners in **any order**, and the consumer must sort
  them. `Rectangle.fromPdfArray`, `PdfAnnotation`'s `/Rect` and `PdfPage`'s box
  reader all took the four numbers positionally, so a producer writing
  `[x2 y2 x1 y1]` yielded an inside-out box: `width` and `height` went negative.
  A negative-size border painted nothing, and the viewer's containment test
  (`y < bottom || y > top`) could not be satisfied by any point at all, so every
  link on such a page was simultaneously invisible and untappable. The single
  annotation fixture used a well-ordered rect, which is why no test caught it.
  `Rectangle.normalized()` is public for callers holding a rectangle from
  elsewhere.
- **`/Border` and `/BS /W` are honoured (§12.5.4).** A link declaring
  `/Border [0 0 0]` asks for no visible frame, which is what a link styled as
  coloured text wants; the synthesized appearance drew a box around it anyway.
  `/BS` supersedes `/Border`, and an undeclared width still falls back to a
  hairline so nothing that used to be visible disappears.

### Added

- `PdfAnnotation.borderWidth`, the declared width in points, or null when the
  annotation declares neither `/BS /W` nor `/Border`.
- `onTap` and `onLinkTap` on the convenience `PdfView(document = …)` and
  `EpubView(document = …)` overloads. They were only on the state-based
  `PdfView`, so links were permanently inert in the shorthand form: the
  dispatcher reads `onLinkTap?.invoke(action) == true`, and a null callback
  makes that false, which sends the tap on to `onTap` as an ordinary page tap.
  Both default to null, so this is source- and binary-compatible.

## [0.6.0] - 2026-08-06

Thread-safety hardening across the engine, closing every confirmed finding of
a full concurrency audit. No public API changes; two behavioural changes are
called out below.

### Fixed

- The iOS text-cache crash is now fixed at the root instead of narrowed:
  skiko's text stack shares process-global state with the host UI
  thread, which no library lock can exclude. The off-main raster now probes a
  page on the pool with system-font text skipped and, only when that fallback
  engages (EPUB body text, PDFs without embedded outlines), re-renders the
  page on the main dispatcher. Pages whose glyphs all carry embedded outlines
  keep rendering entirely off-main.
- `Standard14Widths` and the predefined-CMap table cache are no longer
  mutable process-global maps: both are immutable maps of per-entry lazies
  built at init, so concurrent renders and main-thread text extraction can
  no longer corrupt them. This also fixes a waste bug where every lookup of
  an unbundled `Uni*` CMap name re-decoded and re-stored a null.
- `PdfDocument`'s cycle guard was rewritten: it now tracks every thread
  parsing an object (not just the first), covers the object-stream decode
  path (a crafted `/ObjStm` whose `/Length` pointed back into itself
  recursed without bound, an uncatchable crash on iOS), releases its claim
  on every exit path (an out-of-bounds xref offset used to leak it), and
  caps resolution depth as a backstop. New `ResolutionGuardTest` covers
  both crafted-file cases on all targets.
- `SvgImage.render` and TrueType glyph parsing are reentrant: the
  destination canvas and the file cursor now travel as parameters instead
  of instance fields, so concurrent renders of the same page or font no
  longer hijack each other's state.
- The glyph outline memo caches on `TrueTypeFont` and `CffFont` are guarded
  by a per-face lock (faces are shared across every `EpubDocument` derived
  from one parse via `withSettings`).
- `KiteLock`'s native spinlock yields after a bounded spin instead of
  busy-waiting forever, so a high-QoS waiter can no longer starve a
  lower-QoS lock holder on Darwin.
- `PdfThumbnailStrip` rasterization is routed through the same guarded
  helper as the main view: a page that fails to rasterize keeps its
  placeholder instead of aborting the host app.
- `KiteWarnings.sink` is `@Volatile`, so installing a sink mid-render is
  safely published to worker threads.
- Switching `PdfView` layouts (Continuous to Paged/Spread and back) now
  keeps the reading position: the incoming layout seeds from the live
  adapter position instead of a value the outgoing layout only publishes
  after composition, which lagged one switch behind.

### Changed

- `PdfImage.rgb`/`gray`/`jpeg`/`jpx` document their array ownership: the
  caller's array is referenced, not copied, and must stay untouched until
  `build()` returns. `rgba` is unaffected.

## [0.5.1] - 2026-08-01

Selection you can feel and see, and margin markers that pick a side. Published
to Maven Local only; the next Maven Central release carries these changes.

### Added

- Selection boundary handles: a caret down the boundary line plus a dot
  beneath it, at the leading edge of the first selected quad and the trailing
  edge of the last. They are indicators rather than drag targets; the
  selection still grows by the long-press drag that created it. Styled by the
  new `PdfViewColors.selectionHandle`, opaque by default where the selection
  wash is translucent, and sized against the boundary line's own height so
  they scale through thumbnails and deep zoom.
- Selection haptics, on every platform with a haptic engine: a long-press
  buzz when the selection anchors and one `TextHandleMove` tick each time the
  selected TEXT changes while dragging. Ticking on text rather than on pixels
  is what keeps a slow drag from rattling.
- `PdfHighlight.edgeMarkerSide` with `PdfMarkerSide.Start`/`End`. `End` (the
  right margin in display space) is the pre-0.5.1 behaviour and stays the
  default; `Start` mirrors the same clamp into the left margin, including the
  refusal to paint when text runs into it.

### Changed

- Nothing breaking. Both additions are defaulted parameters; 0.5.0 call sites
  compile and render identically.

## [0.5.0] - 2026-07-31

Scanned books stop rendering as black pages. An image in a PDF may nominate a
second image as its `/Mask`, the stencil that decides which of its pixels are
allowed to paint, and KitePDF never read that entry. Scanners write school
books that way: a photo of the paper underneath, a near-solid block of black
ink on top, and a stencil in the shape of the letters that lets the ink through.
Without the stencil the ink covered the page, and the only things left visible
were the small figures drawn after it. Twenty-three of the 95 real documents in
the verification corpus were unreadable for that reason and now render.

0.4.0 was published to Maven Local only and never reached Maven Central, so
0.5.0 is the release that carries both it and this fix.

### Added

- `/Mask` in both of its forms (ISO 32000-1 section 8.9.6). A stencil `/Mask`
  is a 1-bit image XObject, coded with JBIG2, CCITT or Flate, whose samples say
  which of the base image's pixels may paint: with the default `/Decode [0 1]`
  a 0 sample paints and a 1 sample is masked out, and `/Decode [1 0]` on the
  mask swaps the two. It is decoded into the same 8-bit alpha plane `/SMask`
  already produces, so both masking forms composite through one tested path.
- Colour-key `/Mask`, the array form: 2 x n bounds tested against the image's
  source samples, before `/Decode` and colour conversion as the specification
  requires, making every pixel that falls inside all of them transparent. The
  ranges are public as `ImageXObject.colorKeyMask`.
- Composite-grid alignment. A stencil is usually finer than the layer it masks:
  a scanned textbook page pairs a 300 dpi stencil with a 75 dpi block of ink.
  The image is resampled up onto the stencil's grid rather than the stencil
  down onto the image's, because the ink layer holds no detail of its own to
  lose while the stencil holds the letter shapes. Beyond a size ceiling, or for
  sample depths other than 8 bits, the mask is instead sampled onto the image's
  grid, which still paints the right pixels, just more coarsely.
- Regression coverage: stencil polarity in both directions, a stencil finer than
  its image and one coarser than it, colour-key masking and a colour-key array
  of the wrong arity, `/SMask` winning over `/Mask`, an undecodable mask and a
  truncated one. A synthetic PDF drives the whole path through the AWT
  rasterizer, so the raster gate needs no corpus file.

### Changed

- An image carrying a `/Mask` is no longer held in the per-document decoded
  image cache. Those are the ink layer of a scan, one use per page, and holding
  a page-sized composite for each page of a book would cost far more than the
  reuse it would save. `/ImageMask` stencils were already treated this way.

### Fixed

- `/SMask` takes precedence when an image carries both masks, which is what the
  specification asks for. Only one of the two is ever resolved.
- A mask that cannot be decoded, is the wrong shape, or declares an absurd size
  leaves its image painted unmasked. A renderer fed untrusted files must degrade
  to the old behaviour there, never blank the page and never throw.

### Measured

JVM suites: 791 tests across the six tested modules, 0 failures (compose-viewer
33, core 95, epub 269, native-renderer 72, pdf 318, skia-renderer 4). The
Compose viewer also compiles for Android, iOS device and simulator, macOS, JS,
and Wasm. On the 95-document verification corpus, the 23 affected books went
from 95 to 100 percent black pixels per page down to the 2 to 10 percent a page
of text should have, and pages were checked against `mutool` renders of the
same files.

## [0.4.0] - 2026-07-31

Three viewer features for apps that annotate what they display. Text selection
now holds the page still instead of competing with pan and scroll, highlights
can each carry their own colour, and a highlight can put a marker in the page
margin so a reader sees that a note exists without reading the page first.

### Added

- `PdfViewState.isSelectionActive`, the explicit signal that text selection owns
  the gesture. It is raised the instant the long press fires, which is before
  `selection` exists, and it survives the finger lifting, so the page also stays
  put while the user reaches for a copy button. `clearSelection()` lowers it,
  and so does a long press that anchored nothing, which keeps a stray press on
  a margin from freezing the viewer.
- `PdfViewState.highlights`, a second overlay channel taking `PdfHighlight`
  entries. Each carries a `KiteSearchHit` plus an optional `color`, so an app
  can paint notes by category. A null colour paints
  `PdfViewColors.searchHighlight`, exactly what the existing channel does, and
  `searchHighlights` itself is unchanged. `KiteSearchHit` deliberately gains no
  colour field; it stays a pure text-search result.
- `PdfHighlight.edgeMarker` and `PdfHighlight.edgeMarkerColor`, a rounded marker
  in the page's right margin, level with the highlighted text. Every dimension
  is a fraction of the rendered page width, so it keeps its proportions in a
  thumbnail and at deep zoom alike, and its inner edge is clamped past the
  highlighted quads so it never paints over the words.
- Regression coverage for all three: the selection lock across a whole gesture
  including the window before a selection object exists, a pointer-driven
  one-finger drag that must not pan a zoomed page under a selection, a
  pointer-driven strip drag that must not scroll under one, per-highlight
  colours against the unchanged default, and the marker's position, its
  clearance from the text and its scaling at 1x and 2x.

### Fixed

- A drag that started as a text selection no longer pans the page underneath
  it. Claiming the drag was never enough on its own: the pinch handler reads its
  pan on the Initial pointer pass, before the selection detector sees anything,
  and `calculatePan` ignores consumption, so both pan sites fired anyway. They
  now gate on `isSelectionActive`. Zoom is untouched, so a two-finger pinch
  still works mid-selection.
- The continuous strip and both pagers no longer scroll under an active
  selection. Suppressing pan alone still let the list carry the page away.

### Changed

- The private per-page overlay renderer is now `Modifier.highlightOverlay`
  rather than `searchHighlightOverlay`, since it paints three channels. No
  public API changed with it.
- Documentation: the Compose viewer guide gains "Highlights" and "Text
  selection" sections covering both channels, the margin marker and the
  selection lock.

### Measured

JVM suites: 778 tests across the six tested modules, 0 failures (compose-viewer
33, core 95, epub 269, native-renderer 68, pdf 309, skia-renderer 4). The
Compose viewer also compiles for Android, iOS device and simulator, macOS, JS,
and Wasm.

## [0.3.1] - 2026-07-26

Compose system-font fallback rendering now keeps the advance widths assigned by
PDF and EPUB layout.

### Fixed

- System-font runs are shaped once and then scaled to the document glyph
  advances. A host substitute font can have wider metrics than the requested
  font. The old renderer painted that wider run without adjustment, which made
  adjacent publisher-serif words collide at larger EPUB text sizes.
- The width correction preserves ligatures, right-to-left shaping, combining
  marks, and non-uniform text-matrix scaling. Invalid or degenerate dimensions
  retain the previous unscaled fallback.

### Added

- Regression coverage for exact metric scaling, invalid dimensions, and two
  adjacent serif runs at 21 and 29 pixels.

### Measured

JVM suites: 772 tests across the six tested modules, 0 failures. The Compose
viewer also compiles for Android, iOS device and simulator, macOS, JS, and Wasm.

## [0.3.0] - 2026-07-25

Rendering correctness and supply chain. Image decoding moves out to KiteImage,
function-based shadings lose their seams, embedded glyph outlines get their
transform order fixed on every canvas, and the differential harness loses every
way it could report a false green.

### Changed

- `:kitepdf-core` decodes images through KiteImage
  (`io.github.yuroyami:kiteimage:0.1.0`), declared `api`. This is the module's
  first runtime dependency beyond `kotlin-stdlib`; everything else in it stays
  stdlib-only. `JpegDecoder`, `PngDecoder`, `GifDecoder`, `JpxDecoder`,
  `Jbig2Decoder` and `MqDecoder` are gone, and `CcittFaxFilter` keeps its
  `PdfDictionary` parameter parsing while delegating the algorithm. All six were
  `internal` in 0.2.0, so no public API was removed, but KiteImage's public API
  now sits on the consumer compile classpath.
- Decoder coverage widens with the move. JPEG gains progressive SOF2, restart
  intervals, 4:1:1 and YCCK. PNG gains 1/2/4-bit depths, the Average and Paeth
  filters, and color-key `tRNS`. GIF gains complete LZW (KwKwK and deferred
  clear) and interlace. `ImageXObject.fromEncodedImage` sniffs the format and
  additionally accepts BMP and JP2 from EPUB and CBZ content.
- Android `compileSdk` moves from 36 to 37 in all eight modules, because Compose
  Multiplatform 1.12.0-beta02 requires it. Consumers of the published Android
  artifacts must compile against API 37 or later. `minSdk` is unchanged: 21 for
  the engine and the Skia renderer, 24 for the Compose viewer, 29 for the native
  renderer.
- Deprecated Kotlin Multiplatform configuration removed:
  `kotlin.mpp.androidSourceSetLayoutVersion` and the `js(IR)` target form.

### Fixed

- Function-based (type 1) shadings no longer show a hairline grid where the
  sampled cells meet. Adjacent cells now overlap by half a device pixel, applied
  only at full alpha under `BlendMode.Normal` with a finite non-singular CTM,
  capped at half a cell, and computed shear-aware so the overlap stays half a
  pixel in device space. The outer domain boundary is unchanged.
- Embedded glyph outlines composed their transform in the wrong order on all
  four canvases (Skia, Android, CoreGraphics and Canvas2D). Scale, then offset,
  then device space is now applied consistently.
- The shading-type dispatch in `paintComplexShading` is exhaustive again. The
  `else -> Unit` catch-all that silently swallowed unhandled types is gone.

### Added

- MuPDF oracle hardening. `pageCountDetailed` and `renderDetailed` return sealed
  results instead of nullables, carrying the reason a call failed. Both enforce a
  60 second timeout with escalating termination, capture the exit code, and
  validate the output rather than trusting a zero exit. Page-count parsing
  tolerates diagnostics printed before the count.
- The differential harness validates `kitepdf.diff.maxpages`, the DPI, the diff
  budget and the corpus path, and reports oracle and comparison failures in
  their own section instead of folding them into the score.
- Regression suites for the paths above: `MuPdfOracleTest`,
  `OracleFailureHandlingTest`, `DifferentialConfigurationTest`, `ImageDiffTest`,
  `EmbeddedGlyphTransformTest` and `FunctionShadingSeamTest`. The
  failure-handling suite pins the three false-green cases: a discovered but
  broken oracle cannot pass as a zero score, a page-geometry mismatch cannot be
  rescaled into a score, and a truncated page count cannot pass with finite
  scores.
- The EPUB PNG test fixture writes real per-chunk CRC-32 and Adler-32 values.
  The previous fixture used dummy values that the old decoder ignored and
  KiteImage rejects. The raster test now decodes the fixture and asserts the
  pixels it produces.

### Documentation

- README rewritten for newcomers, carrying the artifact map that was missing.
  Seven modules are published, the README listed four, and `:kitepdf-core`
  appeared on no page at all. It now documents the trap that breaks a first
  build, where all three renderer modules hold their engine dependency as
  `implementation` while exposing `PdfDocument` and `PdfPage` in their own
  signatures.
- The "no expect/actual" claim is dropped from the README and three docs pages.
  `:kitepdf-core` has three, and the JVM `PlatformFlate` actual is
  `java.util.zip`. Corrected alongside it: the sample app is a JVM entry point
  only, the engine does not compile for every Kotlin target, and the EPUB
  hyphenation language list.
- Shared Kite Dokka theme, `Module.md` for all seven modules, and an
  `mkdocs.yml` matching the other Kite libraries.
- Em dashes removed repo-wide, including 687 from Kotlin comments across 191
  files, verified comment-only.
- A long-standing packaging constraint is now written down: on Android,
  `kitepdf-skia-renderer` resolves `org.jetbrains.skiko:skiko-android`, which
  JetBrains publishes to the Compose dev repository rather than to Maven
  Central, so that repository has to be added. This has been true since the
  module first shipped and is unchanged in 0.3.0. Every other target and every
  other artifact resolves from Maven Central alone. On Android,
  `kitepdf-native-renderer` remains the recommended renderer.

### Build

- The vanniktech publish plugin is declared at the root with `apply false`.
  Applying it only to sibling modules loaded its shared build service under two
  classloaders and left `publishAndReleaseToMavenCentral` unable to configure.
- CI builds a multi-target matrix and publishes the documentation site.
  `kotlin-js-store/yarn.lock` was refreshed so the js job stops failing
  `kotlinStoreYarnLock` on lock drift.

### Measured

Differential run against `mutool` at 96 DPI: 36 pages, 0 render failures, 0 blank
pages, 0 oracle or comparison failures, mean MAE 0.0062 versus MuPDF. JVM suites:
769 tests across the six modules, 0 failures.

## [0.2.0] - 2026-07-11

The multi-format release: the engine becomes a MuPDF-style core + handlers
architecture, gains a complete EPUB reader, closes the PDF completeness gaps
(shadings, Type3, JPX, JBIG2, CJK CMaps, soft masks), and lands the breaking
API cleanup this release exists for.

### Breaking changes and migration

Explicit API mode is enabled everywhere, the format-neutral core types lost
their `Pdf` prefix, and every `:kitepdf-core` package moved under
`io.github.yuroyami.kitepdf.core.*` to eliminate split packages (which broke
JPMS consumers and confused R8). `:kitepdf-pdf` keeps the root package.

| old import | new import |
|---|---|
| `io.github.yuroyami.kitepdf.render.PdfCanvas` | `io.github.yuroyami.kitepdf.core.render.KiteCanvas` |
| `io.github.yuroyami.kitepdf.render.PdfPath` | `io.github.yuroyami.kitepdf.core.render.KitePath` |
| `io.github.yuroyami.kitepdf.render.PdfShading` | `io.github.yuroyami.kitepdf.core.render.KiteShading` |
| `io.github.yuroyami.kitepdf.render.PdfPattern` | `io.github.yuroyami.kitepdf.core.render.KitePattern` |
| `io.github.yuroyami.kitepdf.render.PdfFunction` | `io.github.yuroyami.kitepdf.core.render.KiteFunction` |
| `io.github.yuroyami.kitepdf.render.Matrix` | `io.github.yuroyami.kitepdf.core.render.Matrix` |
| `io.github.yuroyami.kitepdf.Rectangle` | `io.github.yuroyami.kitepdf.core.Rectangle` |
| `io.github.yuroyami.kitepdf.KitePage` (and `KiteDocument`, `KiteMetadata`, `KiteOutlineItem`, `KiteStructuredText`) | `io.github.yuroyami.kitepdf.core.*` |
| `io.github.yuroyami.kitepdf.parser.{Lexer, PdfObject, ...}` (core files) | `io.github.yuroyami.kitepdf.core.parser.*` |
| `io.github.yuroyami.kitepdf.font.*` | `io.github.yuroyami.kitepdf.core.font.*` |
| `io.github.yuroyami.kitepdf.{compression, filters, text}.*` | `io.github.yuroyami.kitepdf.core.{compression, filters, text}.*` |

Deprecated `typealias`es for the five renamed types ship in `:kitepdf-pdf`
(`PdfCanvas = KiteCanvas`, ...) for this release cycle only.

Other breaking changes:

- `EpubDocument.open` now returns a non-null document or throws
  `EpubFormatException` naming the first structural failure; use
  `EpubDocument.openOrNull` (and the new `PdfDocument.openOrNull`) for
  null-on-failure call sites. `PdfFormatException` and `EpubFormatException`
  share the new `KiteFormatException` supertype in core.
- `Parser`, `XrefParser` (pdf) and `LzwFilter`, `Predictors` (core) are now
  `internal`.
- The raw object-model surface (`PdfDocument.xref`/`trailer`/`resolve`,
  `PdfEditor.addObject`/`updateObject`/`allocateReference`/`setTrailerEntry`)
  now requires opting in to `@KiteRawApi` (a warning, not an error: stable
  file format, unstable Kotlin surface).
- `EpubDocument.metadata` was renamed `epubMetadata`; the format-neutral
  `metadata` (title/authors/language/cover) comes from `KiteDocument`.

### Added

- EPUB support: a second document handler, `:kitepdf-epub`, built on the shared
  core and proving the multi-format architecture.
  - Pure-Kotlin reflowable EPUB 2/3 reader: container/OPF parsing, metadata,
    table of contents, and pagination to fixed page sizes.
  - CSS engine: cascade with user-agent, author, and reader origins, selector
    matching including pseudo-classes and sibling combinators, and
    `::before`/`::after` generated content.
  - Box-model layout: block and inline flow, margins/borders/padding, tables,
    `float`/`clear` with exclusion bands, inline images on the baseline, and
    `position: relative`.
  - Typography: per-glyph font fallback, embedded fonts (TrueType,
    OpenType/CFF, WOFF, and WOFF2 via a from-scratch pure-Kotlin Brotli
    decoder), Unicode bidirectional text, Knuth-Liang hyphenation
    with bundled TeX patterns for English, German, French, Spanish, Italian,
    Portuguese, and Dutch, CJK inter-character justification with kinsoku
    line-break rules, ruby annotations, `text-transform`, letter and word
    spacing, and synthesized small-caps.
  - Structured text extraction and search over laid-out pages; anchors,
    internal links, and href-to-page navigation.
  - Reader settings (`EpubSettings`): font family, line-height scale, text and
    background colors, forced justification, and a publisher-CSS toggle,
    applied as a dedicated cascade origin that overrides author `!important`.
- Module taxonomy: the single `:kitepdf` module is split MuPDF-style into
  `:kitepdf-core` (the shared core: geometry, canvas, fonts, images,
  compression, text) and `:kitepdf-pdf` (the PDF handler), joined by
  `:kitepdf-epub`; the renderers are renamed to `:kitepdf-skia-renderer` and
  `:kitepdf-compose-viewer`, and `:kitepdf` remains as an umbrella artifact
  that pulls in everything.
- Pure-Kotlin image codecs in the core: PNG (decoder and encoder), JPEG, GIF,
  and JBIG2, replacing platform-specific decode paths so images render
  identically on every target.
- Robustness hardening against hostile documents: a 512 MiB decompression
  bomb guard across the Flate/LZW/RunLength filters and the PNG, EPUB-zip, and
  WOFF inflate sites, plus content-stream operation budgets (5 million parsed
  operators per stream, 20 million dispatched per page), so crafted inputs
  degrade to truncated output instead of exhausting memory or CPU.
- Custom font embedding in the writer: TrueType (`glyf`) and OpenType/CFF (`.otf`)
  programs are embedded as composite Type0 fonts (Identity-H) with a generated
  `/ToUnicode` map, so emitted text round-trips back to Unicode through the reader.
- From-scratch font subsetting for both formats, keeping only the glyphs a
  document actually draws:
  - TrueType subsetter with `glyf`/`loca` renumbering, a rebuilt SFNT, and a
    `/CIDToGIDMap` stream.
  - CFF subsetter that rewrites the CFF INDEX/DICT/charset/FDSelect structures
    and emits a bare `CIDFontType0C` `/FontFile3`.
  - Subset `/BaseFont` names carry the standard six-letter subset tag.

### Changed

- The renderer seam is format-neutral: `Canvas.drawText` is replaced by
  `Canvas.drawGlyphs`, which takes positioned glyph runs instead of
  PDF-specific text state, so non-PDF handlers drive the same canvas.
- Continuous integration now builds and tests the JVM target on every push and
  pull request, with `mupdf-tools` installed so the differential oracle tests
  run against `mutool` in CI.

### Fixed

- Trust-critical PDF fixes: encryption key authentication, explicit
  wrong-password signalling instead of garbage output, and a redaction leak
  where removed content could survive in the written file.
- Render correctness: page rotation, origin, and crop-box handling; image
  decode fixes; parser error recovery; embedded-glyph advances unified to
  1/1000 em; the default shading-fill path now paints unclipped `sh`
  operations instead of dropping them.
- Font subsystem hardening: CFF Type2 charstring edge cases, CJK CMap
  codespace ranges, and embedded CMap streams.
- EPUB pagination now compiles and runs on non-JVM targets: a JVM-only
  `putIfAbsent` call was replaced with the multiplatform `getOrPut`.
- Oracle tests that previously printed a message and returned early (reported as
  passing) when `mutool` or a test font was absent now use JUnit assumptions, so
  they report as skipped instead of silently green. No real assertion was weakened.

### Added later in the same cycle

- Viewer feature set: engine-level text search with per-page highlight quads,
  viewport hit testing, link taps (PDF link annotations and EPUB hrefs) with
  internal go-to-page handling, outline/TOC panels, text selection with
  long-press drag handles, page thumbnails, RTL reading progression, and
  two-page spreads.
- Format-neutral document seam: `KiteDocument` exposes metadata, outlines,
  structured text, and search for both handlers; `PdfPage.textContent()`
  adapts PDF structured text to it.
- Performance and concurrency: platform zlib fast paths on JVM/Android
  with a dynamic-Huffman pure-Kotlin deflate elsewhere, one glyph-layout pass
  per text run, a per-document decoded-image cache, thread-safe
  `PdfDocument` (concurrent page rendering), lazy `pageCount` from `/Count`,
  off-main-thread rasterization, and a page-bitmap LRU in the viewer. Corpus
  mean render time: 9.7ms/page on the reference machine.
- PDF completeness: text clipping modes 4-7; shading types 1, 4, 5 and
  6/7 (approximated); Type 3 fonts; luminosity soft masks; 47 predefined CJK
  CMaps; complete JBIG2 (MMR, Huffman symbol dictionaries and text regions,
  refinement, pattern/halftone regions); a from-scratch JPEG 2000 (JPX)
  decoder, byte-exact against OpenJPEG on lossless configurations; encrypted
  PDF creation and editing (AES-256/R6 write support in `PdfBuilder.encrypt`
  and `PdfEditor`); vertical writing (tategaki) for EPUB; and a digital
  signature scaffold (`PdfSigner`: prepare, ByteRange, embed; the CMS blob
  comes from the application).
- API cleanup: explicit API mode across all published modules;
  `KitePDF.VERSION` generated from the Gradle version; a `String` password
  overload with the documented UTF-8-then-Latin-1 rule; `KiteWarnings`, a
  process-global warning sink for the lenient salvage paths; CMYK color
  operators in the writer's content builder.
- Test hardening: a deterministic mutation fuzzer (2600 seeded mutants per
  run, wired into every build) and seeded writer round-trip property tests;
  CI now also tests iOS simulator, macOS, and JS(Node) targets on main.

### Fixed later in the same cycle

- A latent AWT soft-mask perf bug (an unclipped surface allocated a
  100-megapixel offscreen buffer per luminosity mask, ~1.1s per page).
- Non-exhaustive shading dispatch on the JS, Apple, and Android native
  canvases (they had not compiled since the shading work landed).
- A glyf-parser crash on fonts with non-monotonic contour end points (found
  by the mutation fuzzer).
- mocha's 2-second default timeout killing slow crypto tests on JS.

## [0.1.0] - 2026-06-17

- Initial public release, published to Maven Central under
  `io.github.yuroyami:kitepdf`.
- Pure-Kotlin PDF engine for Kotlin Multiplatform: parser, renderer, writer,
  editor, encryption, and font handling, callable from `commonMain` and running
  unchanged across Android, iOS, JVM, JS, Wasm, and Kotlin/Native.

[0.3.1]: https://github.com/yuroyami/KitePDF/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/yuroyami/KitePDF/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yuroyami/KitePDF/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/yuroyami/KitePDF/releases/tag/v0.1.0
