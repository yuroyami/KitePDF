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
    fun create(): KitePlayer = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), SilentOutput)))
}

private object SilentOutput : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "silent"
        override suspend fun create(): AudioSink = SilentSink()
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
