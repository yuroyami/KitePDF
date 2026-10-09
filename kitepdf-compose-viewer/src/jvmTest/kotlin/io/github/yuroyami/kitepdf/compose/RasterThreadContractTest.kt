package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Actual host-font resolution, asynchronous dispatch and stable form exports for #428. Most tests
 * force the path Android takes, host text through Compose's text on the UI thread. The desktop
 * JVM, iOS and macOS shape host text with Skia on any thread instead (#131): the tests at the
 * end pin that path.
 */
class RasterThreadContractTest {
    private val density = Density(1f)
    private val font = FontSpec(KiteFontFamily.SansSerif, false, false)

    @Test
    fun every_sync_overload_refuses_a_worker_before_reading_the_page() = runBlocking {
        val renderer = renderer()
        val traversals = AtomicInteger()
        val page = object : KitePage {
            override val displayWidth: Double get() { traversals.incrementAndGet(); return 200.0 }
            override val displayHeight: Double get() { traversals.incrementAndGet(); return 200.0 }
            override fun displayToDeviceBase(): KiteMatrix { traversals.incrementAndGet(); return KiteMatrix.IDENTITY }
            override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) { traversals.incrementAndGet() }
        }
        withContext(Dispatchers.Default) {
            assertFalse(EventQueue.isDispatchThread())
            // Invalid dimensions establish that the thread contract is checked even before allocation validation.
            assertFailsWith<IllegalStateException> { renderer.rasterize(page, 0, 0) }
            assertFailsWith<IllegalStateException> { renderer.rasterize(page, 0, 0, canvasDecorator = { it }) }
            assertFailsWith<IllegalStateException> { renderer.rasterize(page, 0, 0, formState = null) }
        }
        assertEquals(0, traversals.get())
    }

    @Test
    fun all_suspend_overloads_resolve_host_fonts_on_edt_and_match_sync_pixels() = runBlocking {
        assertTrue(GraphicsEnvironment.isHeadless(), "this regression must exercise headless desktop exports")
        for (family in 0..2) {
            val resolvedOnEdt = Collections.synchronizedList(ArrayList<Boolean>())
            val renderer = renderer(resolvedOnEdt)
            val page = hostPage()
            val decoratedPasses = AtomicInteger()
            val decorator: KiteCanvasDecorator = { inner ->
                decoratedPasses.incrementAndGet()
                // Application wrappers can introduce host text even when the page does not advertise it.
                drawText(inner, "D", KiteMatrix(1.0, 0.0, 0.0, -1.0, 100.0, 160.0))
                inner
            }
            val actual = withContext(Dispatchers.Default) {
                when (family) {
                    0 -> renderer.rasterizeOffMain(page, 200, 200)
                    1 -> renderer.rasterizeOffMain(page, 200, 200, canvasDecorator = decorator)
                    else -> renderer.rasterizeOffMain(page, 200, 200, formState = null, canvasDecorator = decorator)
                }
            }
            assertTrue(resolvedOnEdt.isNotEmpty(), "overload $family did not exercise the real font resolver")
            assertTrue(resolvedOnEdt.all { it }, "a font was resolved away from the EDT")
            if (family != 0) assertEquals(2, decoratedPasses.get(), "the wrapper must run in probe and real pass")
            val expected = onTestUiThread {
                when (family) {
                    0 -> renderer.rasterize(page, 200, 200)
                    1 -> renderer.rasterize(page, 200, 200, canvasDecorator = decorator)
                    else -> renderer.rasterize(page, 200, 200, formState = null, canvasDecorator = decorator)
                }
            }
            assertContentEquals(pixels(expected), pixels(actual), "overload $family changed the image")
            assertTrue(pixels(actual).any { it != -1 }, "host text must paint actual pixels")
        }
    }

    @Test
    fun direct_canvas_refuses_worker_host_text_but_a_probe_never_resolves_it() = runBlocking {
        val resolutions = Collections.synchronizedList(ArrayList<Boolean>())
        val measurer = measurer(resolutions)
        withContext(Dispatchers.Default) {
            val bitmap = ImageBitmap(200, 200)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 200f)) {
                val direct = ComposeCanvas(this, measurer, 1f, false, magnification = 1f, hostLines = false)
                assertFailsWith<IllegalStateException> { drawText(direct, "H", KiteMatrix.IDENTITY) }
                assertFailsWith<IllegalStateException> { direct.hostGlyphOutline("O", font) }
                val probe = ComposeCanvas(this, measurer, 1f, skipSystemFontText = true, magnification = 1f, hostLines = false)
                drawText(probe, "H", KiteMatrix.IDENTITY)
                assertNull(probe.hostGlyphOutline("O", font))
                assertTrue(probe.usedSystemFontText)
            }
        }
        assertEquals(emptyList(), resolutions, "a refused call or skipped probe must not touch host fonts")
    }

    @Test
    fun cancellation_before_the_queued_edt_pass_skips_drawing_and_releases_the_slot() = runBlocking {
        val renderer = renderer().apply { probesOffMain = false }
        val draws = AtomicInteger()
        val enteredEdt = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        EventQueue.invokeLater {
            enteredEdt.countDown()
            releaseEdt.await(10, TimeUnit.SECONDS)
        }
        assertTrue(enteredEdt.await(5, TimeUnit.SECONDS), "EDT blocker did not start")
        // With no probe and a free permit, UNDISPATCHED reaches the queued UI continuation before returning.
        val export = launch(start = CoroutineStart.UNDISPATCHED) {
            renderer.rasterizeOffMain(hostPage(draws), 200, 200)
        }
        try {
            export.cancel()
            assertEquals(0, draws.get(), "cancelled work must not draw while waiting for EDT")
        } finally {
            releaseEdt.countDown()
            export.cancel()
        }
        withTimeout(5_000) { export.join() }
        onTestUiThread { Unit } // Drain the cancelled continuation after the blocker.
        assertEquals(0, draws.get(), "the queued callback must check cancellation before starting")
        withTimeout(5_000) {
            KitePageRasterizer.rasterGate.withPermit({ RasterPriority.VISIBLE }) {
                KitePageRasterizer.rasterGate.withPermit({ RasterPriority.VISIBLE }) { Unit }
            }
        }
    }

    @Test
    fun a_ui_coroutine_can_suspend_for_a_worker_export_without_blocking_edt() = runBlocking {
        val resolutions = Collections.synchronizedList(ArrayList<Boolean>())
        val renderer = renderer(resolutions)
        val result = withTimeout(5_000) {
            async(TestUiDispatcher) {
                assertTrue(EventQueue.isDispatchThread())
                val bitmap = withContext(Dispatchers.Default) { renderer.rasterizeOffMain(hostPage(), 200, 200) }
                assertTrue(EventQueue.isDispatchThread())
                bitmap
            }.await()
        }
        assertTrue(resolutions.isNotEmpty())
        assertTrue(resolutions.all { it })
        assertTrue(pixels(result).any { it != -1 })
    }

    @Test
    fun live_form_edits_between_probe_and_ui_pass_do_not_change_the_export() = runBlocking {
        val doc = formDocument()
        val state = PdfFormState(doc)
        state.setValue("name", "accepted")
        state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)))
        val expectedState = state.snapshot()
        val renderer = renderer()
        val passes = AtomicInteger()
        val firstPass = CountDownLatch(1)
        val continueExport = CountDownLatch(1)
        val texts = Collections.synchronizedList(ArrayList<String>())
        // Base-14 form appearances can have outlines. A real host-font overlay guarantees both passes.
        val hostOverlay: KiteCanvasDecorator = { inner ->
            drawText(inner, "H", KiteMatrix(1.0, 0.0, 0.0, -1.0, 160.0, 190.0))
            inner
        }
        val decorator: KiteCanvasDecorator = { inner ->
            val pass = passes.incrementAndGet()
            hostOverlay(inner)
            recordText(inner) { text ->
                texts += text
                if (pass == 1) {
                    firstPass.countDown()
                    assertTrue(continueExport.await(10, TimeUnit.SECONDS), "live edit did not release the probe")
                }
            }
        }
        val export = async(Dispatchers.Default) {
            renderer.rasterizeOffMain(doc.pages[0], 200, 200, formState = state, canvasDecorator = decorator)
        }
        val actual = try {
            assertTrue(firstPass.await(5, TimeUnit.SECONDS), "the form did not finish its probe")
            state.setValue("name", "later")
            state.setChoiceSelection("choice", PdfChoiceSelection(listOf(0)))
            state.setHidden("choice", true)
            continueExport.countDown()
            withTimeout(5_000) { export.await() }
        } finally {
            continueExport.countDown()
            export.cancel()
        }
        assertEquals(2, passes.get())
        assertEquals(2, texts.size)
        assertEquals(texts[0], texts[1], "both passes must use the same accepted fields")
        assertTrue(texts.all { "accepted" in it && "Second label" in it }, texts.toString())
        assertTrue(texts.none { "later" in it || "First label" in it }, texts.toString())
        val expected = onTestUiThread {
            renderer.rasterize(doc.pages[0], 200, 200, formState = expectedState, canvasDecorator = hostOverlay)
        }
        assertContentEquals(pixels(expected), pixels(actual))
        val later = renderer.rasterizeOffMain(doc.pages[0], 200, 200, formState = state, canvasDecorator = hostOverlay)
        assertFalse(pixels(later).contentEquals(pixels(actual)), "the later edit must remain live for a new export")
    }

    @Test
    fun a_form_export_captures_accepted_values_before_waiting_for_a_raster_slot() = runBlocking {
        val doc = formDocument()
        val state = PdfFormState(doc)
        state.setValue("name", "queued value")
        val renderer = renderer()
        val releaseSlots = CompletableDeferred<Unit>()
        val holders = List(2) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                KitePageRasterizer.rasterGate.withPermit({ RasterPriority.VISIBLE }) { releaseSlots.await() }
            }
        }
        val texts = Collections.synchronizedList(ArrayList<String>())
        val export = async(start = CoroutineStart.UNDISPATCHED) {
            renderer.rasterizeOffMain(doc.pages[0], 200, 200, formState = state,
                canvasDecorator = { inner -> recordText(inner) { texts += it } })
        }
        try {
            assertEquals(1, KitePageRasterizer.rasterGate.waitingCount)
            state.setValue("name", "changed while queued")
            releaseSlots.complete(Unit)
            withTimeout(5_000) { export.await() }
            assertTrue(texts.isNotEmpty())
            assertTrue(texts.all { "queued value" in it && "changed while queued" !in it }, texts.toString())
        } finally {
            releaseSlots.complete(Unit)
            export.cancel()
            holders.forEach { it.cancel() }
        }
    }

    /** A rasterizer that draws host text through Compose, as on Android, unless [throughCompose] is false. */
    @Test
    fun a_page_with_host_text_rasters_once_off_the_ui_thread_without_compose_text() = runBlocking {
        val resolutions = Collections.synchronizedList(ArrayList<Boolean>())
        val renderer = renderer(resolutions, throughCompose = false)
        val passes = AtomicInteger()
        val textThreads = Collections.synchronizedList(ArrayList<Boolean>())
        val decorator: KiteCanvasDecorator = { inner ->
            passes.incrementAndGet()
            object : KiteCanvas by inner {
                override fun drawGlyphs(glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int,
                    hasOutlines: Boolean, fontSpec: FontSpec, textToDevice: KiteMatrix,
                    color: RgbColor, alpha: Double, blendMode: KiteBlendMode) {
                    textThreads += EventQueue.isDispatchThread()
                    inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, color, alpha, blendMode)
                }
            }
        }
        val actual = withContext(Dispatchers.Default) { renderer.rasterizeOffMain(hostPage(), 200, 200, canvasDecorator = decorator) }
        assertEquals(1, passes.get(), "a page with host text drew more than once")
        assertEquals(listOf(false), textThreads, "the host text drew on the UI thread")
        assertEquals(emptyList(), resolutions, "the host text went through Compose's font resolver")
        assertTrue(pixels(actual).any { it != -1 }, "host text must paint actual pixels")
        val expected = onTestUiThread { renderer.rasterize(hostPage(), 200, 200) }
        assertContentEquals(pixels(expected), pixels(actual), "the raster off the UI thread drew other pixels")
    }

    @Test
    fun a_direct_canvas_draws_host_text_and_outlines_on_a_worker() = runBlocking {
        val resolutions = Collections.synchronizedList(ArrayList<Boolean>())
        val measurer = measurer(resolutions)
        val bitmap = ImageBitmap(200, 200)
        withContext(Dispatchers.Default) {
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 200f)) {
                val canvas = ComposeCanvas(this, measurer)
                drawText(canvas, "H", KiteMatrix(1.0, 0.0, 0.0, -1.0, 20.0, 100.0))
                assertTrue(canvas.hostGlyphOutline("O", font)?.isEmpty() == false, "no host outline off the UI thread")
            }
        }
        assertTrue(pixels(bitmap).any { it != -1 }, "host text must paint actual pixels")
        assertEquals(emptyList(), resolutions, "the host text went through Compose's font resolver")
    }

    @Test
    fun skia_text_draws_the_pixels_compose_text_drew() {
        // Skia's paragraph takes Compose's family names, style, rasterization, line height and
        // baseline, so a run draws the same pixels on either path: kana, Han, Greek and Cyrillic
        // from fallback faces too, at sizes whose baselines round either way.
        val text = "Hello, world. Quick fox \u00e9t\u00e9 \u3044 \u4e2d\u6587 \u0391\u03b2 \u0416"
        val glyphs = text.map { TextGlyph(0, 1, -1, it.toString(), 560.0, null, false) }
        val measurer = measurer(Collections.synchronizedList(ArrayList()))
        fun ink(spec: FontSpec, size: Double, throughCompose: Boolean): IntArray {
            val bitmap = ImageBitmap(600, 100)
            onTestUiThread {
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(600f, 100f)) {
                    drawRect(androidx.compose.ui.graphics.Color.White)
                    ComposeCanvas(this, measurer, 1f, false, magnification = 1f, hostLines = !throughCompose)
                        .drawGlyphs(glyphs, size, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, 10.3, 70.6), RgbColor.BLACK)
                }
            }
            return pixels(bitmap)
        }
        for (size in listOf(7.0, 9.5, 15.2, 23.3)) for (family in KiteFontFamily.entries) for (bold in listOf(false, true)) for (italic in listOf(false, true)) {
            val spec = FontSpec(family, bold, italic)
            val skia = ink(spec, size, throughCompose = false)
            assertTrue(skia.any { it != -1 }, "$spec at $size drew nothing")
            assertContentEquals(ink(spec, size, throughCompose = true), skia, "$spec at $size")
        }
    }

    @Test
    fun host_text_on_many_threads_at_once_stays_whole() = runBlocking {
        // The iOS abort of db082392 was two threads in Compose's text cache at once. Skia's text has no such cache.
        // Two rasters run at once on the pool while the UI thread draws its own, so three threads draw host text together.
        val renderer = renderer(throughCompose = false)
        val reference = pixels(withContext(Dispatchers.Default) { renderer.rasterizeOffMain(hostPage(), 200, 200) })
        val workers = (0 until 8).map {
            async(Dispatchers.Default) { List(20) { pixels(renderer.rasterizeOffMain(hostPage(), 200, 200)) } }
        }
        val ui = async(Dispatchers.Default) { List(40) { pixels(onTestUiThread { renderer.rasterize(hostPage(), 200, 200) }) } }
        val all = workers.flatMap { it.await() } + ui.await()
        assertEquals(200, all.size)
        assertTrue(all.all { it.contentEquals(reference) }, "a raster drew other pixels under load")
    }

    private fun renderer(
        resolutions: MutableList<Boolean> = Collections.synchronizedList(ArrayList()),
        throughCompose: Boolean = true,
    ) = KitePageRasterizer(density, LayoutDirection.Ltr, measurer(resolutions)).apply { textOffMain = !throughCompose }

    private fun measurer(resolutions: MutableList<Boolean>): TextMeasurer = onTestUiThread {
        val resolver = testFontFamilyResolver()
        // Compose requires its final resolver implementation in ParagraphBuilder. Observe its existing
        // argument interceptor instead: every real resolve, including cache hits, goes through it before Skia.
        // This fixture depends on pinned Compose internals and must be updated if their layout changes.
        val field = resolver.javaClass.getDeclaredField("platformResolveInterceptor").apply { isAccessible = true }
        val delegate = field.get(resolver)
        val interceptor = Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> {
                    if (method.name == "interceptFontFamily") {
                        val onEdt = EventQueue.isDispatchThread()
                        resolutions += onEdt
                        check(onEdt) { "the host font resolver was called off the EDT" }
                    }
                    try { method.invoke(delegate, *(arguments ?: emptyArray())) }
                    catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
                }
            }
        }
        field.set(resolver, interceptor)
        TextMeasurer(resolver, density, LayoutDirection.Ltr)
    }

    private fun hostPage(draws: AtomicInteger = AtomicInteger()) = object : KitePage {
        override val displayWidth = 200.0
        override val displayHeight = 200.0
        override fun displayToDeviceBase() = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            draws.incrementAndGet()
            canvas.beginPage(200.0, 200.0, deviceCtm)
            drawText(canvas, "H", deviceCtm.concat(KiteMatrix.translation(20.0, 100.0)))
            canvas.endPage()
        }
    }

    private fun drawText(canvas: KiteCanvas, text: String, matrix: KiteMatrix) = canvas.drawGlyphs(
        listOf(TextGlyph(0, text.length, -1, text, 700.0, null, false)),
        30.0, 1000, false, font, matrix, RgbColor.BLACK,
    )

    private fun recordText(inner: KiteCanvas, complete: (String) -> Unit): KiteCanvas = object : KiteCanvas by inner {
        private val text = StringBuilder()
        override fun drawGlyphs(glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int,
            hasOutlines: Boolean, fontSpec: FontSpec, textToDevice: KiteMatrix,
            color: RgbColor, alpha: Double, blendMode: KiteBlendMode) {
            for (glyph in glyphs) text.append(glyph.text.orEmpty())
            inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, color, alpha, blendMode)
        }
        override fun endPage() {
            inner.endPage()
            complete(text.toString())
        }
    }

    private fun pixels(bitmap: ImageBitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

    private fun formDocument(): PdfDocument {
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun obj(body: String) {
            offsets += out.length
            out.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        obj("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        obj("<< /Type /Page /Parent 2 0 R /Annots [4 0 R 5 0 R] >>")
        obj("<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (source) /Rect [10 120 190 160] /DA (/Helv 16 Tf 0 g) >>")
        obj("<< /Type /Annot /Subtype /Widget /FT /Ch /T (choice) /Ff 131072 /Opt [[(same) (First label)] [(same) (Second label)]] /V (same) /I [0] /Rect [10 40 190 80] /DA (/Helv 16 Tf 0 g) >>")
        val xref = out.length
        out.append("xref\n0 6\n0000000000 65535 f \n")
        for (offset in offsets) out.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(out.toString().encodeToByteArray())
    }
}
