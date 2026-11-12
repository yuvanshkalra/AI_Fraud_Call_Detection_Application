package com.fraudcalldetector.domain.model

/**
 * Immutable configuration for an audio capture session.
 *
 * Passed to [AudioCaptureSource] before recording starts.
 * All fields have sensible defaults for call-fraud analysis.
 */
data class AudioCaptureConfig(

    // ── Encoding ──────────────────────────────────────────────────────────────

    /** Output container format. */
    val outputFormat  : AudioOutputFormat = AudioOutputFormat.MPEG_4,

    /** Codec used inside the container. */
    val audioEncoder  : AudioEncoderType  = AudioEncoderType.AAC,

    /**
     * Sample rate in Hz.
     * 16 000 Hz is the sweet spot for speech recognition.
     * 44 100 Hz gives higher fidelity but larger files.
     */
    val sampleRateHz  : Int               = 16_000,

    /** Bitrate in bits/second. Lower is sufficient for speech. */
    val bitRateBps    : Int               = 64_000,

    /** Number of audio channels (1 = mono, 2 = stereo). */
    val channelCount  : Int               = 1,

    // ── Chunking ─────────────────────────────────────────────────────────────

    /**
     * How long each audio chunk spans before a new file is started.
     * null = no chunking (single file for the entire call).
     * Typical value: 30_000 ms (30 s) for streaming STT analysis.
     */
    val chunkDurationMs: Long?            = 30_000L,

    // ── Source ────────────────────────────────────────────────────────────────

    /** Which microphone / audio path to use. */
    val audioSource   : AudioSourceType   = AudioSourceType.VOICE_COMMUNICATION,

    // ── Storage ───────────────────────────────────────────────────────────────

    /**
     * Sub-directory inside the app's private filesDir where recordings land.
     * e.g. "recordings" → <filesDir>/recordings/call_<callId>_chunk_0.mp4
     */
    val storageSubDir : String            = "recordings"
) {
    companion object {
        /** Default config tuned for speech-to-text pipelines. */
        val DEFAULT = AudioCaptureConfig()

        /** Higher-fidelity config for general audio analysis. */
        val HIGH_QUALITY = AudioCaptureConfig(
            sampleRateHz   = 44_100,
            bitRateBps     = 128_000,
            chunkDurationMs = 60_000L
        )

        /** No-chunk config — one file per call. */
        val SINGLE_FILE = AudioCaptureConfig(chunkDurationMs = null)

        /** Config for tests / CI (no real audio hardware needed). */
        val MOCK = AudioCaptureConfig(
            audioSource     = AudioSourceType.MOCK,
            chunkDurationMs = 5_000L   // short chunks for fast unit tests
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Enum helpers
// ─────────────────────────────────────────────────────────────────────────────

enum class AudioOutputFormat {
    MPEG_4,      // android.media.MediaRecorder.OutputFormat.MPEG_4
    THREE_GPP,   // android.media.MediaRecorder.OutputFormat.THREE_GPP
    AAC_ADTS,    // android.media.MediaRecorder.OutputFormat.AAC_ADTS
    RAW_AMR      // android.media.MediaRecorder.OutputFormat.AMR_NB (legacy)
}

enum class AudioEncoderType {
    AAC,         // android.media.MediaRecorder.AudioEncoder.AAC
    AAC_ELD,     // android.media.MediaRecorder.AudioEncoder.AAC_ELD
    HE_AAC,      // android.media.MediaRecorder.AudioEncoder.HE_AAC
    AMR_NB,      // android.media.MediaRecorder.AudioEncoder.AMR_NB
    OPUS         // android.media.MediaRecorder.AudioEncoder.OPUS (API 29+)
}

enum class AudioSourceType {
    VOICE_COMMUNICATION, // android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
    MIC,                 // android.media.MediaRecorder.AudioSource.MIC
    VOICE_RECOGNITION,   // android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
    MOCK                 // pseudo-source for tests; no real hardware used
}
