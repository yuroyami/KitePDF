package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.withLock
import kotlin.concurrent.Volatile

/**
 * The frames of an animated GIF, APNG, WebP, AVIF or JPEG XL that a book or a comic shows (#939).
 *
 * [KiteImageData.animation] carries one for an image whose header names more than one frame,
 * which costs a header probe and nothing more: the image itself is still its first frame, and
 * every render draws that frame until a player moves [frameIndex]. A player such as the Compose
 * viewer calls [prepare] off the main thread, then sets [frameIndex] from [frameAt] as time
 * passes and draws the page again; [release] drops the frames when the page leaves the screen.
 *
 * [loopCount] is the file's: 0 loops forever, n plays the frames n times and stops on the last.
 */
public class KiteImageAnimation internal constructor(
    private val bytes: ByteArray,
    /** How many frames the file names. */
    public val frameCount: Int,
    /** How many times the frames play: 0 for ever. */
    public val loopCount: Long,
    private val applyOrientation: Boolean,
) {
    private val lock = KiteLock()
    private var frames: List<KiteImageData>? = null
    private var delays: IntArray? = null

    /**
     * The frame that a render draws in place of the image, from 0, the first. Set it from
     * [frameAt]; a frame that [prepare] has not decoded draws as the first.
     */
    @Volatile
    public var frameIndex: Int = 0

    /** True once [prepare] has decoded the frames. */
    public val isPrepared: Boolean get() = lock.withLock { frames != null }

    /** How long each frame shows, in milliseconds, once [prepare] has decoded them; null before. */
    public val frameDelays: IntArray? get() = lock.withLock { delays?.copyOf() }

    /** The RGBA bytes the decoded frames take: four a pixel of each frame. */
    public val frameBytes: Long get() = lock.withLock { frames?.sumOf { it.width.toLong() * it.height * 4 } ?: 0L }

    /**
     * Decodes every frame and its delay, unless they would take more than [maxBytes] of RGBA, which
     * the header's size and frame count tell before a decode. Slow: call it off the main thread.
     * Returns true when the frames are ready, false when the file is over the budget or does not
     * decode, and the image then stays its first frame.
     */
    public fun prepare(maxBytes: Long = DEFAULT_MAX_BYTES): Boolean {
        lock.withLock { if (frames != null) return true }
        val info = runCatching { ImageKodec.probe(bytes) }.getOrNull() ?: return false
        if (info.width.toLong() * info.height * 4 * frameCount > maxBytes) return false
        val animation = runCatching { ImageKodec.decodeAnimation(bytes, applyOrientation) }
            .onFailure { kiteWarn { "an animated image did not decode: ${it.message}" } }
            .getOrNull() ?: return false
        if (animation.frames.size < 2) return false
        val decoded = animation.frames.map { it.bitmap.toKiteImageData().copy(interpolate = true) }
        val times = IntArray(animation.frames.size) { animation.frames[it].delayMillis.coerceAtLeast(MIN_DELAY_MS) }
        lock.withLock {
            if (frames == null) { frames = decoded; delays = times }
        }
        return true
    }

    /** Drops the decoded frames, and goes back to the first. */
    public fun release() {
        frameIndex = 0
        lock.withLock { frames = null; delays = null }
    }

    /** The frame to draw for [frameIndex], or null to draw the image itself, the first frame. */
    public fun currentFrame(): KiteImageData? {
        val index = frameIndex
        if (index <= 0) return null
        return lock.withLock { frames?.getOrNull(index) }
    }

    /**
     * The frame that shows [elapsedMillis] after the animation started, honouring [loopCount]: once
     * the last loop ends, its last frame stays. 0 before [prepare].
     */
    public fun frameAt(elapsedMillis: Long): Int {
        val times = lock.withLock { delays } ?: return 0
        val loop = times.sumOf { it.toLong() }
        if (loop <= 0) return 0
        if (loopCount > 0 && elapsedMillis >= loop * loopCount) return times.lastIndex
        var t = elapsedMillis.coerceAtLeast(0) % loop
        for ((i, d) in times.withIndex()) {
            if (t < d) return i
            t -= d
        }
        return times.lastIndex
    }

    /** Whether the animation has played all its loops at [elapsedMillis]; never for one that loops for ever. */
    public fun isFinished(elapsedMillis: Long): Boolean {
        if (loopCount <= 0) return false
        val times = lock.withLock { delays } ?: return true
        return elapsedMillis >= times.sumOf { it.toLong() } * loopCount
    }

    public companion object {
        /** The RGBA bytes one animation's frames may take by default: 64 MiB. */
        public const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024

        /** The shortest delay a frame keeps, as browsers clamp a delay of 0 (ImageKodec already makes it 100 ms for GIF). */
        private const val MIN_DELAY_MS = 20

        /**
         * The animation of the image file [bytes], or null for a still image: what
         * [KiteImageData.fromEncodedImage] attaches, for a page that knows its image file before it
         * draws. It reads the header only.
         */
        public fun probe(bytes: ByteArray, applyOrientation: Boolean = true): KiteImageAnimation? = of(bytes, applyOrientation)

        internal fun of(bytes: ByteArray, applyOrientation: Boolean): KiteImageAnimation? {
            val info = runCatching { ImageKodec.probe(bytes) }.getOrNull() ?: return null
            if (info.frameCount < 2) return null
            return KiteImageAnimation(bytes, info.frameCount, info.loopCount, applyOrientation)
        }
    }
}
