package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.WrongPasswordException
import io.github.yuroyami.kitepdf.core.render.NoopCanvas
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * T-51: deterministic mutation fuzzer. Every corpus PDF and every generated
 * fixture gets [MUTANTS_PER_DOC] seeded mutations (byte flips, truncations,
 * 64-byte window shuffles; master seed 42). Each mutant must open + render
 * page 0 without anything worse than a format/password exception, within a
 * 10-second watchdog, without OOM. A failing mutant is written to
 * `build/fuzz/failing-<seed>-<i>.pdf` so it can be replayed and pinned as a
 * regression fixture.
 */
class MutationFuzzTest {

    private companion object {
        const val MASTER_SEED = 42L
        const val MUTANTS_PER_DOC = 200
        const val WATCHDOG_SECONDS = 10L
    }

    private fun corpusPdfs(): List<Pair<String, ByteArray>> {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        val dir = d?.let { File(it, "corpus/pdf") } ?: return emptyList()
        return dir.listFiles { f -> f.extension == "pdf" }
            ?.sortedBy { it.name }
            ?.map { it.name to it.readBytes() }
            ?: emptyList()
    }

    @Test
    fun mutants_never_crash_the_engine() {
        val docs = ArrayList<Pair<String, ByteArray>>()
        for (fx in GeneratedPdfs.all()) docs.add(fx.name to fx.bytes)
        docs.addAll(corpusPdfs())
        check(docs.isNotEmpty())

        val fuzzDir = File("build/fuzz").apply { mkdirs() }
        val rnd = Random(MASTER_SEED)
        var pool = newWatchdogPool()
        var exercised = 0
        try {
            for ((name, original) in docs) {
                for (i in 0 until MUTANTS_PER_DOC) {
                    val seed = rnd.nextLong()
                    val mutant = mutate(original, Random(seed))
                    val future = pool.submit { exercise(mutant) }
                    try {
                        future.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        future.cancel(true)
                        pool.shutdownNow()
                        pool = newWatchdogPool()
                        fail("TIMEOUT (${WATCHDOG_SECONDS}s) on mutant $i of $name: ${dump(fuzzDir, seed, i, mutant)}")
                    } catch (e: java.util.concurrent.ExecutionException) {
                        val cause = e.cause ?: e
                        fail(
                            "mutant $i of $name (seed=$seed) crashed with " +
                                "${cause::class.simpleName}: ${cause.message}\n" +
                                "mutant saved to ${dump(fuzzDir, seed, i, mutant)}",
                        )
                    }
                    exercised++
                }
            }
        } finally {
            pool.shutdownNow()
        }
        println("[fuzz] $exercised mutants over ${docs.size} documents, seed $MASTER_SEED: no crashes")
    }

    private fun newWatchdogPool(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "fuzz-watchdog").apply { isDaemon = true } }

    /** Open + render page 0. Format/password failures are the EXPECTED outcome. */
    private fun exercise(mutant: ByteArray) {
        try {
            val doc = PdfDocument.open(mutant)
            doc.pages.firstOrNull()?.renderTo(NoopCanvas)
        } catch (_: KiteFormatException) {
            // PdfFormatException included: malformed input politely refused.
        } catch (_: WrongPasswordException) {
            // A mutation that flipped bytes into /Encrypt territory.
        } catch (e: OutOfMemoryError) {
            throw AssertionError("OutOfMemoryError: a mutant blew the heap", e)
        }
    }

    private fun mutate(original: ByteArray, rnd: Random): ByteArray {
        val out = original.copyOf()
        when (rnd.nextInt(3)) {
            0 -> { // byte flips
                repeat(1 + rnd.nextInt(32)) {
                    val at = rnd.nextInt(out.size)
                    out[at] = (out[at].toInt() xor (1 + rnd.nextInt(255))).toByte()
                }
            }
            1 -> { // truncation at a random offset
                return out.copyOf(1 + rnd.nextInt(out.size))
            }
            else -> { // shuffle 64-byte windows
                repeat(1 + rnd.nextInt(4)) {
                    if (out.size < 129) return@repeat
                    val a = rnd.nextInt(out.size - 64)
                    val b = rnd.nextInt(out.size - 64)
                    for (k in 0 until 64) {
                        val t = out[a + k]; out[a + k] = out[b + k]; out[b + k] = t
                    }
                }
            }
        }
        return out
    }

    private fun dump(dir: File, seed: Long, i: Int, mutant: ByteArray): String {
        val f = File(dir, "failing-$seed-$i.pdf")
        f.writeBytes(mutant)
        return f.absolutePath
    }
}
