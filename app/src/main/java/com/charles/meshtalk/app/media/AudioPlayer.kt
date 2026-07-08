package com.charles.meshtalk.app.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Decodes and plays back a received voice clip via a [MediaCodec] decoder + [AudioTrack]. */
class AudioPlayer {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    @Volatile private var stopRequested = false
    private var playThread: Thread? = null

    fun play(bytes: ByteArray, codec: AudioCodec, onFinished: () -> Unit) {
        stop()
        stopRequested = false
        _isPlaying.value = true
        playThread = Thread {
            try {
                decodeAndPlay(bytes, codec)
            } catch (e: Exception) {
                // A corrupted/truncated clip (e.g. a partial BLE transfer) or an unsupported codec
                // must not crash the whole app — an uncaught exception here would kill the process,
                // not just this thread, since it's a bare java.lang.Thread.
            } finally {
                _isPlaying.value = false
                _progress.value = 0f
                onFinished()
            }
        }.also { it.start() }
    }

    /** Stops playback and blocks briefly until the playback thread has released its resources. */
    fun stop() {
        stopRequested = true
        playThread?.join(500)
        playThread = null
    }

    private fun decodeAndPlay(rawBytes: ByteArray, codec: AudioCodec) {
        val (csd, bytes) = AudioEnvelope.decode(rawBytes)
        val format = MediaFormat.createAudioFormat(codec.mimeType, codec.sampleRateHz, 1)
        // MediaCodec requires codec-config buffers (e.g. Opus's OpusHead/pre-skip/pre-roll) set as
        // separate csd-0/csd-1/csd-2 entries on the format before configure(), not fed as ordinary
        // input data — see AudioEnvelope for why these travel separately from the audio bytes, and
        // why a single combined Opus config buffer must be split into its three parts first.
        val csdParts = csd.flatMap { AudioEnvelope.splitOpusConfigBlob(it) }
        csdParts.forEachIndexed { index, blob ->
            format.setByteBuffer("csd-$index", java.nio.ByteBuffer.wrap(blob))
        }
        val decoder = MediaCodec.createDecoderByType(codec.mimeType)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val minBuf = AudioTrack.getMinBufferSize(
            codec.sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(codec.sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Opus/AMR-NB are packet-based — the decoder rejects input that doesn't line up exactly on
        // frame boundaries, so each length-prefixed frame written by AudioRecorder must be fed as
        // its own queueInputBuffer call, not re-chunked to whatever fits the input buffer.
        val frames = readLengthPrefixedFrames(bytes)
        var frameIndex = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            track.play()
            while (!outputDone && !stopRequested) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (frameIndex >= frames.size) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val frame = frames[frameIndex]
                            inputBuffer?.clear()
                            inputBuffer?.put(frame)
                            decoder.queueInputBuffer(inIndex, 0, frame.size, 0, 0)
                            frameIndex++
                            _progress.value = frameIndex.toFloat() / frames.size
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(pcm)
                        track.write(pcm, 0, pcm.size)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { track.stop() } catch (e: Exception) { /* already stopped */ }
            track.release()
            try { decoder.stop() } catch (e: Exception) { /* already stopped */ }
            decoder.release()
        }
    }

    /** Parses the `[len(4 big-endian)][frame bytes]...` stream written by [AudioRecorder]. */
    private fun readLengthPrefixedFrames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.remaining() >= 4) {
            val len = buffer.int
            if (len < 0 || len > buffer.remaining()) break
            frames.add(ByteArray(len).also { buffer.get(it) })
        }
        return frames
    }
}
