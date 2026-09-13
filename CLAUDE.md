# KitePDF

A Kotlin Multiplatform library for reading and writing PDF, EPUB, CBZ and SVG, with one Compose viewer for all of them.

Read `CONTRIBUTING.md` first: it holds the ground rules, the references, and the gate you must run before every commit.

Open work lives in GitHub Issues. There is no private planning file, and there never will be one again. If a plan needs writing, it is a tracking issue.

## Gotchas

Things a previous change taught the hard way. One line each. Delete a line when it stops being true.

- A target that no gate compiles will silently rot. Non-exhaustive `when` blocks left three canvases uncompilable for weeks and nothing noticed.
- The differential gate averages over synthetic pages, so a slow or blank real document can still pass (#46, #43).
- A test that returns early instead of calling an assumption reports PASS, not SKIPPED, so a whole feature can be untested and green (#44).
- The EPUB corpus cannot catch a block nested inside an inline element, because the HTML parser already splits the common case at parse time. The bug that found it came from an FB2 conversion, a shape the corpus does not cover.
- The browser test runner cannot run from a checkout whose absolute path contains a hash character. It truncates the URL and dies with a 404 before any test runs, so the browser backend has no pixel coverage here.
- Hashing a recorded draw call is unstable across runs, because identity hash codes leak in through the image data type. Compare fields, not hashes.
- The opt-in compiler warning carries the message text, not the annotation name, and Gradle's quiet flag swallows warning lines entirely. Grep for the message.
- In zsh, `set -- $pair` does not word-split, so a rename loop written for bash silently does nothing.
- Sampling a crossfade mid-fade reads white, and asserting before pumping a pager animation reads the old page. Both are timing assumptions in the test, not product bugs.
- A wall-clock benchmark failure is often ambient machine load. Bisect before believing it: an older commit scoring worse under the same load proves it is the machine.
- Reading a filter chain without the lenient wrapper reintroduces a salvage hole, however local the call site looks.
- A page object built fresh on every lookup defeats every identity-keyed cache above it, and no test counted rasters, so each chapter landing re-rendered the visible pages for two releases (#221).
- The JVM suites run on a 3 GB heap and never measure retained memory, so a book that fills a 192 MB Android heap passes every gate (#218).
- The layout budget only counts what it lays out: a font declared in every chapter's own style block was parsed 41 times with 41 glyph caches, the budget said 23 MB while the heap held 340 MB, and only a heap class histogram showed it (#224).
