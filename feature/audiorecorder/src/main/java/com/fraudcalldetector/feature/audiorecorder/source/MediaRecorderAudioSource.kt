package com.fraudcalldetector.feature.audiorecorder.source

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.fraudcalldetector.domain.audio.AudioCaptureException
import com.fraudcalldetector.domain.audio.AudioCaptureSource
import com.fraudcalldetector.domain.audio.SegmentResult
import com.fraudcalldetector.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * [AudioCaptureSource] backed by Android [MediaRecorder].
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Threading
 * ─────────────────────────────────────────────────────────────────────────────
 * [MediaRecorder] is not thread-safe; [startSegment] and [stopSegment]
 * must be called from the same single-threaded dispatcher.
 * [AudioCaptureManager] enforces this by using Dispatchers.IO confined to
 * a single thread for all recorder interactions.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Replaceable
 * ─────────────────────────────────────────────────────────────────────────────
 * Swap this class for a different [AudioCaptureSource] in the DI module
 * (e.g. an OEM-specific dual-stream capture) without touching any call-site.
 */
class MediaRecorderAudioSource @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioCaptureSource {

    private var recorder        : MediaRecorder? = null
    private var segmentStartMs  : Long           = 0L
    private var lastOutputPath  : String?        = null
    private val _isRecording    = AtomicBoolean(false)

    override val isRecording: Boolean get() = _isRecording.get()

    // ─────────────────────────────────────────────────────────────────────────
    // AudioCaptureSource
    // ─────────────────────────────────────────────────────────────────────────

    override fun startSegment(outputPath: String, config: AudioCaptureConfig) {
        if (_isRecording.get()) {
            Timber.w("MediaRecorderAudioSource: startSegment called while already recording — ignoring")
            return
        }

        lastOutputPath = outputPath          // tracked here so stopSegment() can retrieve it
        File(outputPath).parentFile?.mkdirs()

        val rec = createRecorder()
        try {
            rec.setAudioSource(config.audioSource.toMediaSource())
            rec.setOutputFormat(config.outputFormat.toMediaFormat())
            rec.setAudioEncoder(config.audioEncoder.toMediaEncoder())
            rec.setAudioSamplingRate(config.sampleRateHz)
            rec.setAudioEncodingBitRate(config.bitRateBps)
            rec.setAudioChannels(config.channelCount)
            rec.setOutputFile(outputPath)
            rec.prepare()
            rec.start()
            segmentStartMs = System.currentTimeMillis()
            recorder = rec
            _isRecording.set(true)
            Timber.d("MediaRecorderAudioSource: segment started → $outputPath")
        } catch (e: Exception) {
            runCatching { rec.release() }
            recorder = null
            _isRecording.set(false)
            throw AudioCaptureException("Failed to start MediaRecorder segment: ${e.message}", e)
        }
    }

    override fun stopSegment(): SegmentResult {
        val rec = recorder
            ?: throw AudioCaptureException("stopSegment called with no active recorder")

        val endMs = System.currentTimeMillis()
        val startMs = segmentStartMs

        return try {
            rec.stop()
            rec.release()
            recorder = null
            _isRecording.set(false)

            // Retrieve the output path from the last startSegment call.
            // We pass it through a local variable set during start.
            val file = lastOutputPath ?: throw AudioCaptureException("Output path not tracked")
            val size = File(file).length()

            Timber.d("MediaRecorderAudioSource: segment stopped, size=${size}B, duration=${endMs - startMs}ms")
            SegmentResult(filePath = file, startTimeMs = startMs, endTimeMs = endMs, sizeBytes = size)
        } catch (e: AudioCaptureException) {
            throw e
        } catch (e: Exception) {
            runCatching { rec.release() }
            recorder = null
            _isRecording.set(false)
            throw AudioCaptureException("Failed to stop MediaRecorder segment: ${e.message}", e)
        }
    }

    override fun cancelSegment() {
        val rec = recorder ?: return
        try {
            rec.stop()
        } catch (_: Exception) { /* best-effort */ }
        rec.release()
        recorder = null
        _isRecording.set(false)
        lastOutputPath?.let { File(it).delete() }
        lastOutputPath = null
        Timber.d("MediaRecorderAudioSource: segment cancelled")
    }

    override fun release() {
        cancelSegment()
        Timber.d("MediaRecorderAudioSource: released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Config → MediaRecorder constant mapping extensions
// ─────────────────────────────────────────────────────────────────────────────

internal fun AudioOutputFormat.toMediaFormat(): Int = when (this) {
    AudioOutputFormat.MPEG_4   -> MediaRecorder.OutputFormat.MPEG_4
    AudioOutputFormat.THREE_GPP -> MediaRecorder.OutputFormat.THREE_GPP
    AudioOutputFormat.AAC_ADTS  -> MediaRecorder.OutputFormat.AAC_ADTS
    AudioOutputFormat.RAW_AMR   -> MediaRecorder.OutputFormat.AMR_NB
}

internal fun AudioEncoderType.toMediaEncoder(): Int = when (this) {
    AudioEncoderType.AAC     -> MediaRecorder.AudioEncoder.AAC
    AudioEncoderType.AAC_ELD -> MediaRecorder.AudioEncoder.AAC_ELD
    AudioEncoderType.HE_AAC  -> MediaRecorder.AudioEncoder.HE_AAC
    AudioEncoderType.AMR_NB  -> MediaRecorder.AudioEncoder.AMR_NB
    AudioEncoderType.OPUS    ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaRecorder.AudioEncoder.OPUS
        else MediaRecorder.AudioEncoder.AAC  // fallback on < API 29
}

internal fun AudioSourceType.toMediaSource(): Int = when (this) {
    AudioSourceType.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    AudioSourceType.MIC                 -> MediaRecorder.AudioSource.MIC
    AudioSourceType.VOICE_RECOGNITION   -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    AudioSourceType.MOCK                -> MediaRecorder.AudioSource.MIC  // never reached at runtime
}
