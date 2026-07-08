package com.charles.meshtalk.app.media

import android.media.MediaCodecList
import android.media.MediaFormat
import com.charles.meshtalk.app.ble.AudioCodecId

/**
 * Maps a push-to-talk speech codec to Android's MediaCodec MIME type / sample rate / bitrate.
 * Opus is preferred (much better quality per bit at these low bitrates than AMR-NB or AAC-LC),
 * but MediaCodec has only offered a built-in Opus encoder since API 29; AMR-NB (available via
 * MediaCodec since API 1, narrowband/lower quality) is the universal fallback for older devices
 * or encoders that don't expose Opus.
 */
enum class AudioCodec(val id: AudioCodecId, val mimeType: String, val sampleRateHz: Int, val bitrate: Int) {
    // 16kbps was unintelligibly muddy in on-device testing; on-device BLE testing showed transfer
    // is far faster than the original conservative throughput estimate, so there's plenty of
    // headroom to spend more bits on quality. 32kbps at 24kHz (Opus's "super-wideband" mode) is a
    // large step up in clarity for speech, still well within a 30s clip's ~120KB size budget.
    OPUS(AudioCodecId.OPUS, MediaFormat.MIMETYPE_AUDIO_OPUS, 24_000, 32_000),
    AMR_NB(AudioCodecId.AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8_000, 12_200);

    companion object {
        fun forId(id: AudioCodecId): AudioCodec = entries.first { it.id == id }

        /** Picks the best codec this device actually has an encoder for, falling back to AMR-NB
         * (universally available) if Opus isn't exposed by this device's MediaCodec. */
        fun preferredForRecording(): AudioCodec = if (isEncoderAvailable(OPUS.mimeType)) OPUS else AMR_NB

        fun isEncoderAvailable(mimeType: String): Boolean = codecInfos().any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }

        fun isDecoderAvailable(mimeType: String): Boolean = codecInfos().any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }

        private fun codecInfos() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asList()
    }
}
