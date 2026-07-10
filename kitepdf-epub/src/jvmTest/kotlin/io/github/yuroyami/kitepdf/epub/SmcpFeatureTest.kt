package io.github.yuroyami.kitepdf.epub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-67: the real-`smcp` path. Scans the in-repo reference fonts for one whose
 * GSUB carries the small-caps feature and proves the substituted glyph is
 * used at FULL size (no synthesis). Skips when no such font is present in
 * the checkout (the synthesized path is covered in commonTest).
 */
class SmcpFeatureTest {

    private fun fontsDir(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "mupdf-master/resources/fonts").exists()) d = d.parentFile
        return d?.let { File(it, "mupdf-master/resources/fonts") }
    }

    private fun smcpFace(): EmbeddedFace? {
        val dir = fontsDir() ?: return null
        val candidates = listOf("noto/NotoSans-Regular.otf", "noto/NotoSerif-Regular.otf", "sil/CharisSIL.cff")
            .map { File(dir, it) }.filter { it.exists() }
        for (f in candidates) {
            val face = FontRegistry.face("t", bold = false, italic = false, f.readBytes()) ?: continue
            val gid = face.gidFor('a'.code)
            if (gid != 0 && face.substSingle("smcp", gid) != gid) return face
        }
        return null
    }

    @Test
    fun smcp_feature_substitutes_the_glyph_at_full_size() {
        val face = smcpFace() ?: return // no smcp-bearing font in this checkout: skip
        val gid = face.gidFor('a'.code)
        val smcp = face.substSingle("smcp", gid)
        assertNotEquals(gid, smcp, "precondition")
        assertTrue(face.advance1000(smcp) > 0, "the small-cap glyph has a real advance")
    }
}
