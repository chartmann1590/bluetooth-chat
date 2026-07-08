package com.charles.meshtalk.app.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Result of a completed push-to-talk recording. */
data class RecordedClip(val bytes: ByteArray, val codec: AudioCodec, val durationMs: Int)

/**
 * Records from the mic via [AudioRecord] (raw PCM) and encodes incrementally via [MediaCodec],
 * rather than [MediaRecorder] — a walkie-talkie clip needs to end up as a small in-memory byte
 * array ready to hand straight to the mesh, not a container file on disk with muxer/seek
 * machinery this feature has no use for.
 */
class AudioRecorder {
    companion object {
        const val MAX_DURATION_MS = 30_000
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0)
    val elapsedMs: StateFlow<Int> = _elapsedMs.asStateFlow()

    @Volatile private var recording = false
    private var recordThread: Thread? = null

    /** Starts recording on a background thread; [onStopped] is invoked (off the caller's thread)
     * with the finished clip once [stop] is called or [MAX_DURATION_MS] is reached, or with null
     * if the mic/encoder couldn't be initialized. Caller must already hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(onStopped: (RecordedClip?) -> Unit) {
        if (recording) return
        val chosenCodec = AudioCodec.preferredForRecording()
        val sampleRate = chosenCodec.sampleRateHz
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            onStopped(null)
            return
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onStopped(null)
            return
        }

        val format = MediaFormat.createAudioFormat(chosenCodec.mimeType, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, chosenCodec.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)
            // VBR spends more bits on louder/more complex speech and fewer on silence, sounding
            // noticeably clearer than CBR at the same average bitrate.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val encoder = try {
            MediaCodec.createEncoderByType(chosenCodec.mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            audioRecord.release()
            onStopped(null)
            return
        }

        recording = true
        val encodedOutput = ByteArrayOutputStream()
        val csdBuffers = mutableListOf<ByteArray>()
        val startedAtMs = System.currentTimeMillis()
        audioRecord.startRecording()

        recordThread = Thread {
            var failed = false
            try {
                val pcmBuffer = ByteArray(minBufferSize)
                val info = MediaCodec.BufferInfo()
                while (recording) {
                    val elapsed = (System.currentTimeMillis() - startedAtMs).toInt()
                    _elapsedMs.value = elapsed
                    if (elapsed >= MAX_DURATION_MS) {
                        recording = false
                        break
                    }
                    val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read > 0) {
                        _amplitude.value = amplitudeOf(pcmBuffer, read)
                        feedEncoder(encoder, pcmBuffer, read, info, endOfStream = false, output = encodedOutput, csdBuffers = csdBuffers)
                    }
                }
                feedEncoder(encoder, ByteArray(0), 0, info, endOfStream = true, output = encodedOutput, csdBuffers = csdBuffers)
            } catch (e: Exception) {
                // A mic/encoder failure mid-recording must not crash the whole app — an uncaught
                // exception here would kill the process, not just this thread.
                failed = true
            }

            val durationMs = (System.currentTimeMillis() - startedAtMs).toInt()
            try { audioRecord.stop() } catch (e: Exception) { /* already stopped */ }
            audioRecord.release()
            try { encoder.stop() } catch (e: Exception) { /* already stopped */ }
            encoder.release()

            if (failed) {
                onStopped(null)
            } else {
                val envelope = AudioEnvelope.encode(csdBuffers, encodedOutput.toByteArray())
                onStopped(RecordedClip(envelope, chosenCodec, durationMs))
            }
        }.also { it.start() }
    }

    /** Requests the recording stop; the clip is delivered asynchronously via the `onStopped`
     * callback passed to [start] once the background thread finishes draining the encoder. */
    fun stop() {
        recording = false
    }

    private fun feedEncoder(
        encoder: MediaCodec,
        pcm: ByteArray,
        length: Int,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        output: ByteArrayOutputStream,
        csdBuffers: MutableList<ByteArray>
    ) {
        val inIndex = encoder.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inIndex)
            inputBuffer?.clear()
            if (length > 0) inputBuffer?.put(pcm, 0, length)
            val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(inIndex, 0, length, System.nanoTime() / 1000, flags)
        }
        drainEncoder(encoder, info, endOfStream, output, csdBuffers)
    }

    /** Codec-config buffers (e.g. Opus's OpusHead/pre-skip/pre-roll blobs, flagged
     * [MediaCodec.BUFFER_FLAG_CODEC_CONFIG]) must be captured separately from actual encoded audio
     * frames — they need to be handed to the decoder as codec-specific-data on its [MediaFormat],
     * not fed through as ordinary input, or the decoder throws IllegalStateException. See
     * [AudioEnvelope]. */
    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        output: ByteArrayOutputStream,
        csdBuffers: MutableList<ByteArray>
    ) {
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) continue else break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outIndex < 0 -> break
                else -> {
                    val outputBuffer = encoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(chunk)
                        // On-device testing found this device's software Opus encoder only flags
                        // the first of its three config buffers (OpusHead) as BUFFER_FLAG_CODEC_CONFIG
                        // — the pre-skip ("AOPUSDLY") and pre-roll ("AOPUSPRL") buffers come through
                        // as ordinary (unflagged) output, identifiable only by their "AOPUS" magic
                        // prefix. Detect by content, not just the flag, or they leak into the audio
                        // data and the decoder rejects the stream.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || isOpusConfigBuffer(chunk)) {
                            csdBuffers.add(chunk)
                        } else {
                            // Opus (and AMR-NB) are packet/frame-based codecs — each MediaCodec
                            // output buffer is one complete frame, and the decoder rejects input
                            // that doesn't line up exactly on frame boundaries (libopus errors with
                            // OPUS_INVALID_PACKET if fed a partial or multi-frame chunk). Length-
                            // prefix each frame here so AudioPlayer can feed them back one at a time.
                            val lenPrefix = java.nio.ByteBuffer.allocate(4).putInt(chunk.size).array()
                            output.write(lenPrefix)
                            output.write(chunk)
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private val OPUS_CONFIG_MAGIC = byteArrayOf('A'.code.toByte(), 'O'.code.toByte(), 'P'.code.toByte(), 'U'.code.toByte(), 'S'.code.toByte())

    private fun isOpusConfigBuffer(chunk: ByteArray): Boolean {
        if (chunk.size < OPUS_CONFIG_MAGIC.size) return false
        for (i in OPUS_CONFIG_MAGIC.indices) if (chunk[i] != OPUS_CONFIG_MAGIC[i]) return false
        return true
    }

    private fun amplitudeOf(pcm: ByteArray, length: Int): Float {
        var maxAbs = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            maxAbs = maxOf(maxAbs, abs(sample))
            i += 2
        }
        return (maxAbs / 32767f).coerceIn(0f, 1f)
    }
}
