package com.keyvoice.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Manages audio recording using MediaRecorder.
 * Outputs M4A/AAC files to the app's cache directory.
 */
class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0L

    companion object {
        /** Minimum recording duration in milliseconds */
        const val MIN_RECORDING_DURATION_MS = 500L

        /** Audio sampling interval for amplitude in milliseconds */
        const val AMPLITUDE_SAMPLE_INTERVAL_MS = 100L
    }

    /**
     * Starts audio recording.
     * @return the output file where audio will be saved
     * @throws IllegalStateException if already recording
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     */
    fun startRecording(): File {
        if (isRecording) {
            throw IllegalStateException("Already recording")
        }

        // Create output file in cache directory
        val file = File(context.cacheDir, "keyvoice_recording_${System.currentTimeMillis()}.m4a")
        outputFile = file

        // Configure MediaRecorder
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setAudioChannels(1) // Mono for speech
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        mediaRecorder = recorder
        isRecording = true
        recordingStartTime = System.currentTimeMillis()

        return file
    }

    /**
     * Stops recording and returns the recorded audio file.
     * @return Result containing the audio file, or an error if recording was too short
     */
    fun stopRecording(): Result<File> {
        if (!isRecording) {
            return Result.failure(IllegalStateException("Not recording"))
        }

        val duration = System.currentTimeMillis() - recordingStartTime

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: RuntimeException) {
            // MediaRecorder may throw if stopped too quickly
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            cleanupFile()
            return Result.failure(e)
        }

        mediaRecorder = null
        isRecording = false

        // Check minimum duration
        if (duration < MIN_RECORDING_DURATION_MS) {
            cleanupFile()
            return Result.failure(RecordingTooShortException())
        }

        val file = outputFile
        return if (file != null && file.exists() && file.length() > 0) {
            Result.success(file)
        } else {
            Result.failure(IllegalStateException("Recording file is empty or missing"))
        }
    }

    /**
     * Returns the current maximum amplitude of the audio being recorded.
     * Used for the audio level visualizer.
     * @return amplitude value (0–32767), or 0 if not recording
     */
    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) {
                mediaRecorder?.maxAmplitude ?: 0
            } else {
                0
            }
        } catch (e: IllegalStateException) {
            0
        }
    }

    /**
     * Returns whether recording is currently in progress.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Returns the elapsed recording time in milliseconds.
     */
    fun getElapsedTimeMs(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - recordingStartTime
        } else {
            0L
        }
    }

    /**
     * Releases all resources. Call when done with the recorder.
     */
    fun release() {
        try {
            if (isRecording) {
                mediaRecorder?.stop()
            }
            mediaRecorder?.release()
        } catch (_: Exception) {
            // Ignore release errors
        }
        mediaRecorder = null
        isRecording = false
    }

    /**
     * Deletes the temporary recording file.
     * Should be called after the file has been uploaded to the API.
     */
    fun cleanupFile() {
        outputFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        outputFile = null
    }

    /** Exception indicating that the recording was too short (< 0.5s) */
    class RecordingTooShortException : Exception("Recording too short (< 0.5s)")
}
