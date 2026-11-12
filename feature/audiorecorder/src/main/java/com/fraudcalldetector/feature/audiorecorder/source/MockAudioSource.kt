package com.fraudcalldetector.feature.audiorecorder.source

import com.fraudcalldetector.domain.audio.AudioCaptureException
import com.fraudcalldetector.domain.audio.AudioCaptureSource
import com.fraudcalldetector.domain.audio.SegmentResult
import com.fraudcalldetector.domain.model.AudioCaptureConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Deterministic [AudioCaptureSource] for unit tests and CI.
 *
 * Writes a minimal valid MP4/AAC file header (enough bytes for the STT module
 * to check file existence and size) without touching any real hardware.
 * The "recording" starts immediately; [stopSegment] writes the file and returns.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Usage in tests
 * ─────────────────────────────────────────────────────────────────────────────
 * Inject via Hilt test module or construct directly:
 *   val source = MockAudioSource()
 *   source.startSegment("/tmp/test_chunk_0.mp4", AudioCaptureConfig.MOCK)
 *   delay(config.chunkDurationMs ?: 5_000)
 *   val result = source.stopSegment()
 */
class MockAudioSource @Inject constructor() : AudioCaptureSource {

    private val _isRecording   = AtomicBoolean(false)
    private var segmentStartMs : Long    = 0L
    private var outputPath     : String? = null

    override val isRecording: Boolean get() = _isRecording.get()

    // ─────────────────────────────────────────────────────────────────────────
    // AudioCaptureSource
    // ─────────────────────────────────────────────────────────────────────────

    override fun startSegment(outputPath: String, config: AudioCaptureConfig) {
        if (_isRecording.get()) {
            Timber.w("MockAudioSource: startSegment called while already recording — ignoring")
            return
        }
        File(outputPath).parentFile?.mkdirs()
        this.outputPath     = outputPath
        this.segmentStartMs = System.currentTimeMillis()
        _isRecording.set(true)
        Timber.d("MockAudioSource: segment started → $outputPath")
    }

    override fun stopSegment(): SegmentResult {
        if (!_isRecording.get()) {
            throw AudioCaptureException("MockAudioSource: stopSegment called with no active segment")
        }

        val path  = outputPath ?: throw AudioCaptureException("MockAudioSource: outputPath is null")
        val endMs = System.currentTimeMillis()

        // Write a minimal silent audio payload so file.exists() && file.length() > 0.
        writeSilentFile(path)

        _isRecording.set(false)
        val size = File(path).length()
        Timber.d("MockAudioSource: segment stopped, size=${size}B, duration=${endMs - segmentStartMs}ms")
        return SegmentResult(
            filePath    = path,
            startTimeMs = segmentStartMs,
            endTimeMs   = endMs,
            sizeBytes   = size
        )
    }

    override fun cancelSegment() {
        if (!_isRecording.get()) return
        outputPath?.let { File(it).delete() }
        outputPath = null
        _isRecording.set(false)
        Timber.d("MockAudioSource: segment cancelled")
    }

    override fun release() {
        cancelSegment()
        Timber.d("MockAudioSource: released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a 64-byte buffer of zeroes — enough to satisfy "file exists and
     * has content" checks without requiring real audio encoding.
     *
     * Tests that need a real decodable file should override this method or
     * provide a pre-baked test asset via a custom [AudioCaptureSource].
     */
    private fun writeSilentFile(path: String) {
        try {
            FileOutputStream(path).use { out ->
                out.write(ByteArray(SILENT_PAYLOAD_BYTES))
            }
        } catch (e: Exception) {
            throw AudioCaptureException("MockAudioSource: failed to write silent file: ${e.message}", e)
        }
    }

    companion object {
        /** Number of zero-bytes written as the mock audio payload. */
        const val SILENT_PAYLOAD_BYTES = 64
    }
}
