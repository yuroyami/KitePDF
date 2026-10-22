# KitePDF

A Kotlin Multiplatform library for reading and writing PDF, EPUB, CBZ, SVG and XPS/OpenXPS, with one Compose viewer for all of them.

Read `CONTRIBUTING.md` first: it holds the ground rules, the references, and the gate you must run before every commit.

Open work lives in GitHub Issues. There is no private planning file, and there never will be one again. If a plan needs writing, it is a tracking issue.

## Gotchas

Things a previous change taught the hard way. One line each. Delete a line when it stops being true.

- A target that no gate compiles will silently rot. Non-exhaustive `when` blocks left three canvases uncompilable for weeks and nothing noticed.
- A Linux host cannot build Apple code, but Kotlin/Native's front end can type-check it: `kotlinc-native -target macos_arm64 -p library -Xmetadata-klib` with `-l` for each platform klib of the macOS distribution and a kitepdf-core klib whose manifest names that target. Only CI runs it (#589).
- The differential gate averages over synthetic pages, so a slow or blank real document can still pass (#46, #43).
- A test that returns early instead of calling an assumption reports PASS, not SKIPPED, so a whole feature can be untested and green (#44).
- `./kotlin test` on the JVM and Android runs only test classes whose names start with `Test` or end with `Test` or `Tests`. A class named `...Probe` never runs, and the run does not even report it as skipped.
- A mutation that does not compile leaves the last test results in place, and warnings fail the compile here. Check that the result file is newer than the mutation before you read it.
- The EPUB corpus cannot catch a block nested inside an inline element, because the HTML parser already splits the common case at parse time. The bug that found it came from an FB2 conversion, a shape the corpus does not cover.
- Hashing a recorded draw call is unstable across runs, because identity hash codes leak in through the image data type. Compare fields, not hashes.
- The opt-in compiler warning carries the message text, not the annotation name. Grep for the message.
- In zsh, `set -- $pair` does not word-split, so a rename loop written for bash silently does nothing.
- Sampling a crossfade mid-fade reads white, and asserting before pumping a pager animation reads the old page. Both are timing assumptions in the test, not product bugs.
- A wall-clock benchmark failure is often ambient machine load. Bisect before believing it: an older commit scoring worse under the same load proves it is the machine.
- Reading a filter chain without the lenient wrapper reintroduces a salvage hole, however local the call site looks.
- A page object built fresh on every lookup defeats every identity-keyed cache above it, and no test counted rasters, so each chapter landing re-rendered the visible pages for two releases (#221).
- The JVM suites run on a 3 GB heap and never measure retained memory, so a book that fills a 192 MB Android heap passes every gate (#218).
- The layout budget only counts what it lays out: a font declared in every chapter's own style block was parsed 41 times with 41 glyph caches, the budget said 23 MB while the heap held 340 MB, and only a heap class histogram showed it (#224).
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
- CoreGraphics gives a bitmap context a device space that runs down while its base space runs up, so a raster read in device pixels comes out upside down. A test box in the middle of the context, or a backdrop of one colour, reads the same either way up (#308).
- Kotlin/Native checks a cast from an Objective-C object to a C pointer at run time, so `as CFDataRef` compiles and then throws. Create the Core Foundation object instead (#288).
- mutool repairs a broken xref, warns on stderr and still exits with 0, and `draw` loads only the objects its page uses. Check a file KitePDF wrote with `MutoolAcceptance`, and load every object with `mutool show <file> grep` (#295).
- `mutool draw -o /dev/null` exits with 1 for every file, because it cannot pick an output format from the name. A test that expects mutool to refuse a file must pass `-F` (#296).
- A glyph lookup that misses falls back to glyph 0 and draws nothing, with no error. So the CFF standard strings stopped at SID 149, every accented letter of an embedded Type1C font vanished, and no test noticed. Test a font path with a glyph beyond ASCII (#303).
- A synthetic font tests none of what it leaves empty. Every Type 1 fixture had `/Subrs 0 array`, so subroutines ran still encrypted in every LaTeX font and no test noticed. Compare a real font glyph by glyph with fontTools (#596).
- `ImageComposeScene` runs effects on `Dispatchers.Unconfined` by default, so code after a frame await runs inside the frame, before recomposition. An app runs it after recomposition, so a scene test can pass in an order no app uses. Make the scene with `QueuedEffects` and hand it to `SceneTestDriver` to get the app order (#328).
- Under that default dispatcher, an effect goes on running on the thread that ended its wait: the pool after `withContext`, the timer thread after `delay`. A snapshot or a scroll from there runs Compose's layout observers on that thread, and the test fails now and then with "multithreaded access to SnapshotStateObserver". Call `backOnComposeThread()` after such a wait (#443).
- An empty `withContext` hop can end before its caller suspends, and the caller then goes on on its own thread. A test whose premise is that thread change must hold the hop on a latch until the caller waits in it.
- `ImageComposeScene` and the desktop take `NoOpPlatformPrefetchScheduler` in Compose 1.12, so lazy-list prefetch and a `LazyLayoutCacheWindow` never run there. A prefetch that a scene test can see must be the viewer's own (#437).
- Homebrew's `mutool` is built without OpenSSL, so `mutool sign -v` cannot check a signature and `mutool sign -s` cannot make one. Poppler's `pdfsig` can check one, and `SignatureOracleTest` runs it (#203).
- Kotlin/JS prints a whole `Double` as `1`, while the JVM prints `1.0`. A golden text built with `toString()` on a `Double` passes on the JVM and fails on JS. Round to a `Long` first.
- Kotlin/JS keeps a `Float` as a double, so `toFloat().toDouble()` rounds nothing there. Round to float32 with `Float.fromBits(x.toFloat().toBits())` (#609).
- A lone surrogate in a string literal becomes `?` on Kotlin/JS. Build it from `Char` values with `charArrayOf(...).concatToString()`.
- A debug build for Kotlin/Native takes about 10 KB of stack for each level of EPUB layout, three times a release build, and a secondary thread on Apple platforms has 512 KB. Measure a nesting limit there, not on the JVM (#450).
- The page text has a line break wherever a narrow box wraps, so two words that a test looks for can sit on two lines. Fold white space before the search, or wrapped text reads as lost text (#450).
- Git treats a PDF without NUL bytes as text, and `core.autocrlf=input` rewrote the line endings of a corpus PDF on commit, so its SHA-256 no longer matched. `.gitattributes` marks PDF and EPUB binary; keep it that way.
- JFR's default execution samples do not see native frames, so zlib inflate and Java2D drawing vanish from a profile. Add native-method samples or a stage timer before you trust the shares (#462).
- Thread CPU time moves with machine load too: the same scanned page measured 100 to 162 ms on different runs. Compare a change before and after in one JVM, with the runs alternating (#462).
- A KiteJS host function that returns Kotlin `null` gives the script `undefined`, not `null`, so a script-side check written `=== null` never matches. The EPUB DOM prelude compares host answers with `== null` (#41).
- A JVM class file holds no string constant over 64 KB, so a longer Kotlin literal fails the compile with "UTF8 string too large". The EPUB DOM prelude is two constants joined at run time for it.
- An element's `style` that answers every key as a CSS property hands a string to a script that probes for a method, so jQuery 1.7.1 called `style.removeAttribute` and threw. Answer CSS property names only.
- The W3C EPUB test suite and its reports key a test by its folder name, and three folders carry another `dc:identifier` (`lay-pp-svg-icb_multi`, `ocf-font_obfuscation_bis`, `pkg-unique-id_duplicate`). Key on the folder.
- A KitePlayer says Paused before it counts the render its device had started, so its position moves one period after the status changes. Let the position settle before a test holds it (#568).
- Kotlin/Wasm 2.4.20 wrote an invalid module when a suspend function of an outer class called an inline function of its inner class with a lambda, and the wasm tests failed to load with "CompileError: ... expected type". A plain method in the inner class works around it (#489).
- Node 24 has no WebAssembly stack switching, so a script never pauses there. CI runs the JS, wasmJs and wasmWasi tests on Node 26 (#489).
- A recorded glyph run's text-to-device y runs up the page, while an EPUB page's link and embed rectangles run down it. A check that mixes them looks for a superscript below its base.
- The EPUB layout places marks in typing order and reverses a right-to-left line afterwards, so a mark draws before its base and its offset must not count the base's advance. An oracle of glyph ids cannot see a mark in the wrong place. Compare positions with `hb-shape` (#622).
- A corpus page whose font is not embedded scores by the host's fonts. `public-pdfa-vertical-text` scored 0.0046 and later 0.0066 on a Mac and 0.017 on CI's Linux at one commit. Record CI's score in the differential baseline.
