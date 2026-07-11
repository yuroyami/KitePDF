package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-52: seeded pseudo-random build -> open -> verify -> edit -> reopen
 * property loop. Pure common code; the mutool oracle half already lives in
 * WriterOracleTest. Every assertion message carries the seed so a failure is
 * reproducible with `runSeed(<seed>)`.
 */
class WriterRoundTripPropertyTest {

    /** Non-symbolic standard fonts: extracted text must match what we wrote. */
    private val textFonts = StandardFont.entries.filter {
        !it.baseFont.startsWith("Symbol") && !it.baseFont.startsWith("ZapfDingbats")
    }

    @Test
    fun twenty_five_seeds_round_trip() {
        for (seed in 1L..25L) runSeed(seed)
    }

    private fun runSeed(seed: Long) {
        val rnd = Random(seed)
        val pageCount = 1 + rnd.nextInt(20)
        val sizes = ArrayList<Pair<Double, Double>>()
        val strings = ArrayList<List<String>>() // per page, in emission order

        val builder = PdfBuilder()
        for (p in 0 until pageCount) {
            val w = 300.0 + rnd.nextInt(700)
            val h = 300.0 + rnd.nextInt(900)
            sizes.add(w to h)
            val runs = (1 + rnd.nextInt(5))
            val pageStrings = List(runs) { r -> "s$seed p$p r$r ${rnd.nextInt(100000)}" }
            strings.add(pageStrings)
            builder.page(width = w, height = h) {
                for ((r, text) in pageStrings.withIndex()) {
                    when (rnd.nextInt(3)) {
                        0 -> setFillRgb(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble())
                        1 -> setFillGray(rnd.nextDouble())
                        else -> setFillCmyk(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble())
                    }
                    // Random rect somewhere on the page.
                    rectangle(rnd.nextDouble() * (w - 60), rnd.nextDouble() * (h - 60), 10.0 + rnd.nextInt(50), 10.0 + rnd.nextInt(50))
                    fill()
                    setFillGray(0.0)
                    text(
                        textFonts[rnd.nextInt(textFonts.size)],
                        8.0 + rnd.nextInt(28),
                        20.0 + rnd.nextDouble() * (w - 220),
                        // Descending y per run so reading order matches emission order.
                        h - 40.0 - r * (h - 80.0) / runs,
                        text,
                    )
                }
            }
        }
        val bytes = builder.build()

        // ── open + structural invariants ─────────────────────────────────
        val doc = PdfDocument.open(bytes)
        assertEquals(pageCount, doc.pageCount, "seed $seed: page count")
        for (p in 0 until pageCount) {
            assertEquals(sizes[p].first, doc.pages[p].width, "seed $seed: page $p width")
            assertEquals(sizes[p].second, doc.pages[p].height, "seed $seed: page $p height")
            val extracted = doc.pages[p].extractText()
            var at = -1
            for (s in strings[p]) {
                val next = extracted.indexOf(s)
                assertTrue(next > at, "seed $seed: page $p must contain \"$s\" after offset $at, got $next in <<$extracted>>")
                at = next
            }
        }

        // ── incremental edit: stamp page 0 + set info ─────────────────────
        val stamp = "stamp-$seed"
        val editor = doc.edit()
        editor.stampPage(doc.pages[0]) {
            setFillRgb(1.0, 0.0, 0.0)
            text(StandardFont.Helvetica, 14.0, 30.0, 30.0, stamp)
        }
        editor.setInfo(title = "T-52 seed $seed", author = "prop-tester")
        val edited = editor.saveIncremental()

        val re = PdfDocument.open(edited)
        assertEquals(pageCount, re.pageCount, "seed $seed: page count after edit")
        for (p in 0 until pageCount) {
            val extracted = re.pages[p].extractText()
            for (s in strings[p]) {
                assertTrue(s in extracted, "seed $seed: page $p lost \"$s\" after incremental save")
            }
        }
        assertTrue(stamp in re.pages[0].extractText(), "seed $seed: stamp missing after reopen")
        assertEquals("T-52 seed $seed", re.info.title, "seed $seed: info title round-trip")
        assertEquals("prop-tester", re.info.author, "seed $seed: info author round-trip")
    }
}
