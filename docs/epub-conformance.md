# EPUB conformance

How KitePDF does against the W3C EPUB 3 test suite, [w3c/epub-tests](https://github.com/w3c/epub-tests) at [`54092b42`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403), which holds a test book for each normative statement of [EPUB 3](https://www.w3.org/TR/epub/) and [EPUB Reading Systems 3](https://www.w3.org/TR/epub-rs/). The suite marks each test with the level of its statement: must, should or may, or deprecated for a feature the specifications no longer recommend.

The reading system under test is the library and its viewer together: `kitepdf-epub` reads and lays out the book, `KiteDocView` shows it, pairs spreads and follows links, and `EpubScriptRunner` of `kitepdf-javascript` runs its scripts. The [implementation report](epub-conformance.json) gives each result in the suite's own format, `true`, `false` or `"n/a"`, ready to send to the suite's results page. Each failure of a must or a should names the issue that tracks it.

## Summary

| Level | Tests | Passes | Fails | Not applicable |
|---|---|---|---|---|
| must | 140 | 118 | 18 | 4 |
| should | 38 | 26 | 11 | 1 |
| may | 1 | 0 | 1 | 0 |
| deprecated | 27 | 25 | 1 | 1 |
| all | 206 | 169 | 31 | 6 |

## How the results are known

`EpubConformanceTest` in `kitepdf-compose-viewer` packs each test book from the suite and opens it as a reader does. Where the result shows in KitePDF's API, a check decides it: the text of a page, its size, its page count, what it paints on a recording canvas, the rendition of a chapter, the table of contents, the spreads the viewer pairs, the outcome of the scripts. Those rows say **Checked**, with what the check asserts, and the test fails when a check and the report part, so a change that moves a result has to move the report too.

The rest need a person: whether a line breaks before a given character, or how read-aloud behaves while it plays. Those rows say **Judged**, with what was seen on the page or read in the code. A test is not applicable when it does not test a reading system, such as one that asks EPUBCheck to report an error, or when it says so itself for a reading system without the feature it tests.

CI checks the suite out at the pinned commit and runs the test on every push. To run it locally, point it at a checkout:

```bash
git clone https://github.com/w3c/epub-tests ../epub-tests
git -C ../epub-tests checkout 54092b4233253e9aac80e93ec4782b380b4b3403
KITEPDF_EPUB_TESTS=$PWD/../epub-tests/tests ./gradlew :kitepdf-compose-viewer:jvmTest --tests "*EpubConformanceTest*"
```

## Open Container Format

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`ocf-font_obfuscation`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-font_obfuscation) | should | Passes | Checked: The quote draws in the obfuscated font, with glyph outlines from the font the book embeds. |
| [`ocf-font_obfuscation_bis`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-font_obfuscation_bis) | should | Passes | Checked: A font obfuscated with the wrong key does not load, so no glyph run takes its outlines from it. |
| [`ocf-metainf-inc`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-metainf-inc) | must | Passes | Checked: The book opens and its page shows the pass sentence. |
| [`ocf-metainf-manifest`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-metainf-manifest) | must | Passes | Checked: The spine is the one chapter of the package, and no page shows the fail sentence. |
| [`ocf-package_arbitrary`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-package_arbitrary) | must | Passes | Checked: The package at FOO/BAR opens and its page shows the pass sentence and not the fail one. |
| [`ocf-package_multiple`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-package_multiple) | must | Passes | Checked: The first rendition, at FOO/BAR, opens and its page shows the pass sentence and not the fail one. |
| [`ocf-url_link-leaking-relative`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_link-leaking-relative) | must | Passes | Checked: The image whose path climbs past the root draws, 1000 by 562 pixels. |
| [`ocf-url_link-path-absolute`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_link-path-absolute) | must | Passes | Checked: The image with a path from the root draws, 1000 by 562 pixels. |
| [`ocf-url_link-relative`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_link-relative) | must | Passes | Checked: The image with a relative path draws, 1000 by 562 pixels. |
| [`ocf-url_manifest`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_manifest) | must | Passes | Checked: The chapter at EPUB/foo opens and shows the pass sentence. |
| [`ocf-url_origin`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_origin) | must | Fails ([#521](https://github.com/yuroyami/KitePDF/issues/521)) | Checked: Two openings of the book, standing for two readers, show different origins; today both show the one origin hashed from the identifier. |
| [`ocf-url_parse-leaking-relative`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_parse-leaking-relative) | must | Passes | Checked: After the scripts run, the page shows the image address that `URL` resolves under the book's origin, which stays at the root of the book however many `..` segments climb above it. |
| [`ocf-url_parse-path-absolute`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_parse-path-absolute) | must | Passes | Checked: After the scripts run, the page shows the path-absolute address that `URL` resolves under the book's origin, from the root of the book. |
| [`ocf-url_relative`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-url_relative) | must | Passes | Checked: The chapter at foo/BAR/qux opens and shows the pass sentence. |
| [`ocf-zip-comp`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-zip-comp) | must | Passes | Checked: The test book re-packed with its entries marked as bzip2 does not open: `EpubDocument.open` throws `EpubFormatException`. The folder itself can only hold Deflate. |
| [`ocf-zip-mult`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/ocf-zip-mult) | must | Passes | Checked: The test book whose end record says it is the second segment of a split archive does not open: `EpubDocument.open` throws `EpubFormatException`. |

## Package Documents

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`pkg-collections-unknown`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-collections-unknown) | must | Passes | Checked: The book opens with its own title, not the unknown collection's, and shows the pass sentence. |
| [`pkg-creator-order`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-creator-order) | must | Passes | Checked: `epubMetadata.creators` lists the five creators in package order. |
| [`pkg-linked-records`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-linked-records) | should | Not applicable | Judged: Linked metadata records are not read, and the test applies only to a reading system that reads them. The title and creator come from the package. |
| [`pkg-manifest-unknown`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-manifest-unknown) | must | Passes | Checked: The book opens and shows the pass sentence. |
| [`pkg-manifest-unlisted-resource`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-manifest-unlisted-resource) | should | Fails ([#516](https://github.com/yuroyami/KitePDF/issues/516)) | Checked: The image that the manifest does not list does not draw; today it does. |
| [`pkg-meta-unknown`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-meta-unknown) | must | Passes | Checked: The book opens with its own title, not the unknown meta's, and shows the pass sentence. |
| [`pkg-meta-whitespace`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-meta-whitespace) | must | Passes | Checked: The creator reads "Dave Cramer" with one space, and the title reads as written. |
| [`pkg-spine-duplicate-item-hyperlink`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-duplicate-item-hyperlink) | must | Passes | Checked: The link to the repeated document goes to its first place in the spine. |
| [`pkg-spine-duplicate-item-rendering`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-duplicate-item-rendering) | must | Passes | Checked: The document listed three times in the spine shows three times, one page each. |
| [`pkg-spine-duplicate-item-ui`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-duplicate-item-ui) | must | Passes | Checked: Each of the three places of the repeated document has a bookmark of its own chapter. |
| [`pkg-spine-nonlinear-activation`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-nonlinear-activation) | must | Passes | Checked: The link to the non-linear document goes to it, and it shows the pass sentence. |
| [`pkg-spine-order`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-order) | must | Passes | Checked: The chapters follow the spine, whatever the file names. |
| [`pkg-spine-order-svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-order-svg) | must | Passes | Checked: The four SVG chapters follow the spine and draw "Page 1" to "Page 4". |
| [`pkg-spine-unknown`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-unknown) | must | Passes | Checked: The book opens and shows the pass sentence. |
| [`pkg-title-order`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-title-order) | must | Passes | Checked: The title is the first `dc:title` of the package. |
| [`pkg-unique-id`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-unique-id) | must | Passes | Checked: The two books that share an identifier open as two books, each with its own title. |
| [`pkg-unique-id_duplicate`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-unique-id_duplicate) | must | Passes | Checked: The two books that share an identifier open as two books, each with its own title. |
| [`pkg-version-backward`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-version-backward) | must | Passes | Checked: A package with version 0 opens and shows the pass sentence. |

## Publication Resources

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`pub-data-urls_browsing-context`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-data-urls_browsing-context) | must | Passes | Checked: The image whose source is a `data:` URL draws. |
| [`pub-data-urls_top-level-content`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-data-urls_top-level-content) | must | Passes | Checked: The fixed-layout page draws the SVG of its `data:` URL in place, and the book keeps its two pages. |
| [`pub-external-links`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-external-links) | should | Fails ([#519](https://github.com/yuroyami/KitePDF/issues/519)) | Judged: `KiteDocView` hands the web link to `onLinkTap` and does nothing more; it neither asks the reader nor opens a browser. |
| [`pub-external-links_consent`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-external-links_consent) | should | Fails ([#519](https://github.com/yuroyami/KitePDF/issues/519)) | Judged: `KiteDocView` hands the mail link to `onLinkTap` and does nothing more; it neither asks the reader nor opens a mail app. |
| [`pub-file-urls`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-file-urls) | must | Passes | Checked: The three iframes point at `file:` URLs, none of which the book can read, and their boxes stay empty. |
| [`pub-xml-external-id`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-xml-external-id) | must | Passes | Checked: No page shows the fail sentence: the external entity is not resolved, and stands for nothing, and the DOCTYPE's internal subset leaves nothing of itself on the page. |
| [`pub-xml-names`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-xml-names) | must | Fails ([#517](https://github.com/yuroyami/KitePDF/issues/517)) | Judged: The XHTML parse is lenient: the invalid element name lays out and no error is reported. |
| [`pub-xml-non-validating_comment`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-xml-non-validating_comment) | must | Passes | Checked: The spine holds the two chapters, and the commented-out item is not one of them. |
| [`pub-xml-non-validating_unclosed`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-xml-non-validating_unclosed) | must | Fails ([#517](https://github.com/yuroyami/KitePDF/issues/517)) | Judged: The XHTML parse is lenient: the unclosed paragraph lays out and no error is reported. |
| [`sec-untrusted-consent_network`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/sec-untrusted-consent_network) | should | Passes | Checked: Opened with the default settings, the page fetches nothing: no image draws, nothing arrives from the network, and the remote stylesheet does not turn the text red. Fetching starts only when the host app passes a fetcher, which is where the reader consents. |
| [`sec-untrusted-consent_scripting`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/sec-untrusted-consent_scripting) | should | Passes | Checked: Without `EpubScriptRunner` the page still says "No script has been executed.": scripts run only when the host app passes a runner, which is where the reader consents. |

## Core Media Types

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`pub-cmt-avif`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-avif) | must | Fails ([#513](https://github.com/yuroyami/KitePDF/issues/513)) | Checked: The AVIF draws; today it does not decode. |
| [`pub-cmt-gif`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-gif) | must | Passes | Checked: The GIF draws. |
| [`pub-cmt-jpeg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-jpeg) | must | Passes | Checked: The JPEG draws. |
| [`pub-cmt-jxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-jxl) | must | Fails ([#513](https://github.com/yuroyami/KitePDF/issues/513)) | Checked: The JPEG XL draws; today it does not decode. |
| [`pub-cmt-mp3`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-mp3) | must | Passes | Checked: The page offers the MP3 as audio with controls, which `kitepdf-media` plays on Android, iOS and the desktop JVM. |
| [`pub-cmt-mp4`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-mp4) | must | Passes | Checked: The page offers the M4A as audio with controls, which `kitepdf-media` plays on Android, iOS and the desktop JVM. |
| [`pub-cmt-opus`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-opus) | must | Passes | Checked: The page offers the Opus file as audio with controls, which `kitepdf-media` plays on Android, iOS and the desktop JVM. |
| [`pub-cmt-png`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-png) | must | Passes | Checked: The PNG draws. |
| [`pub-cmt-svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-svg) | must | Passes | Checked: The SVG image draws its red heart. |
| [`pub-cmt-webp`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-cmt-webp) | must | Fails ([#513](https://github.com/yuroyami/KitePDF/issues/513)) | Checked: The WebP draws; today a lossy WebP does not decode. |

## Manifest Fallbacks

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`pub-foreign_bad-fallback`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-foreign_bad-fallback) | must | Passes | Checked: The book, whose only spine item has no content document in its fallback chain and no markup in its bytes, is refused with a format error, as the test allows. |
| [`pub-foreign_image`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-foreign_image) | must | Passes | Checked: The PSD image falls back to its PNG, which draws. |
| [`pub-foreign_json-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-foreign_json-spine) | must | Passes | Checked: The JSON spine item falls back to its XHTML document, which shows the pass sentence. |
| [`pub-foreign_xml-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-foreign_xml-spine) | must | Passes | Checked: The XML spine item falls back to its XHTML document, which shows the pass sentence. |
| [`pub-foreign_xml-suffix-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pub-foreign_xml-suffix-spine) | must | Passes | Checked: The `+xml` spine item falls back to its XHTML document, which shows the pass sentence. |

## Content Documents

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`cnt-css-fonts_ot`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-css-fonts_ot) | must | Passes | Checked: The item draws in the OpenType font of its `@font-face`, with glyph outlines from the book. |
| [`cnt-css-fonts_tt`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-css-fonts_tt) | must | Passes | Checked: The item draws in the TrueType font of its `@font-face`, with glyph outlines from the book. |
| [`cnt-css-fonts_woff`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-css-fonts_woff) | must | Passes | Checked: The item draws in the WOFF font of its `@font-face`, with glyph outlines from the book. |
| [`cnt-css-fonts_woff2`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-css-fonts_woff2) | must | Passes | Checked: The item draws in the WOFF2 font of its `@font-face`, with glyph outlines from the book. |
| [`cnt-mathml-support`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-mathml-support) | must | Passes | Checked: The equation draws as MathML: the exponent 2 is smaller than the base and raised above it. |
| [`cnt-svg-css`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-svg-css) | must | Passes | Checked: The SVG chapter fills its shapes with the star pattern of its CSS. |
| [`cnt-svg-css-inclusion`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-svg-css-inclusion) | must | Passes | Checked: The inline SVG fills with the star pattern of the document's CSS. |
| [`cnt-svg-css-reference`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-svg-css-reference) | must | Passes | Checked: The SVG referenced by `img` stays solid green: the document's CSS does not reach it. |
| [`cnt-svg-embedded`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-svg-embedded) | must | Passes | Checked: The inline SVG draws its red heart. |
| [`cnt-svg-support`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-svg-support) | must | Passes | Checked: The SVG chapter draws its red heart and its text. |
| [`cnt-xhtml-support`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/cnt-xhtml-support) | must | Passes | Checked: The XHTML chapter shows its pass sentence. |
| [`css-epub-hyphens`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-hyphens) | should | Passes | Judged: `-epub-hyphens` is read: `none` never hyphenates, `manual` breaks at soft hyphens and `auto` hyphenates by the language's patterns. At the test's page width no line happens to end on a break point, so this is judged from the code and narrower pages. |
| [`css-epub-line-break`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-line-break) | should | Passes | Checked: Over many widths of the page, no line runs past its edge, `auto` and `strict` start no line with 々 or ぁ, `normal` starts some with ぁ and none with 々, and `loose` starts some with each. |
| [`css-epub-text-align-last`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-text-align-last) | should | Passes | Checked: The last line of each sample sits where its `-epub-text-align-last` puts it: at the left edge for `auto`, `start`, `left` and `end` in right-to-left text, at the right edge for `right`, centred for `center`, and across the line for `justify`. |
| [`css-epub-text-combine-horizontal`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-text-combine-horizontal) | should | Fails ([#508](https://github.com/yuroyami/KitePDF/issues/508)) | Judged: `-epub-text-combine-horizontal` is not read: the digits stay one sideways run in the vertical column. |
| [`css-epub-text-emphasis`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-text-emphasis) | should | Passes | Checked: The first chapter draws over its text each mark its style sheet asks for, from the book's font where the font has it, a blue one for test 12 and one under the text for test 13, and the vertical chapters draw them left of the first column and then right of it. Tests 8 to 11 name other marks than their style sheet asks for; the check follows the style sheet. |
| [`css-epub-text-orientation`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-text-orientation) | should | Passes | Checked: The test's second chapter lays out vertically: the mixed paragraph stands its Japanese up and turns its English, the upright one stands its English letters up, and the sideways ones turn their Japanese too. |
| [`css-epub-text-transform`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-text-transform) | should | Passes | Checked: The digits and letters of the sample read as their full-width forms and stand upright in the vertical chapter, as the Japanese beside them does. |
| [`css-epub-text-underline-position`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-text-underline-position) | should | Passes | Checked: The first chapter's underline sits under the baseline and above the 0.2 em that descenders reach, the second chapter's sits below them, and the vertical chapters draw it left of the first column and then right of it. |
| [`css-epub-word-break`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-word-break) | should | Passes | Checked: The `normal` and `keep-all` samples keep every word whole, and the `break-all` sample breaks inside words and fills each line to its edge. |
| [`css-epub-writing-mode`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/css-epub-writing-mode) | should | Passes | Checked: Chapter 1 lays out horizontally and chapters 2 and 3 vertically. |

## Internationalization

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`pkg-dir-auto_root-rtl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir-auto_root-rtl) | must | Passes | Checked: `epubMetadata.titleRightToLeft` is false, since the title's own `auto` goes by its first strong character, the Latin C of "CSS:", over the package's `rtl`, so the title displays incorrectly as the test expects. |
| [`pkg-dir-auto_root-unset`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir-auto_root-unset) | must | Passes | Checked: `epubMetadata.titleRightToLeft` is false, since the title's `auto` goes by its first strong character, the Latin C of "CSS:", so the title displays incorrectly as the test expects. |
| [`pkg-dir_but_not_content`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir_but_not_content) | must | Passes | Checked: The list reads left to right: the package's `dir` does not reach the content. |
| [`pkg-dir_creator-rtl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir_creator-rtl) | must | Passes | Checked: `epubMetadata.creatorsRightToLeft` says the one creator reads right to left by its own `dir`. |
| [`pkg-dir_rtl-root-ltr`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir_rtl-root-ltr) | must | Passes | Checked: `epubMetadata.titleRightToLeft` is true, since the title's own `rtl` wins over the package's `ltr`. |
| [`pkg-dir_rtl-root-unset`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir_rtl-root-unset) | must | Passes | Checked: `epubMetadata.titleRightToLeft` is true by the title's own `rtl`. |
| [`pkg-dir_unset-root-rtl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir_unset-root-rtl) | must | Passes | Checked: `epubMetadata.titleRightToLeft` is true by the package's `rtl`, which the title inherits. |
| [`pkg-dir_unset-root-unset`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-dir_unset-root-unset) | must | Passes | Checked: `epubMetadata.titleRightToLeft` is false, since with no `dir` anywhere the title goes by its first strong character, the Latin C of "CSS:", so it displays incorrectly as the test expects. |
| [`pkg-lang_but_not_content`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-lang_but_not_content) | must | Passes | Checked: The `q` element shows English quotation marks, not the French ones of the package language. |
| [`pkg-spine-progression-default`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-progression-default) | must | Passes | Checked: The book of Arabic language with a default spine progresses right to left. |
| [`pkg-spine-progression-pre-paginated`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-progression-pre-paginated) | must | Passes | Checked: The pages progress left to right and pair into spreads with the first page on the left. |
| [`pkg-spine-progression_ltr`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-progression_ltr) | must | Passes | Checked: The pages progress left to right, paired with the first page on the left. |
| [`pkg-spine-progression_rtl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pkg-spine-progression_rtl) | must | Passes | Checked: The book progresses right to left, and the viewer pairs its pages that way. |

## Navigation Documents

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`nav-access`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-access) | must | Passes | Checked: The table of contents has the one entry, which the outline panel lists. |
| [`nav-activation`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-activation) | must | Passes | Checked: The entry for the second document goes to it, and it shows the pass sentence. |
| [`nav-non-text_img`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-non-text_img) | should | Passes | Checked: The entry made of an image takes the image's `alt` as its label. |
| [`nav-non-text_img_title`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-non-text_img_title) | should | Passes | Checked: The entry made of an image takes its `alt`, not its `title`, as its label. |
| [`nav-spine_in-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-spine_in-spine) | must | Passes | Checked: The navigation document in the spine shows both links, and the table of contents has both entries. |
| [`nav-spine_in-spine-hidden-toc-css`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-spine_in-spine-hidden-toc-css) | must | Passes | Checked: The table of contents has both entries, while the page hides the second as its CSS says. |
| [`nav-spine_in-spine-hidden-toc-html`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-spine_in-spine-hidden-toc-html) | must | Passes | Checked: The table of contents has both entries, while the page hides the second as its `hidden` says. |
| [`nav-spine_in-spine-no-list-style`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-spine_in-spine-no-list-style) | must | Passes | Checked: The outline lists the two titles alone, with no number or letter before them. |
| [`nav-spine_not-in-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/nav-spine_not-in-spine) | must | Passes | Checked: The table of contents has both entries, and the navigation document is not a chapter. |

## Structural Semantics

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`pss-support`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pss-support) | may | Fails | Judged: The note reference and its footnote are found (`linkTarget`), but `KiteDocView` shows no popup of its own; the app builds one from `onLinkTap`. |
| [`pss-support_ignore-title`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/pss-support_ignore-title) | must | Passes | Checked: A link to the head's `title` is not a footnote, while the note in the body is one. |

## Scripting

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`scr-not-support_ccscript-modify-host`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-not-support_ccscript-modify-host) | must | Fails ([#528](https://github.com/yuroyami/KitePDF/issues/528)) | Checked: The iframe's box draws its document; today the box is empty. |
| [`scr-not-support_ccscript-modify-size`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-not-support_ccscript-modify-size) | must | Fails ([#528](https://github.com/yuroyami/KitePDF/issues/528)) | Checked: The iframe's box draws its document; today the box is empty. |
| [`scr-readingsystem-features`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-readingsystem-features) | must | Fails ([KiteJS#11](https://github.com/yuroyami/KiteJS/issues/11)) | Checked: After the scripts run, the page lists each feature `hasFeature` answers; today the script does not parse, for `const` in a `for` head. |
| [`scr-readingsystem-support`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-readingsystem-support) | must | Passes | Checked: After the scripts run, the page says the reading system implements `epubReadingSystem`. |
| [`scr-readingsystem-support_iframe`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-readingsystem-support_iframe) | must | Fails ([#528](https://github.com/yuroyami/KitePDF/issues/528)) | Checked: The iframe's box draws its document, whose script finds `epubReadingSystem`; today the box is empty. |
| [`scr-readingsystem-support_iframe_svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-readingsystem-support_iframe_svg) | must | Fails ([#528](https://github.com/yuroyami/KitePDF/issues/528)) | Checked: The iframe's box draws its SVG document, whose script finds `epubReadingSystem`; today the box is empty. |
| [`scr-readingsystem-support_svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-readingsystem-support_svg) | must | Passes | Checked: After the scripts run in the SVG chapter, it draws the pass text about `epubReadingSystem`. |
| [`scr-storage-delete`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-storage-delete) | should | Passes | Checked: Opened a second time, the page says persistent data is not kept, so the test passes: local storage lives as long as the session. |
| [`scr-support`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support) | should | Passes | Checked: After the scripts run, the page says scripting is supported and the test passes. |
| [`scr-support-fallback`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support-fallback) | must | Not applicable | Judged: Scripts run, so the page itself says its manifest fallback is unused and the test does not apply. |
| [`scr-support_iframe`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support_iframe) | should | Fails ([#528](https://github.com/yuroyami/KitePDF/issues/528)) | Checked: The iframe's box draws its document, whose script says scripting works; today the box is empty. |
| [`scr-support_origin`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support_origin) | must | Passes | Checked: After the scripts run, both chapters show the same origin, `epub://` and a host. |
| [`scr-support_origin_unique`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support_origin_unique) | must | Passes | Checked: After the scripts run, the book shows an origin other than `scr-support_origin`'s. |
| [`scr-support_scrolled-continuous`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support_scrolled-continuous) | should | Fails ([#505](https://github.com/yuroyami/KitePDF/issues/505)) | Checked: With `flow scrolled-continuous`, a chapter is one scrolling column; today it is cut into pages. |
| [`scr-support_scrolled-doc`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support_scrolled-doc) | must | Fails ([#505](https://github.com/yuroyami/KitePDF/issues/505)) | Checked: With `flow scrolled-doc`, a chapter is one scrolling column; today it is cut into pages. |
| [`scr-support_svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/scr-support_svg) | should | Passes | Checked: After the scripts run in the SVG chapter, it draws the pass text and not the fail text. |

## Fixed Layout

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`lay-page-layout-both`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-page-layout-both) | should | Passes | Checked: The reflowable chapter flows over pages between the fixed ones, each one page of its viewport. |
| [`lay-page-layout-both-spread`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-page-layout-both-spread) | must | Passes | Checked: The last page, which asks for the left, sits alone in the left half of its spread. |
| [`lay-pkg-flow-paginated`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pkg-flow-paginated) | should | Passes | Checked: With `flow paginated`, the chapters are cut into pages. |
| [`lay-pkg-flow-scrolled-continuous`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pkg-flow-scrolled-continuous) | should | Fails ([#505](https://github.com/yuroyami/KitePDF/issues/505)) | Checked: With `flow scrolled-continuous`, a chapter is one scrolling column; today it is cut into pages. |
| [`lay-pkg-flow-scrolled-doc`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pkg-flow-scrolled-doc) | should | Fails ([#505](https://github.com/yuroyami/KitePDF/issues/505)) | Checked: With `flow scrolled-doc`, a chapter is one scrolling column; today it is cut into pages. |
| [`lay-reflow-align-x-center`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-reflow-align-x-center) | must | Not applicable | Judged: `rendition:align-x-center` is not supported, and the test applies only to a reading system that supports it. |
| [`lay-rendition-flow-pre-pag`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-rendition-flow-pre-pag) | must | Passes | Checked: The fixed chapters of a scrolled book are one page each of their viewport. |
| [`lay-viewport-meta-prop`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-viewport-meta-prop) | must | Passes | Checked: All four pages take the same 900 by 600 viewport whatever their scale keys say; the viewer's zoom follows its own settings. |

## Pre-paginated Layout

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`fxl-layout-duplication`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/fxl-layout-duplication) | deprecated | Not applicable | Judged: Only EPUBCheck can pass it, by reporting the duplicated `rendition:layout`. KitePDF keeps the first value. |
| [`fxl-page-spread-break`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/fxl-page-spread-break) | deprecated | Passes | Checked: Each chapter is one 900 by 600 page despite `page-break-before`, and the viewer pairs them two by two. |
| [`fxl-page-spread-center`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/fxl-page-spread-center) | deprecated | Passes | Checked: The first chapter asks for the centre, and the viewer gives it a spread of its own, drawn centred. |
| [`fxl-spine-overrides_behave-as-global`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/fxl-spine-overrides_behave-as-global) | deprecated | Passes | Checked: The fixed chapter is one page of its viewport and the overriding chapter flows over pages. |
| [`fxl-spine-overrides_behave-as-global-bis`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/fxl-spine-overrides_behave-as-global-bis) | deprecated | Passes | Checked: The overriding chapter is one fixed page of its viewport between two reflowable ones. |
| [`fxl-spine-overrides_duplicate`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/fxl-spine-overrides_duplicate) | deprecated | Passes | Checked: Of the two overrides on the itemref, the first, reflowable, wins. |
| [`lay-fxl-layout-default`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-layout-default) | deprecated | Passes | Checked: With no `rendition:layout`, the book is reflowable and flows over pages. |
| [`lay-fxl-layout-pre-paginated`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-layout-pre-paginated) | deprecated | Passes | Checked: Each chapter is one 900 by 600 page, in spine order. |
| [`lay-fxl-layout-pre-paginated-spreads`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-layout-pre-paginated-spreads) | deprecated | Passes | Checked: The viewer pairs the four pages into two spreads with no lone page between. |
| [`lay-fxl-orientation-default`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-orientation-default) | deprecated | Passes | Checked: Every chapter reads orientation auto, and the viewer follows the device. |
| [`lay-fxl-orientation-landscape`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-orientation-landscape) | deprecated | Fails | Judged: `rendition:orientation landscape` is read, but `KiteDocView` neither rotates nor tells the reader. |
| [`lay-fxl-page-spread-combined`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-page-spread-combined) | deprecated | Passes | Checked: The left and right pages share one spread, the left one first. |
| [`lay-fxl-page-spread-left`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-page-spread-left) | deprecated | Passes | Checked: The `page-spread-left` page opens its spread on the left. |
| [`lay-fxl-page-spread-right`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-page-spread-right) | deprecated | Passes | Checked: The `page-spread-right` first page sits alone in the right half of its spread, and the pages after it pair. |
| [`lay-fxl-spread-auto`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-spread-auto) | deprecated | Passes | Checked: With `spread auto`, the viewer pairs the pages. |
| [`lay-fxl-spread-both`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-spread-both) | deprecated | Passes | Checked: With `spread both`, the viewer pairs the pages in landscape and in portrait. |
| [`lay-fxl-spread-default`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-spread-default) | deprecated | Passes | Checked: With no `rendition:spread`, the value is auto and the viewer pairs the pages. |
| [`lay-fxl-spread-landscape`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-spread-landscape) | deprecated | Passes | Checked: With `spread landscape`, the viewer pairs the pages in landscape only. |
| [`lay-fxl-spread-none`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-spread-none) | deprecated | Passes | Checked: With `spread none`, every page shows alone. |
| [`lay-fxl-svg-icb_multi`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-svg-icb_multi) | deprecated | Passes | Checked: Each SVG page takes the size of its own `viewBox`, 900 by 600 and 1200 by 600. |
| [`lay-fxl-xhtml-icb`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb) | deprecated | Passes | Checked: The page is its 900 by 600 pixel viewport, and the grid of layered gradients paints over the red box, in 18 by 12 cells. |
| [`lay-fxl-xhtml-icb_device_sizes`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb_device_sizes) | deprecated | Passes | Checked: The grid of layered gradients paints over the red box. `device-width` and `device-height` give the page the size of the reader's, so it fills the display. |
| [`lay-fxl-xhtml-icb_invalid_meta`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb_invalid_meta) | deprecated | Passes | Checked: Width and height are read from the invalid tag, so the second page is 900 by 600 pixels too, and both paint the grid of layered gradients over the red box. |
| [`lay-fxl-xhtml-icb_multi`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb_multi) | deprecated | Passes | Checked: Each page takes its own viewport, 900 and 1400 pixels wide, and both paint the grid of layered gradients over the red box, which shows to the right of it on the wide page. |
| [`lay-fxl-xhtml-icb_multi_declarations`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb_multi_declarations) | deprecated | Passes | Checked: Of two viewport tags, the first sizes the page, 1000 by 600. |
| [`lay-fxl-xhtml-icb_repeated-in-meta`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb_repeated-in-meta) | deprecated | Passes | Checked: Of a repeated `width` and `height`, the first sizes the page, 900 by 600. |
| [`lay-fxl-xhtml-icb_units`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-fxl-xhtml-icb_units) | deprecated | Passes | Checked: `width=1000px, height=600mm` sizes the page 1000 by 600. |
| [`lay-pp-embedded-images`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-embedded-images) | must | Passes | Checked: Each of the nine chapters is one page of its viewport that draws its image, and the title page asks for the centre. |
| [`lay-pp-embedded-images-svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-embedded-images-svg) | must | Passes | Checked: The XHTML title and the SVG plates are one page each of their viewport, each drawing its image. |
| [`lay-pp-images-in-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-images-in-spine) | must | Passes | Checked: Each image spine item shows its XHTML fallback, as the spine requires; showing the image itself is optional. |
| [`lay-pp-images-mixed`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-images-mixed) | must | Passes | Checked: The plates draw their images, and the two image spine items show their XHTML fallbacks. |
| [`lay-pp-layout-default`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-layout-default) | must | Passes | Checked: With no `rendition:layout`, the book is reflowable and flows over pages. |
| [`lay-pp-layout-duplication`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-layout-duplication) | must | Not applicable | Judged: Only EPUBCheck can pass it, by reporting the duplicated `rendition:layout`. KitePDF keeps the first value. |
| [`lay-pp-layout-pre-paginated`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-layout-pre-paginated) | must | Passes | Checked: Each chapter is one 900 by 600 page, in spine order. |
| [`lay-pp-layout-pre-paginated-spreads`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-layout-pre-paginated-spreads) | must | Passes | Checked: The viewer pairs the four pages into two spreads with no lone page between. |
| [`lay-pp-page-spread-combined`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-page-spread-combined) | should | Passes | Checked: The left and right pages share one spread, the left one first. |
| [`lay-pp-page-spread-left`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-page-spread-left) | should | Passes | Checked: The `page-spread-left` page opens its spread on the left. |
| [`lay-pp-page-spread-right`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-page-spread-right) | should | Passes | Checked: The `page-spread-right` first page sits alone in the right half of its spread, and the pages after it pair. |
| [`lay-pp-spine-overrides_behave-as-global`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spine-overrides_behave-as-global) | must | Passes | Checked: The fixed chapter is one page of its viewport and the overriding chapter flows over pages. |
| [`lay-pp-spine-overrides_behave-as-global-bis`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spine-overrides_behave-as-global-bis) | must | Passes | Checked: The overriding chapter is one fixed page of its viewport between two reflowable ones. |
| [`lay-pp-spine-overrides_image-only-pp`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spine-overrides_image-only-pp) | must | Passes | Checked: The fixed chapter is one page that draws its image, and the overriding chapter flows over pages. |
| [`lay-pp-spine-overrides_image-only-reflow`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spine-overrides_image-only-reflow) | must | Passes | Checked: The overriding chapter is one fixed page that draws its image between two reflowable ones. |
| [`lay-pp-spine-overrides_image-spine-pp`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spine-overrides_image-spine-pp) | must | Passes | Checked: The image spine item shows its fixed-layout XHTML fallback, which draws the image, and the overriding chapter flows. |
| [`lay-pp-spine-overrides_image-spine-reflow`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spine-overrides_image-spine-reflow) | must | Passes | Checked: The image spine item shows its fixed-layout XHTML fallback, which draws the image, between reflowable chapters. |
| [`lay-pp-spread-none`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-spread-none) | must | Passes | Checked: With `spread none`, every page shows alone. |
| [`lay-pp-svg-icb_multi`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-svg-icb_multi) | must | Passes | Checked: Each SVG page takes the size of its own `viewBox`, 900 by 600 and 1200 by 600. |
| [`lay-pp-xhtml-icb`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-xhtml-icb) | must | Passes | Checked: The page is its 900 by 600 pixel viewport, and the grid of layered gradients paints over the red box, in 18 by 12 cells. |
| [`lay-pp-xhtml-icb_invalid_meta`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-xhtml-icb_invalid_meta) | should | Passes | Checked: Width and height are read from the invalid tag, so the second page is 900 by 600 pixels too, and both paint the grid of layered gradients over the red box. |
| [`lay-pp-xhtml-icb_multi`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-xhtml-icb_multi) | must | Passes | Checked: Each page takes its own viewport, 900 and 1400 pixels wide, and both paint the grid of layered gradients over the red box, which shows to the right of it on the wide page. |
| [`lay-pp-xhtml-icb_multi_declarations`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-xhtml-icb_multi_declarations) | must | Passes | Checked: Of two viewport tags, the first sizes the page, 1000 by 600. |
| [`lay-pp-xhtml-icb_repeated-in-meta`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-xhtml-icb_repeated-in-meta) | must | Passes | Checked: Of a repeated `width` and `height`, the first sizes the page, 900 by 600. |
| [`lay-pp-xhtml-icb_units`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-pp-xhtml-icb_units) | must | Passes | Checked: `width=1000px, height=600mm` sizes the page 1000 by 600. |

## Roll Layout

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`lay-roll-embedded-images`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-roll-embedded-images) | must | Fails ([#506](https://github.com/yuroyami/KitePDF/issues/506)) | Checked: Each plate is one page of the full width at its own aspect; today a roll book reads as reflowable, one letterboxed plate a page. |
| [`lay-roll-embedded-images-svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-roll-embedded-images-svg) | must | Fails ([#506](https://github.com/yuroyami/KitePDF/issues/506)) | Checked: Each plate is one page of the full width at its own aspect; today a roll book reads as reflowable, one letterboxed plate a page. |
| [`lay-roll-images-in-spine`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-roll-images-in-spine) | must | Fails ([#506](https://github.com/yuroyami/KitePDF/issues/506)) | Checked: Each plate is one page of the full width at its own aspect; today a roll book reads as reflowable, one text page a plate. |
| [`lay-roll-images-mixed`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/lay-roll-images-mixed) | must | Fails ([#506](https://github.com/yuroyami/KitePDF/issues/506)) | Checked: Each plate is one page of the full width at its own aspect; today a roll book reads as reflowable, one letterboxed plate a page. |

## Media Overlays

| Test | Level | Result | How it is known |
|---|---|---|---|
| [`mol-audio`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-audio) | must | Passes | Checked: The overlay's clip reads its audio file from 29.268 s to 44.783 s, which `KiteReadAloud` plays while it highlights the text. |
| [`mol-audio-exceeding-clipend`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-audio-exceeding-clipend) | must | Passes | Judged: From the code of `KiteReadAloud`: a clip whose `clipEnd` lies past the end of its file ends with the file, and reading goes on. |
| [`mol-audio-no-clipbegin`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-audio-no-clipbegin) | must | Passes | Checked: A clip with no `clipBegin` starts at 0 s. |
| [`mol-audio-no-clipend`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-audio-no-clipend) | must | Passes | Checked: A clip with no `clipEnd` plays to the end of its file. |
| [`mol-css`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-css) | should | Fails ([#525](https://github.com/yuroyami/KitePDF/issues/525)) | Judged: `KiteReadAloud` marks the clip being read with a highlight of its own and never applies the book's active classes, by design. |
| [`mol-ignore`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-ignore) | must | Not applicable | Judged: The test is for reading systems without media overlays, and says one with them should skip it. |
| [`mol-navigation`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-navigation) | must | Passes | Judged: After a jump through the table of contents, `KiteReadAloud` goes on from the first clip of the chapter the reader went to, as `ReadAloudTest` checks on a book of its own. |
| [`mol-support_xhtml`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-support_xhtml) | must | Passes | Judged: From the code of `KiteReadAloud`: it reads the overlay clip by clip, highlights each piece of text and turns the page with the reading. |
| [`mol-support_xhtml-fxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-support_xhtml-fxl) | must | Passes | Judged: From the code of `KiteReadAloud`: it reads the fixed page's overlay as it does a reflowable one. |
| [`mol-support_xhtml-load`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-support_xhtml-load) | must | Passes | Checked: The overlay that two chapters share gives each only the clips of its own text. |
| [`mol-support_xhtml-load-fxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-support_xhtml-load-fxl) | must | Passes | Checked: The overlay that two chapters share gives each only the clips of its own text. |
| [`mol-support_xhtml-load-next`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-support_xhtml-load-next) | should | Passes | Judged: From the code of `KiteReadAloud`: when a chapter's overlay ends it goes on with the next chapter's and turns the page itself. |
| [`mol-support_xhtml-load-next-fxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-support_xhtml-load-next-fxl) | should | Passes | Judged: From the code of `KiteReadAloud`: on fixed pages too, reading moves on to the next page's overlay and turns the page. |
| [`mol-timing-synchronization`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-timing-synchronization) | must | Passes | Judged: From the code of `KiteReadAloud`: each clip's text is highlighted while its audio plays. |
| [`mol-timing-synchronization_fxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-timing-synchronization_fxl) | must | Passes | Checked: The overlay that three pages share gives each page its one clip. |
| [`mol-timing-synchronization_multiple_audio`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-timing-synchronization_multiple_audio) | must | Passes | Judged: From the code of `KiteReadAloud`: the clips of both audio files read in order, the second file opening in the same player. |
| [`mol-timing-synchronization_multiple_audio-fxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-timing-synchronization_multiple_audio-fxl) | must | Passes | Judged: From the code of `KiteReadAloud`: on the fixed page too, the reading goes from the first audio file to the second in clip order. |
| [`mol-timing-synchronization_svg`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-timing-synchronization_svg) | must | Fails ([#523](https://github.com/yuroyami/KitePDF/issues/523)) | Checked: A clip's text inside the SVG chapter has a place on its page; today `locateFragment` finds none. |
| [`mol-timing-synchronization_svg-fxl`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-timing-synchronization_svg-fxl) | must | Fails ([#523](https://github.com/yuroyami/KitePDF/issues/523)) | Checked: A clip's text inside the SVG page has a place on it; today `locateFragment` finds none. |
| [`mol-tts_multi`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-tts_multi) | should | Fails ([#525](https://github.com/yuroyami/KitePDF/issues/525)) | Judged: Clips without audio are skipped, and there is no text-to-speech fallback, so nothing is read. |
| [`mol-tts_single`](https://github.com/w3c/epub-tests/tree/54092b4233253e9aac80e93ec4782b380b4b3403/tests/mol-tts_single) | should | Fails ([#525](https://github.com/yuroyami/KitePDF/issues/525)) | Judged: The clip without audio is skipped, and there is no text-to-speech fallback, so nothing is read. |
