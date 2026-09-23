# KitePDF

A Kotlin Multiplatform library for reading and writing PDF, EPUB, CBZ, SVG and XPS/OpenXPS (XPS is unreleased), with one Compose viewer for all of them.

Read `CONTRIBUTING.md` first: it holds the ground rules, the references, and the gate you must run before every commit.

Open work lives in GitHub Issues. There is no private planning file, and there never will be one again. If a plan needs writing, it is a tracking issue.

## Gotchas

Things a previous change taught the hard way. One line each. Delete a line when it stops being true.

- A target that no gate compiles will silently rot. Non-exhaustive `when` blocks left three canvases uncompilable for weeks and nothing noticed.
- The differential gate averages over synthetic pages, so a slow or blank real document can still pass (#46, #43).
- A test that returns early instead of calling an assumption reports PASS, not SKIPPED, so a whole feature can be untested and green (#44).
- The EPUB corpus cannot catch a block nested inside an inline element, because the HTML parser already splits the common case at parse time. The bug that found it came from an FB2 conversion, a shape the corpus does not cover.
- The browser test runner cannot run from a checkout whose absolute path contains a hash character. It truncates the URL and dies with a 404 before any test runs. Run `jsBrowserTest` from a `git worktree` whose path has no hash; CI runs it on every push.
- Hashing a recorded draw call is unstable across runs, because identity hash codes leak in through the image data type. Compare fields, not hashes.
- The opt-in compiler warning carries the message text, not the annotation name, and Gradle's quiet flag swallows warning lines entirely. Grep for the message.
- In zsh, `set -- $pair` does not word-split, so a rename loop written for bash silently does nothing.
- Sampling a crossfade mid-fade reads white, and asserting before pumping a pager animation reads the old page. Both are timing assumptions in the test, not product bugs.
- A wall-clock benchmark failure is often ambient machine load. Bisect before believing it: an older commit scoring worse under the same load proves it is the machine.
- Reading a filter chain without the lenient wrapper reintroduces a salvage hole, however local the call site looks.
- A page object built fresh on every lookup defeats every identity-keyed cache above it, and no test counted rasters, so each chapter landing re-rendered the visible pages for two releases (#221).
- The JVM suites run on a 3 GB heap and never measure retained memory, so a book that fills a 192 MB Android heap passes every gate (#218).
- The layout budget only counts what it lays out: a font declared in every chapter's own style block was parsed 41 times with 41 glyph caches, the budget said 23 MB while the heap held 340 MB, and only a heap class histogram showed it (#224).
- A build run with `-Pkotlin.incremental=false`, as a stash check does, leaves the next normal build failing with "FqNames can't be derived". Delete the module's `build/kotlin` first.
- A new dependency that brings npm packages makes every JS test fail on the stale yarn lock. Run `kotlinUpgradeYarnLock` and `kotlinWasmUpgradeYarnLock` and commit `kotlin-js-store` with the change.
- The differential oracle draws no push-button background: MuPDF's own `draw_push_button` prints `"0 0 %g %g re"` with four arguments, so its background rectangle is empty. The spec and Chrome fill it, so a form with `/MK /BG` scores worse against mutool and is still right.
- A fake clock that moves a millisecond per read ties the benchmark to the engine's own speed. The script runner's deadline check read it every 100,000 instructions, so a faster engine moved game time more slowly, the game ran fewer tics per frame, and the frame time never fell. Move the clock per frame, not per read.
- DoomPDF shows a still title screen for its first eleven seconds and writes almost nothing while it is up. A benchmark that measures the first frames measures an idle loop and reports a fine number for doing nothing. Run 15 seconds of game time first, and assert that half the measured frames drew something.
- Java2D gives every buffered image of one type the same device configuration, so `deviceConfiguration.bounds` says nothing about the surface a Graphics2D draws on. A soft mask sized from it became a 10-million-point layer at a tiny scale and erased the page (#255).
- A 3-band RGB raster has no alpha band, so a custom Composite that reads band 3 sees every pixel as transparent. That made every AWT blend mode paint as Normal on RGB surfaces (#272).
- `RedactionEngine` keeps its own copy of the renderer's text state machine. A text fix in `PageRenderer` that skips it lets a redaction keep text the page draws (#278).
- The EPUB sweep's blank-page check passes a cover page that paints only a background fill, so a cover image that fails to load goes unnoticed (#276).
- A canvas draws a system font when an embedded font yields no outlines, so a pixel comparison with mutool passed while KitePDF never read a CFF subset. Count `TextGlyph.outline` on a recording canvas as well (#280).
- A `KiteCanvas` overload with a default body goes past every wrapper written with `by` delegation, so a decorator that overrides only the old overload stops seeing those paints with no compile error. The renderer calls the old overload for the old case (#290).
- `CGContextDrawImage` draws the first row of an image at the top of its rectangle, unlike the other canvases. An image test whose rows are equal cannot show a vertical flip (#289).
- Kotlin/Native checks a cast from an Objective-C object to a C pointer at run time, so `as CFDataRef` compiles and then throws. Create the Core Foundation object instead (#288).
- Gradle applies `--tests` only to the test task named just before it, so `:a:jvmTest :b:jvmTest --tests X` runs every test of `:a`. A run that looks filtered can be the whole suite.
