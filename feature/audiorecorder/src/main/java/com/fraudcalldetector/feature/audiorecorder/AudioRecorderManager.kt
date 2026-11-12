package com.fraudcalldetector.feature.audiorecorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.fraudcalldetector.domain.repository.CallRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @deprecated Replaced by [AudioCaptureManager] + [AudioCaptureRepository].
 *
 * This class remains only for reference during the migration period.
 * It is no longer bound by Hilt — use [AudioCaptureRepository] everywhere.
 * Will be removed in a future cleanup pass.
 */
@Deprecated("Use AudioCaptureManager / AudioCaptureRepository instead")
@Singleton
class AudioRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callRepository: CallRepository
) {
    private var recorder: MediaRecorder? = null
    private var currentOutputPath: String? = null
    private var currentCallId: String? = null

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun startRecording(callId: String) {
        if (_state.value is RecordingState.Recording) {
            Timber.w("Already recording, ignoring start request")
            return
        }

        val outputFile = File(context.filesDir, "recordings/call_$callId.mp4")
            .also { it.parentFile?.mkdirs() }

        currentOutputPath = outputFile.absolutePath
        currentCallId = callId

        recorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(outputFile.absolutePath)
            try {
                prepare()
                start()
                _state.value = RecordingState.Recording(callId, outputFile.absolutePath)
                Timber.d("Recording started: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start recording")
                _state.value = RecordingState.Error(e.message ?: "Recording failed")
                release()
            }
        }
    }

    suspend fun stopRecording(): String? {
        val path = currentOutputPath
        val callId = currentCallId
        try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null

            if (path != null && callId != null) {
                // Update call record with recording path
                callRepository.getCallById(callId)?.let { call ->
                    callRepository.updateCall(call.copy(recordingPath = path))
                }
                _state.value = RecordingState.Stopped(callId, path)
                Timber.d("Recording stopped: $path")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop recording")
            _state.value = RecordingState.Error(e.message ?: "Stop failed")
        }
        currentOutputPath = null
        currentCallId = null
        return path
    }

    fun cancelRecording() {
        recorder?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        recorder = null
        currentOutputPath?.let { File(it).delete() }
        currentOutputPath = null
        currentCallId = null
        _state.value = RecordingState.Idle
        Timber.d("Recording cancelled")
    }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}

sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val callId: String, val outputPath: String) : RecordingState()
    data class Stopped(val callId: String, val outputPath: String) : RecordingState()
    data class Error(val message: String) : RecordingState()
}
