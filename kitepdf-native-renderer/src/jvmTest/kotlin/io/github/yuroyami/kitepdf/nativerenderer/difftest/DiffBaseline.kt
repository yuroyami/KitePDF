package io.github.yuroyami.kitepdf.nativerenderer.difftest

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The recorded score of each page of the differential sweep, in a tracked file, so a page
 * that gets worse fails even when the mean does not (#195). The file moves only when a run
 * asks for it with `-Dkitepdf.diff.updateBaseline=true`, and the commit that moves it says why.
 *
 * A synthetic fixture keys by its name, so a fixture whose bytes change keeps its score. A
 * corpus document keys by a hash of its bytes, as the EPUB page counts do, and its name stays
 * out of the file because corpus files are private.
 */
internal object DiffBaseline {

    /** The scores of one page: mean absolute error, fraction of changed pixels, largest channel error. */
    data class Score(val mae: Double, val diffFraction: Double, val maxDelta: Int)

    /** The key of page [page] (0-based) of [entry]. */
    fun key(entry: Corpus.Entry, page: Int): String = "${id(entry)} p$page"

    /** The fixture name, or `sha1-` and a hash prefix for a corpus document. */
    fun id(entry: Corpus.Entry): String =
        if (entry.synthetic) entry.name else "sha1-" + hashes.getOrPut(entry.pdf.absolutePath) { sha1(entry.pdf.readBytes()) }

    private val hashes = HashMap<String, String>()

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)

    /** The tracked file, found from the repo root so any working directory works. */
    fun file(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return File(d ?: File("."), "kitepdf-native-renderer/src/jvmTest/resources/difftest-baseline.txt")
    }

    /** `id pN mae diffFraction maxDelta` per line; `#` starts a comment. */
    fun read(f: File): Map<String, Score> {
        if (!f.exists()) return emptyMap()
        val out = LinkedHashMap<String, Score>()
        for (line in f.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val mae = parts[2].toDoubleOrNull() ?: continue
            val fraction = parts[3].toDoubleOrNull() ?: continue
            val delta = parts[4].toIntOrNull() ?: continue
            out["${parts[0]} ${parts[1]}"] = Score(mae, fraction, delta)
        }
        return out
    }

    /**
     * Writes [current] over [old]. An entry this run did not score stays, so a corpus document
     * only another machine has keeps its score, unless it belongs to a fixture that no longer exists.
     */
    fun write(f: File, old: Map<String, Score>, current: Map<String, Score>, fixtures: Set<String>) {
        val merged = LinkedHashMap<String, Score>()
        for ((key, score) in old) {
            val id = key.substringBefore(' ')
            if (id.startsWith("sha1-") || id in fixtures) merged[key] = score
        }
        merged.putAll(current)
        val ordered = merged.entries.sortedWith(
            compareBy({ it.key.startsWith("sha1-") }, { it.key.substringBefore(' ') }, { it.key.substringAfter(" p").toIntOrNull() }),
        )
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                append("# The score of each page of the differential sweep against MuPDF at 96 dpi, checked by DifferentialTest.\n")
                append("# Fixture name or corpus document hash, page, mean absolute error, fraction of changed pixels,\n")
                append("# largest channel error. Rewrite with -Dkitepdf.diff.updateBaseline=true and say why in the commit.\n")
                // A fixed locale, so the file reads back the same wherever it is written.
                for ((key, s) in ordered) append(String.format(Locale.ROOT, "%s %.5f %.5f %d\n", key, s.mae, s.diffFraction, s.maxDelta))
            },
        )
    }

    /** What makes [now] worse than [base], or null when it is within the margins. */
    fun regression(base: Score, now: Score): String? {
        val reasons = ArrayList<String>()
        if (now.mae > base.mae * FACTOR + MAE_MARGIN) reasons += "MAE %.5f -> %.5f".format(base.mae, now.mae)
        if (now.diffFraction > base.diffFraction * FACTOR + FRACTION_MARGIN) {
            reasons += "changed pixels %.5f -> %.5f".format(base.diffFraction, now.diffFraction)
        }
        if (now.maxDelta > base.maxDelta + DELTA_MARGIN) reasons += "largest channel error ${base.maxDelta} -> ${now.maxDelta}"
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** True when [now] is clearly better than [base], so the baseline should move down with it. */
    fun improved(base: Score, now: Score): Boolean =
        now.mae * FACTOR + MAE_MARGIN < base.mae || now.diffFraction * FACTOR + FRACTION_MARGIN < base.diffFraction

    /** A page may score this much worse, relatively and absolutely, before it fails. */
    private const val FACTOR = 1.25
    private const val MAE_MARGIN = 0.0005
    private const val FRACTION_MARGIN = 0.002
    private const val DELTA_MARGIN = 48
}
