package com.keyvoice.app.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.keyvoice.app.R
import com.keyvoice.app.MainSetupActivity
import com.keyvoice.app.api.RefinementRepository
import com.keyvoice.app.api.TranscriptionRepository
import com.keyvoice.app.audio.AudioRecorder
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.ui.KeyboardViewController
import kotlinx.coroutines.*

/**
 * Main Input Method Service for KeyVoice.
 *
 * This service implements the complete voice-to-text pipeline:
 * 1. Audio recording via MediaRecorder
 * 2. Phase 1: Whisper transcription via Groq API
 * 3. Phase 2: LLM text refinement via Groq API (optional)
 * 4. Text insertion via InputConnection
 */
class VoiceKeyboardService : InputMethodService() {

    private lateinit var prefs: PreferencesManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var transcriptionRepo: TranscriptionRepository
    private lateinit var refinementRepo: RefinementRepository

    private var viewController: KeyboardViewController? = null
    private var serviceScope: CoroutineScope? = null

    // State tracking
    private var currentState = KeyboardViewController.State.IDLE
    private var lastInsertedTextLength = 0
    private var canUndo = false

    // Audio level monitoring
    private val amplitudeHandler = Handler(Looper.getMainLooper())
    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (audioRecorder.isCurrentlyRecording()) {
                val amplitude = audioRecorder.getMaxAmplitude()
                viewController?.audioLevelView?.updateLevel(amplitude)
                amplitudeHandler.postDelayed(this, AudioRecorder.AMPLITUDE_SAMPLE_INTERVAL_MS)
            }
        }
    }

    // Recording duration timer
    private var recordingTimer: CountDownTimer? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        audioRecorder = AudioRecorder(this)
        transcriptionRepo = TranscriptionRepository()
        refinementRepo = RefinementRepository()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        val controller = KeyboardViewController(view)
        viewController = controller

        // Set initial state
        controller.setState(KeyboardViewController.State.IDLE)
        controller.hideUndo()

        // Mic button: toggle recording
        controller.btnMic.setOnClickListener {
            if (prefs.hapticFeedback) {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            handleMicClick()
        }

        // Settings button
        controller.btnSettings.setOnClickListener {
            openSettings()
        }

        // Switch keyboard button
        controller.btnKeyboard.setOnClickListener {
            switchKeyboard()
        }

        // Undo button
        controller.btnUndo.setOnClickListener {
            undoLastInsert()
        }

        return view
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        recordingTimer?.cancel()
        audioRecorder.release()
        super.onDestroy()
    }

    // ─── Mic Click Handler ───────────────────────────────────────────────

    private fun handleMicClick() {
        when (currentState) {
            KeyboardViewController.State.IDLE,
            KeyboardViewController.State.ERROR -> {
                startRecording()
            }
            KeyboardViewController.State.RECORDING -> {
                stopRecordingAndProcess()
            }
            else -> {
                // Ignore clicks during processing
            }
        }
    }

    // ─── Recording ───────────────────────────────────────────────────────

    private fun startRecording() {
        // Check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showError(getString(R.string.error_no_permission))
            return
        }

        // Check API key
        if (!prefs.hasApiKey()) {
            showError(getString(R.string.error_no_api_key))
            return
        }

        try {
            audioRecorder.startRecording()
            currentState = KeyboardViewController.State.RECORDING
            viewController?.setState(KeyboardViewController.State.RECORDING)
            viewController?.hideUndo()
            canUndo = false

            // Start amplitude monitoring
            amplitudeHandler.post(amplitudeRunnable)

            // Start max duration timer
            startDurationTimer()

        } catch (e: Exception) {
            showError(getString(R.string.error_recording_failed))
        }
    }

    private fun stopRecordingAndProcess() {
        // Stop timer and amplitude monitoring
        recordingTimer?.cancel()
        amplitudeHandler.removeCallbacks(amplitudeRunnable)

        val result = audioRecorder.stopRecording()

        result.onSuccess { audioFile ->
            // Start the processing pipeline
            processAudio(audioFile)
        }.onFailure { error ->
            when (error) {
                is AudioRecorder.RecordingTooShortException -> {
                    showError(getString(R.string.error_too_short))
                }
                else -> {
                    showError(getString(R.string.error_recording_failed))
                }
            }
        }
    }

    // ─── Duration Timer ──────────────────────────────────────────────────

    private fun startDurationTimer() {
        val maxDurationMs = prefs.maxRecordingDuration * 1000L
        val countdownStartMs = 10_000L // Show countdown in last 10 seconds

        recordingTimer = object : CountDownTimer(maxDurationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (millisUntilFinished <= countdownStartMs) {
                    val secondsRemaining = (millisUntilFinished / 1000).toInt()
                    viewController?.showCountdown(secondsRemaining)
                }
            }

            override fun onFinish() {
                // Auto-stop recording when max duration reached
                viewController?.hideCountdown()
                if (currentState == KeyboardViewController.State.RECORDING) {
                    stopRecordingAndProcess()
                }
            }
        }.start()
    }

    // ─── Processing Pipeline ─────────────────────────────────────────────

    private fun processAudio(audioFile: java.io.File) {
        val apiKey = prefs.apiKey
        val language = prefs.getWhisperLanguageParam()
        val languageFullName = prefs.getLanguageFullName()
        val whisperModel = prefs.whisperModel
        val llmModel = prefs.llmModel
        val isPhase2Enabled = prefs.isPhase2Enabled
        val systemPrompt = prefs.systemPrompt
        val vocabulary = prefs.vocabulary

        serviceScope?.launch {
            try {
                // ── Phase 1: Whisper Transcription ──
                currentState = KeyboardViewController.State.PROCESSING_PHASE1
                viewController?.setState(KeyboardViewController.State.PROCESSING_PHASE1)

                val transcriptionResult = withContext(Dispatchers.IO) {
                    transcriptionRepo.transcribe(audioFile, apiKey, whisperModel, language, vocabulary)
                }

                // Cleanup audio file after upload
                audioRecorder.cleanupFile()

                val rawText = transcriptionResult.getOrElse { error ->
                    showError(error.message ?: getString(R.string.error_api_generic, 0))
                    return@launch
                }

                // ── Phase 2: LLM Refinement (optional) ──
                val finalText: String
                if (isPhase2Enabled) {
                    currentState = KeyboardViewController.State.PROCESSING_PHASE2
                    viewController?.setState(KeyboardViewController.State.PROCESSING_PHASE2)

                    val refinementResult = withContext(Dispatchers.IO) {
                        refinementRepo.refine(rawText, apiKey, llmModel, languageFullName, systemPrompt)
                    }

                    finalText = refinementResult.getOrElse { error ->
                        // If Phase 2 fails, fall back to raw transcription
                        rawText
                    }
                } else {
                    finalText = rawText
                }

                // ── Insert text ──
                insertText(finalText)

                // Return to idle
                currentState = KeyboardViewController.State.IDLE
                viewController?.setState(KeyboardViewController.State.IDLE)

            } catch (e: CancellationException) {
                throw e // Don't catch cancellation
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_api_generic, 0))
            }
        }
    }

    // ─── Text Insertion ──────────────────────────────────────────────────

    private fun insertText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1) // 1 = cursor position after text
        lastInsertedTextLength = text.length
        canUndo = true
        viewController?.showUndo()
    }

    private fun undoLastInsert() {
        if (!canUndo || lastInsertedTextLength <= 0) return

        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(lastInsertedTextLength, 0)
        lastInsertedTextLength = 0
        canUndo = false
        viewController?.hideUndo()
    }

    // ─── Settings ────────────────────────────────────────────────────────

    private fun openSettings() {
        val intent = Intent(this, MainSetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        requestHideSelf(0)
    }

    private fun switchKeyboard() {
        val switched = switchToNextInputMethod(false)
        if (!switched) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    // ─── Error Handling ──────────────────────────────────────────────────

    private fun showError(message: String) {
        currentState = KeyboardViewController.State.ERROR
        viewController?.setState(KeyboardViewController.State.ERROR, message)

        // Auto-clear error after 4 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (currentState == KeyboardViewController.State.ERROR) {
                currentState = KeyboardViewController.State.IDLE
                viewController?.setState(KeyboardViewController.State.IDLE)
            }
        }, 4000)
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Reset undo state when user starts a new input session
        if (!restarting) {
            canUndo = false
            viewController?.hideUndo()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Stop any ongoing recording
        if (currentState == KeyboardViewController.State.RECORDING) {
            recordingTimer?.cancel()
            amplitudeHandler.removeCallbacks(amplitudeRunnable)
            audioRecorder.release()
            audioRecorder = AudioRecorder(this)
            currentState = KeyboardViewController.State.IDLE
            viewController?.setState(KeyboardViewController.State.IDLE)
        }
    }
}
