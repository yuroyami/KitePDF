# Module kitepdf-xps

XPS and OpenXPS reading through `XpsDocument.open(bytes)` and `XpsPage`.
The umbrella artifact also recognizes these packages as `KiteDocFormat.Xps`.
This source module implements the fixed-page model in
[ECMA-388](https://ecma-international.org/publications-and-standards/standards/ecma-388/),
with Microsoft's 2005 XPS namespace as well as the OpenXPS namespace. It is a
reader and renderer, not a conformance validator or an XPS writer.

The package reader follows the start relationship, document sequence, document
references and page references (§§9, 10). It reads stored/deflated OPC ZIP parts,
including interleaved pieces, resolves package-local relative and absolute URIs,
and preserves reference order. Pages are stable objects with lazily decoded
markup. Dimensions convert XPS units (1/96 inch) to the shared point contract
(1/72 inch). Missing pages retain their position as gray placeholders.

Rendering covers the following parts of the format:

- Canvas nesting, render transforms, clips, opacity groups and opacity masks.
- Abbreviated and expanded path geometry, lines, cubic/quadratic curves, arcs,
  fill rules, solid fills/strokes and dash arrays (§11). Geometry transforms
  change vertices without scaling stroke widths or brush coordinates.
- Glyphs with embedded TrueType or OpenType CFF fonts, TTC face selection,
  GUID-based font deobfuscation, explicit glyph indices, character clusters,
  metric overrides and bidirectional advances (§§9.1.7, 12). Damaged fonts use
  substitute text where Unicode is available. Deobfuscated font bytes stay in
  memory. The shared text API exposes logical Unicode, search and selection.
- Solid sRGB/ARGB and scRGB colours; linear and elliptical radial gradients
  with pad extension and alpha stops; image and visual brushes with viewbox,
  viewport, tiling and flips (§§13, 15). Images use the shared image decoder.
- Local and external package resource dictionaries with lexical shadowing,
  bounded recursive references, a shared page work budget, and resource URIs based on their defining
  dictionary (§§9.1.8, 14). Remote network resources are never fetched.

The implementation deliberately keeps these limits visible:

- Gradient Repeat and Reflect currently use Pad as a salvage fallback.
  Gradient duplicate-stop discontinuities are approximated. ICC profile
  conversion and ContextColor are not implemented; ColorConvertedBitmap uses
  the underlying image decoder without its external colour profile.
- Non-solid stroke brushes are skipped. Different start/end/dash caps and
  triangle caps are approximated by the backend's common cap. Synthetic bold
  and italic styles and sideways vertical metrics are approximations.
- Image support is limited to the shared decoder's formats. JPEG XR is not
  implemented here. Image density is read from PNG pHYs and JPEG JFIF headers;
  other density metadata uses 96 dpi. Backend image interpolation can differ.
- Text boxes approximate ascenders/descenders, rotation and sideways runs.
  Explicit cluster advances are preserved, but there is no StoryFragments
  reading-order model, document outline/link mapping or accessibility tree.
- Print tickets, signatures, rights management, attachments and document
  editing/writing are not implemented. Metadata currently exposes only the
  sequence language. Markup compatibility uses AlternateContent fallback;
  arbitrary extension namespaces are not interpreted.
- Transparency and gradients inherit the chosen canvas backend's capabilities.
  Malformed or unsupported elements are independently skipped or replaced;
  this permissive behavior does not establish conformance. Active visual-resource
  cycles are cut off, and element, dictionary, glyph and tile work share a
  100,000-operation per-page ceiling.

Common tests generate their own packages, fonts and image data. The JVM suite
also paints an actual AWT raster and compares a generated XPS page with MuPDF
when `mutool` is installed (or selected through `MUTOOL`). A missing oracle is
reported as a skipped test. Outputs and the mean RGB error are written under
`kitepdf-xps/build/xps-difftest/`. The generated fixture exercises paths, clips,
ARGB opacity, resources, an obfuscated embedded font, image brushes, a geometry
transform, and a gradient. It is a bounded synthetic check, not a real-world
corpus or platform-wide rendering certification.

```bash
./gradlew :kitepdf-xps:jvmTest :kitepdf:jvmTest
```
