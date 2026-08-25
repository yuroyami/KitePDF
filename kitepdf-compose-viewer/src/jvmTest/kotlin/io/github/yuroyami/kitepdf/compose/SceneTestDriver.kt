package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.skia.Image

/**
 * Drives an [ImageComposeScene] until a pixel condition holds.
 *
 * [KiteDocView] fades a freshly rasterized page in via `Crossfade`, so a page is not
 * fully opaque within the handful of frames a hard-cut raster used to need. This
 * driver advances the virtual frame clock (so the fade animates) and polls after
 * each frame until the page is on screen or a timeout elapses. The frame-time
 * cursor is monotonic across calls, so a test can pump, change state, then pump
 * again. A small real sleep per frame keeps it robust to any post-frame effects.
 */
internal class SceneTestDriver(private val scene: ImageComposeScene) {

    private var timeNanos = 0L

    /**
     * Render frames until [check] passes against the latest frame, or until the
     * frame/time budget is exhausted. Returns the last rendered frame either way
     * to let the caller's assertions report the failure if the condition never
     * held.
     */
    fun pumpUntil(
        maxFrames: Int = 600,
        timeoutMs: Long = 10_000,
        check: (PixelMap) -> Boolean,
    ): Image {
        var img = scene.render(timeNanos)
        val deadline = System.currentTimeMillis() + timeoutMs
        var frame = 0
        while (frame < maxFrames && System.currentTimeMillis() < deadline) {
            if (check(img.toComposeImageBitmap().toPixelMap())) return img
            Thread.sleep(4)
            timeNanos += FRAME_NANOS
            img = scene.render(timeNanos)
            frame++
        }
        return img
    }

    /**
     * Render frames until [check] holds. For conditions that live in state
     * rather than in pixels, such as a chapter finishing its layout on a
     * background thread.
     */
    fun pumpUntilState(
        maxFrames: Int = 900,
        timeoutMs: Long = 20_000,
        check: () -> Boolean,
    ) {
        scene.render(timeNanos)
        val deadline = System.currentTimeMillis() + timeoutMs
        var frame = 0
        while (frame < maxFrames && System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(4)
            timeNanos += FRAME_NANOS
            scene.render(timeNanos)
            frame++
        }
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}

/* ── shared fixture ──────────────────────────────────────────────────────────── */

internal fun multiSpineEpub(bodies: List<String>): ByteArray {
    val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    val items = bodies.indices.joinToString("") {
        """<item id="c${it + 1}" href="chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
    }
    val refs = bodies.indices.joinToString("") { """<itemref idref="c${it + 1}"/>""" }
    val opf = """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">scene</dc:identifier></metadata>
          <manifest>$items</manifest>
          <spine>$refs</spine>
        </package>"""
    val files = bodies.mapIndexed { i, body ->
        "OEBPS/chapter${i + 1}.xhtml" to
            """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""".encodeToByteArray()
    }
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.setMethod(ZipOutputStream.STORED)
        val entries = listOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to container.encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
        ) + files
        for ((name, data) in entries) {
            zip.putNextEntry(
                ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                },
            )
            zip.write(data)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}
