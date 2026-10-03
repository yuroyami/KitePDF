package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Players for the tests: FFmpeg reads and decodes, and the audio goes nowhere. A CI machine has no
 * sound device, and the desktop output fails to open the media without one.
 */
internal object SilentPlayers {
    fun create(): KitePlayer = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), SilentOutput(::SilentSink))))

    /**
     * A player whose output takes the samples as fast as a sound device plays them, and drops
     * them, so its position moves with the clock. The output of [create] never asks for a
     * sample, and a player on it stays at the position it opened or sought to.
     */
    fun realTime(): KitePlayer = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), SilentOutput(::RealTimeSink))))
}

private class SilentOutput(private val sink: () -> AudioSink) : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "silent"
        override suspend fun create(): AudioSink = sink()
    }
}

/** A device that takes any format and never asks for a sample. */
private class SilentSink : AudioSink {
    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat = request
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun drain() = Unit
    override suspend fun setPaused(paused: Boolean): Boolean = true
    override val deviceBufferFrames: Int = 1024
    override fun latencyNanos(): Long = 0
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
    override val events: Flow<AudioSinkEvent> = emptyFlow()
    override fun close() = Unit
}

/** A device that asks for 10 ms of samples every 10 ms on a thread of its own, and drops them. */
private class RealTimeSink : AudioSink {
    @Volatile private var format: AudioFormat? = null
    @Volatile private var render: AudioRenderCallback? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var closed = false
    private var thread: Thread? = null

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        format = request
        this.render = render
        return request
    }

    override suspend fun start() {
        running = true
        if (thread == null) thread = Thread(::pump, "real-time-silent-sink").apply { isDaemon = true; start() }
    }

    private fun pump() {
        var next = System.nanoTime()
        while (!closed) {
            val fmt = format
            val callback = render
            if (running && !paused && fmt != null && callback != null) {
                callback.onRender(Dropped(fmt), fmt.sampleRate / 100, MonotonicClock.System.nanos())
            }
            next += PERIOD_NANOS
            val wait = next - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
        }
    }

    override suspend fun stop() {
        running = false
    }

    override suspend fun drain() = Unit
    override suspend fun setPaused(paused: Boolean): Boolean {
        this.paused = paused
        return true
    }
    override val deviceBufferFrames: Int = 1024
    override fun latencyNanos(): Long = 0
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
    override val events: Flow<AudioSinkEvent> = emptyFlow()
    override fun close() {
        closed = true
    }

    private class Dropped(override val format: AudioFormat) : AudioSinkBuffer {
        override fun writeInterleaved(samples: FloatArray, offset: Int, frames: Int, at: Int) = Unit
        override fun writeSilence(frames: Int, at: Int) = Unit
    }

    private companion object {
        const val PERIOD_NANOS = 10_000_000L
    }
}
