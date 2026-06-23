package com.keyvoice.app.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.accessibilityservice.InputMethod.AccessibilityInputConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.keyvoice.app.R
import com.keyvoice.app.audio.AudioRecorder
import com.keyvoice.app.dictation.DictationHistoryItems
import com.keyvoice.app.dictation.DictationPipeline
import com.keyvoice.app.ime.InsertionFormatter
import com.keyvoice.app.ime.TextCorrectionLearner
import com.keyvoice.app.settings.DictationHistoryStore
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.update.KeyVoiceUpdateCoordinator
import com.keyvoice.app.ui.KeyVoiceAccessibilityOverlayView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class KeyVoiceAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "KeyVoiceAccessibility"
        private const val CONTEXT_CHARS = 80
        private const val DRAG_SLOP_PX = 12
        private const val OVERLAY_WIDTH_DP = 304
        private const val OVERLAY_HEIGHT_DP = 84
        private const val SUCCESS_FEEDBACK_MS = 3_000L
        private const val ERROR_FEEDBACK_MS = 7_000L
        private const val PROCESSING_STATUS_TICK_MS = 1_000L
        private const val PROCESSING_SLOW_MS = 8_000L
        private const val PROCESSING_VERY_SLOW_MS = 20_000L
        private const val VISIBILITY_SYNC_DELAY_MS = 80L
        private const val SHORTCUT_START_RETRY_DELAY_MS = 110L
        private const val SHORTCUT_START_TIMEOUT_MS = 3_000L
        private const val VOLUME_SHORTCUT_COOLDOWN_MS = 900L
        private const val LEARNING_MAX_GROWTH_CHARS = 120
        private const val MAX_ACCESSIBILITY_TEXT_READ_CHARS = 6_000
        private const val INSERTION_VERIFY_CHARS = 160
    }

    private enum class State {
        IDLE,
        ACTIVATING,
        RECORDING,
        PROCESSING,
        COMPLETE
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var dictationPipeline: DictationPipeline
    private lateinit var historyStore: DictationHistoryStore
    private lateinit var windowManager: WindowManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var state = State.IDLE
    private var inputMethod: InputMethod? = null
    private var latestEditorInfo: EditorInfo? = null
    private var bubbleView: View? = null
    private var bubbleOverlay: KeyVoiceAccessibilityOverlayView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleTitleText: String? = null
    private var bubbleSubtitleText: String? = null
    private var bubbleCompletionIsError = false
    private var processingStatusStartedAtMillis = 0L
    private var processingStatusTitle = ""
    private var processingStatusSubtitle = ""
    private var processingStatusSlowSubtitle = ""
    private var processingStatusVerySlowSubtitle = ""
    private var recordingTimer: CountDownTimer? = null
    private var pendingShortcutActivation = false
    private var shortcutActivationDeadlineMillis = 0L
    private var destroyPending = false
    private var stopRequested = false
    private var accessibilityButtonAvailable = false
    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private var volumeShortcutActive = false
    private var lastVolumeShortcutAtMillis = 0L
    private var trackedAccessibilityInsertion: TrackedAccessibilityInsertion? = null
    private var lastAccessibilityLearnedTerms: List<String> = emptyList()

    private val syncBubbleRunnable = Runnable { syncRecordingButtonVisibility() }
    private val shortcutActivationRunnable = Runnable { tryStartFromShortcut() }
    private val accessibilityAmplitudeRunnable = object : Runnable {
        override fun run() {
            if (state == State.RECORDING && audioRecorder.isCurrentlyRecording()) {
                bubbleOverlay?.updateAudioAmplitude(audioRecorder.getMaxAmplitude())
                mainHandler.postDelayed(this, AudioRecorder.AMPLITUDE_SAMPLE_INTERVAL_MS)
            }
        }
    }
    private val processingStatusRunnable = object : Runnable {
        override fun run() {
            if (state != State.PROCESSING || processingStatusStartedAtMillis == 0L) return
            updateProcessingStatus()
            mainHandler.postDelayed(this, PROCESSING_STATUS_TICK_MS)
        }
    }

    private val accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            handleShortcutInvocation()
        }

        override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
            accessibilityButtonAvailable = available
            Log.i(TAG, "Accessibility button availability changed: $available")
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        audioRecorder = AudioRecorder(this)
        dictationPipeline = DictationPipeline()
        historyStore = DictationHistoryStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceScope.launch {
            runCatching {
                KeyVoiceUpdateCoordinator(this@KeyVoiceAccessibilityService, prefs)
                    .checkForUpdate(manual = false, notifyIfAvailable = true)
            }.onFailure { error ->
                Log.d(TAG, "Automatic update check skipped", error)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs.registerPreferenceListener(this)
        configureServiceFlags()
        runCatching {
            accessibilityButtonAvailable = accessibilityButtonController.isAccessibilityButtonAvailable
            accessibilityButtonController.registerAccessibilityButtonCallback(
                accessibilityButtonCallback,
                mainHandler
            )
            Log.i(TAG, "Accessibility button available: $accessibilityButtonAvailable")
        }
        lastVolumeShortcutAtMillis = SystemClock.uptimeMillis()
        handleShortcutInvocation()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeUpPressed = true
                } else {
                    volumeDownPressed = true
                }

                if (volumeUpPressed && volumeDownPressed) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastVolumeShortcutAtMillis > VOLUME_SHORTCUT_COOLDOWN_MS) {
                        lastVolumeShortcutAtMillis = now
                        volumeShortcutActive = true
                        handleShortcutInvocation()
                    }
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeUpPressed = false
                } else {
                    volumeDownPressed = false
                }
                val consume = volumeShortcutActive
                if (!volumeUpPressed && !volumeDownPressed) {
                    volumeShortcutActive = false
                }
                return consume
            }
        }

        return false
    }

    override fun onCreateInputMethod(): InputMethod {
        val method = object : InputMethod(this) {
            override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
                super.onStartInput(editorInfo, restarting)
                latestEditorInfo = editorInfo
                scheduleBubbleSync()
                scheduleShortcutActivation()
            }

            override fun onFinishInput() {
                latestEditorInfo = null
                scheduleBubbleSync()
                super.onFinishInput()
            }
        }
        inputMethod = method
        return method
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            learnFromAccessibilityEdit(event)
        }

        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && state == State.IDLE) {
                trackedAccessibilityInsertion = null
            }
            scheduleBubbleSync()
            scheduleShortcutActivation()
        }
    }

    override fun onInterrupt() {
        when (state) {
            State.RECORDING -> {
                Log.i(TAG, "Accessibility interruption received while recording; processing dictation")
                stopRecordingAndProcess()
            }
            State.PROCESSING -> toast(getString(R.string.accessibility_toast_processing))
            State.COMPLETE -> Unit
            State.ACTIVATING,
            State.IDLE -> finishShortcutSession()
        }
    }

    override fun onDestroy() {
        destroyPending = true
        recordingTimer?.cancel()
        mainHandler.removeCallbacks(syncBubbleRunnable)
        mainHandler.removeCallbacks(shortcutActivationRunnable)
        mainHandler.removeCallbacks(accessibilityAmplitudeRunnable)
        mainHandler.removeCallbacks(processingStatusRunnable)
        if (state == State.RECORDING) {
            Log.i(TAG, "Service destroyed while recording; processing dictation before cleanup")
            stopRecordingAndProcess()
        } else {
            if (state != State.PROCESSING) {
                stopCurrentRecording()
            }
            hideBubble()
        }
        runCatching {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback)
        }
        prefs.unregisterPreferenceListener(this)
        if (state != State.PROCESSING) {
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        syncRecordingButtonVisibility()
    }

    private fun scheduleBubbleSync() {
        mainHandler.removeCallbacks(syncBubbleRunnable)
        mainHandler.postDelayed(syncBubbleRunnable, VISIBILITY_SYNC_DELAY_MS)
    }

    private fun configureServiceFlags() {
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    private fun beginShortcutActivation() {
        if (pendingShortcutActivation) {
            showBubble()
            updateBubble()
            return
        }
        pendingShortcutActivation = true
        state = State.ACTIVATING
        setBubbleStatus(
            getString(R.string.accessibility_overlay_activating_title),
            getString(R.string.accessibility_overlay_activating_subtitle)
        )
        shortcutActivationDeadlineMillis = SystemClock.uptimeMillis() + SHORTCUT_START_TIMEOUT_MS
        scheduleShortcutActivation()
    }

    private fun scheduleShortcutActivation(delayMs: Long = VISIBILITY_SYNC_DELAY_MS) {
        if (!pendingShortcutActivation || (state != State.IDLE && state != State.ACTIVATING)) return
        mainHandler.removeCallbacks(shortcutActivationRunnable)
        mainHandler.postDelayed(shortcutActivationRunnable, delayMs)
    }

    private fun tryStartFromShortcut() {
        if (!pendingShortcutActivation || (state != State.IDLE && state != State.ACTIVATING)) return
        if (!prefs.accessibilityDictationEnabled) {
            toast(getString(R.string.accessibility_toast_disabled))
            finishShortcutSession()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.error_no_permission))
            finishShortcutSession()
            return
        }
        if (!prefs.hasApiKey()) {
            toast(getString(R.string.error_no_api_key))
            finishShortcutSession()
            return
        }
        if (!canRecordInCurrentField()) {
            if (SystemClock.uptimeMillis() < shortcutActivationDeadlineMillis) {
                scheduleShortcutActivation(SHORTCUT_START_RETRY_DELAY_MS)
            } else {
                toast(getString(R.string.accessibility_toast_no_field))
                finishShortcutSession()
            }
            return
        }
        if (startRecording()) {
            pendingShortcutActivation = false
        } else {
            finishShortcutSession()
        }
    }

    private fun handleShortcutInvocation() {
        when (state) {
            State.IDLE -> beginShortcutActivation()
            State.ACTIVATING -> {
                showBubble()
                updateBubble()
                scheduleShortcutActivation(SHORTCUT_START_RETRY_DELAY_MS)
            }
            State.RECORDING -> stopRecordingAndProcess()
            State.PROCESSING -> {
                showBubble()
                updateBubble()
                toast(getString(R.string.accessibility_toast_processing))
            }
            State.COMPLETE -> {
                showBubble()
                updateBubble()
            }
        }
    }

    private fun finishShortcutSession() {
        pendingShortcutActivation = false
        mainHandler.removeCallbacks(shortcutActivationRunnable)
        if (state == State.ACTIVATING) {
            state = State.IDLE
        }
        hideBubble()
    }

    private fun syncRecordingButtonVisibility() {
        when (state) {
            State.RECORDING -> {
                showBubble()
            }
            State.ACTIVATING,
            State.PROCESSING,
            State.COMPLETE -> {
                showBubble()
                updateBubble()
            }
            State.IDLE -> hideBubble()
        }
    }

    private fun canRecordInCurrentField(): Boolean {
        if (!prefs.accessibilityDictationEnabled) return false
        if (isSensitiveEditor(latestEditorInfo)) return false
        return hasEditableTarget()
    }

    private fun showBubble() {
        if (bubbleView != null) {
            updateBubblePositionFromPrefs()
            updateBubble()
            return
        }

        val availableWidth = (resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(128))
        val width = min(dp(OVERLAY_WIDTH_DP), availableWidth)
        val height = dp(OVERLAY_HEIGHT_DP)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyStoredBubblePosition(this, width, height)
        }

        val bubble = KeyVoiceAccessibilityOverlayView(this).apply {
            contentDescription = getString(R.string.accessibility_bubble_content_description)
            setOnTouchListener(BubbleTouchListener(params))
        }

        bubbleView = bubble
        bubbleOverlay = bubble
        bubbleParams = params
        runCatching {
            windowManager.addView(bubble, params)
            bubble.playEntrance()
            updateBubble()
        }.onFailure { error ->
            Log.w(TAG, "Unable to show accessibility dictation bubble", error)
            bubbleView = null
            bubbleOverlay = null
            bubbleParams = null
        }
    }

    private fun hideBubble() {
        val bubble = bubbleView ?: return
        runCatching { windowManager.removeView(bubble) }
        stopAccessibilityAudioMeter()
        bubbleView = null
        bubbleOverlay = null
        bubbleParams = null
        bubbleTitleText = null
        bubbleSubtitleText = null
        bubbleCompletionIsError = false
        clearProcessingStatus()
    }

    private fun handleTrigger() {
        when (state) {
            State.IDLE -> {
                if (!startRecording()) {
                    finishShortcutSession()
                }
            }
            State.ACTIVATING -> tryStartFromShortcut()
            State.RECORDING -> stopRecordingAndProcess()
            State.PROCESSING -> {
                showBubble()
                updateBubble()
                toast(getString(R.string.accessibility_toast_processing))
            }
            State.COMPLETE -> {
                showBubble()
                updateBubble()
            }
        }
    }

    private fun startRecording(): Boolean {
        if (!prefs.accessibilityDictationEnabled) {
            toast(getString(R.string.accessibility_toast_disabled))
            return false
        }
        if (!hasEditableTarget()) {
            toast(getString(R.string.accessibility_toast_no_field))
            return false
        }
        if (isSensitiveEditor(latestEditorInfo)) {
            toast(getString(R.string.accessibility_toast_no_field))
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.error_no_permission))
            return false
        }
        if (!prefs.hasApiKey()) {
            toast(getString(R.string.error_no_api_key))
            return false
        }

        return runCatching {
            audioRecorder.startRecording()
            state = State.RECORDING
            stopRequested = false
            bubbleTitleText = null
            bubbleSubtitleText = null
            bubbleCompletionIsError = false
            showBubble()
            updateBubble()
            toast(getString(R.string.accessibility_toast_recording))
            startAccessibilityAudioMeter()
            startDurationTimer()
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to start accessibility dictation", error)
            toast(getString(R.string.error_recording_failed))
            audioRecorder.release()
            audioRecorder = AudioRecorder(this)
            state = State.IDLE
            hideBubble()
        }.getOrDefault(false)
    }

    private fun stopRecordingAndProcess() {
        if (state != State.RECORDING || stopRequested) {
            if (state == State.PROCESSING) {
                showBubble()
                updateBubble()
                toast(getString(R.string.accessibility_toast_processing))
            }
            return
        }

        stopRequested = true
        pendingShortcutActivation = false
        mainHandler.removeCallbacks(shortcutActivationRunnable)
        recordingTimer?.cancel()
        stopAccessibilityAudioMeter()
        state = State.PROCESSING
        clearProcessingStatus()
        setBubbleStatus(
            title = getString(R.string.accessibility_overlay_preparing_title),
            subtitle = getString(R.string.accessibility_overlay_preparing_subtitle)
        )

        val result = audioRecorder.stopRecording()
        result.onSuccess { audioFile ->
            processAudio(audioFile)
        }.onFailure { error ->
            Log.w(TAG, "Unable to stop accessibility dictation", error)
            audioRecorder.release()
            audioRecorder = AudioRecorder(this)
            toast(
                if (error is AudioRecorder.RecordingTooShortException) {
                    getString(R.string.error_too_short)
                } else {
                    getString(R.string.error_recording_failed)
                }
            )
            state = State.IDLE
            stopRequested = false
            finishShortcutSession()
        }
    }

    private fun processAudio(audioFile: java.io.File) {
        state = State.PROCESSING
        startProcessingStatus(
            title = getString(R.string.accessibility_overlay_preparing_title),
            subtitle = getString(R.string.accessibility_overlay_preparing_subtitle),
            slowSubtitle = getString(R.string.accessibility_overlay_preparing_slow),
            verySlowSubtitle = getString(R.string.accessibility_overlay_preparing_slow)
        )
        toast(getString(R.string.accessibility_toast_processing))

        val request = DictationPipeline.Request(
            apiKey = prefs.apiKey,
            whisperModel = prefs.whisperModel,
            language = prefs.getWhisperLanguageParam(),
            languageFullName = prefs.getLanguageFullName(),
            llmModel = prefs.llmModel,
            phase2Enabled = prefs.isPhase2Enabled,
            systemPrompt = prefs.getResolvedSystemPrompt(),
            vocabulary = prefs.effectiveVocabulary,
            promptPreset = prefs.promptPreset.id
        )

        serviceScope.launch {
            try {
                val pipelineResult = withContext(Dispatchers.IO) {
                    dictationPipeline.process(audioFile, request) { stage ->
                        withContext(Dispatchers.Main) {
                            when (stage) {
                                DictationPipeline.Stage.TRANSCRIBING -> {
                                    startProcessingStatus(
                                        title = getString(R.string.accessibility_overlay_transcribing_title),
                                        subtitle = getString(R.string.accessibility_overlay_transcribing_subtitle),
                                        slowSubtitle = getString(R.string.accessibility_overlay_transcribing_slow),
                                        verySlowSubtitle = getString(R.string.accessibility_overlay_transcribing_very_slow)
                                    )
                                }
                                DictationPipeline.Stage.REFINING -> {
                                    startProcessingStatus(
                                        title = getString(R.string.accessibility_overlay_refining_title),
                                        subtitle = getString(R.string.accessibility_overlay_refining_subtitle),
                                        slowSubtitle = getString(R.string.accessibility_overlay_refining_slow),
                                        verySlowSubtitle = getString(R.string.accessibility_overlay_refining_very_slow)
                                    )
                                }
                            }
                        }
                    }
                }

                val output = pipelineResult.getOrElse { error ->
                    val message = error.message ?: getString(R.string.error_api_generic, 0)
                    toast(message)
                    showCompletionStatus(
                        title = getString(R.string.accessibility_overlay_error_title),
                        subtitle = message,
                        isError = true
                    )
                    return@launch
                }

                val completionNotice = when (output.fallbackReason) {
                    DictationPipeline.FallbackReason.PHASE2_UNAVAILABLE -> {
                        clearProcessingStatus()
                        setBubbleStatus(
                            getString(R.string.accessibility_overlay_phase2_fallback_title),
                            getString(R.string.accessibility_overlay_phase2_fallback_subtitle)
                        )
                        delay(900L)
                        getString(R.string.accessibility_overlay_inserted_with_fallback_subtitle)
                    }
                    DictationPipeline.FallbackReason.PHASE2_DISABLED -> {
                        getString(R.string.feedback_phase2_disabled)
                    }
                    null -> null
                }

                startProcessingStatus(
                    title = getString(R.string.accessibility_overlay_inserting_title),
                    subtitle = completionNotice ?: getString(R.string.accessibility_overlay_inserting_subtitle)
                )
                val inserted = insertIntoCurrentField(output.finalText)
                if (inserted.isNotBlank()) {
                    trackAccessibilityInsertion(inserted)
                    historyStore.add(
                        DictationHistoryItems.create(
                            rawText = output.rawText,
                            finalText = output.finalText,
                            insertedText = inserted,
                            promptPreset = output.promptPreset,
                            phase2Used = output.phase2Used
                        )
                    )
                    toast(getString(R.string.accessibility_toast_inserted))
                    showCompletionStatus(
                        title = getString(R.string.accessibility_overlay_inserted_title),
                        subtitle = completionNotice ?: inserted.compactPreview(),
                        isError = false
                    )
                } else {
                    toast(getString(R.string.accessibility_toast_no_field))
                    showCompletionStatus(
                        title = getString(R.string.accessibility_overlay_error_title),
                        subtitle = getString(R.string.accessibility_toast_no_field),
                        isError = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility dictation failed", e)
                val message = e.message ?: getString(R.string.error_api_generic, 0)
                toast(message)
                showCompletionStatus(
                    title = getString(R.string.accessibility_overlay_error_title),
                    subtitle = message,
                    isError = true
                )
            } finally {
                audioRecorder.cleanupFile()
                state = State.IDLE
                stopRequested = false
                hideBubble()
                finishShortcutSession()
                if (destroyPending) {
                    serviceScope.cancel()
                }
            }
        }
    }

    private fun insertIntoCurrentField(text: String): String {
        val connection = currentAccessibilityConnection()
        if (connection != null) {
            val insertionContext = readInsertionContext(connection)
            val insertedText = InsertionFormatter.format(text, insertionContext)
            if (insertedText.isNotBlank()) {
                val committed = commitAndVerifyAccessibilityText(connection, insertedText)
                if (committed) return insertedText
            }
        }

        return insertWithAccessibilityNode(text)
    }

    private fun commitAndVerifyAccessibilityText(
        connection: AccessibilityInputConnection,
        insertedText: String
    ): Boolean {
        val attempted = runCatching {
            connection.commitText(insertedText, 1, null)
            true
        }.onFailure { error ->
            Log.w(TAG, "Accessibility input connection commit failed; falling back to node insertion", error)
        }.getOrDefault(false)

        if (!attempted) {
            return false
        }

        if (isInsertedTextVisible(insertedText)) return true

        Log.w(TAG, "Accessibility commitText returned success but inserted text was not observed; falling back")
        return false
    }

    private fun isInsertedTextVisible(insertedText: String): Boolean {
        val probe = insertedText.trim().takeLast(INSERTION_VERIFY_CHARS).trim()
        if (probe.isBlank()) return false

        val readChars = (probe.length + CONTEXT_CHARS)
            .coerceIn(CONTEXT_CHARS, MAX_ACCESSIBILITY_TEXT_READ_CHARS)
        return readCurrentFieldText(readChars)
            ?.contains(probe)
            ?: false
    }

    private fun currentAccessibilityConnection(): AccessibilityInputConnection? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            inputMethod?.currentInputConnection ?: inputMethod?.getCurrentInputConnection()
        }.getOrNull()
    }

    private fun readInsertionContext(connection: AccessibilityInputConnection): InsertionFormatter.Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return InsertionFormatter.Context()

        return runCatching {
            val surroundingText = connection.getSurroundingText(CONTEXT_CHARS, CONTEXT_CHARS, 0)
                ?: return InsertionFormatter.Context()
            val text = surroundingText.text?.toString().orEmpty()
            val selectionStart = surroundingText.selectionStart.coerceIn(0, text.length)
            val selectionEnd = surroundingText.selectionEnd.coerceIn(0, text.length)
            val start = min(selectionStart, selectionEnd)
            val end = max(selectionStart, selectionEnd)

            InsertionFormatter.Context(
                beforeCursor = text.take(start),
                afterCursor = text.drop(end),
                selectedText = if (end > start) text.substring(start, end) else ""
            )
        }.getOrDefault(InsertionFormatter.Context())
    }

    private fun insertWithAccessibilityNode(text: String): String {
        val node = findEditableTargetNode() ?: return ""

        val currentText = node.text?.toString().orEmpty()
        val selectionStart = node.textSelectionStart.takeIf { it >= 0 } ?: currentText.length
        val selectionEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: selectionStart
        val start = min(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val end = max(selectionStart, selectionEnd).coerceIn(0, currentText.length)
        val beforeCursor = currentText.take(start)
        val afterCursor = currentText.drop(end)
        val selectedText = if (end > start) currentText.substring(start, end) else ""
        val insertedText = InsertionFormatter.format(
            text,
            InsertionFormatter.Context(beforeCursor, afterCursor, selectedText)
        )
        if (insertedText.isBlank()) return ""

        val updatedText = beforeCursor + insertedText + afterCursor
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updatedText)
        }
        val replaced = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (replaced) {
            val cursor = beforeCursor.length + insertedText.length
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }
        return if (replaced) insertedText else ""
    }

    private fun hasEditableTarget(): Boolean {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isPassword }
            ?.let { return false }
        if (currentAccessibilityConnection() != null) return true
        return findEditableTargetNode() != null
    }

    private fun findEditableTargetNode(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && !it.isPassword }
            ?.let { return it }

        val root = rootInActiveWindow ?: return null
        return findFocusedEditableNode(root)
    }

    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused && !node.isPassword) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findFocusedEditableNode(child)
            if (match != null) return match
        }
        return null
    }

    private fun trackAccessibilityInsertion(insertedText: String) {
        if (insertedText.isBlank()) {
            trackedAccessibilityInsertion = null
            return
        }

        val readChars = (insertedText.length + CONTEXT_CHARS)
            .coerceIn(CONTEXT_CHARS, MAX_ACCESSIBILITY_TEXT_READ_CHARS)
        val currentText = readCurrentFieldText(readChars).orEmpty()
        val startIndex = currentText.lastIndexOf(insertedText)

        trackedAccessibilityInsertion = if (startIndex >= 0) {
            TrackedAccessibilityInsertion(
                originalText = insertedText,
                lastObservedText = insertedText,
                beforeContext = currentText.take(startIndex).takeLast(CONTEXT_CHARS),
                afterContext = currentText.drop(startIndex + insertedText.length).take(CONTEXT_CHARS)
            )
        } else {
            TrackedAccessibilityInsertion(
                originalText = insertedText,
                lastObservedText = insertedText,
                beforeContext = "",
                afterContext = ""
            )
        }
    }

    private fun learnFromAccessibilityEdit(event: AccessibilityEvent?) {
        if (!prefs.autoLearningEnabled) {
            trackedAccessibilityInsertion = null
            return
        }

        val tracked = trackedAccessibilityInsertion ?: return
        val readChars = (max(tracked.originalText.length, tracked.lastObservedText.length) +
            CONTEXT_CHARS +
            LEARNING_MAX_GROWTH_CHARS)
            .coerceIn(CONTEXT_CHARS, MAX_ACCESSIBILITY_TEXT_READ_CHARS)
        val currentText = readCurrentFieldText(readChars)
            ?: event?.text?.joinToString(separator = " ") { it.toString() }
            ?: return

        val observedText = findTrackedAccessibilitySegment(currentText, tracked)
            ?: run {
                trackedAccessibilityInsertion = null
                return
            }
        if (observedText == tracked.lastObservedText) return

        val appendedText = observedText.removePrefix(tracked.lastObservedText)
        val looksLikeContinuation = observedText.startsWith(tracked.lastObservedText) &&
            appendedText.any { it.isWhitespace() } &&
            tracked.afterContext.isEmpty()

        if (looksLikeContinuation) {
            trackedAccessibilityInsertion = null
            return
        }

        if (observedText != tracked.originalText) {
            val learnedTerms = TextCorrectionLearner.extractNewTerms(tracked.originalText, observedText)
            val additions = prefs.addLearnedVocabularyTerms(learnedTerms)
            if (additions.isNotEmpty()) {
                lastAccessibilityLearnedTerms = additions
                Log.d(TAG, "Learned accessibility vocabulary terms: ${additions.joinToString()}")
                showAccessibilityLearningFeedback(additions)
            }
        }

        tracked.lastObservedText = observedText
    }

    private fun findTrackedAccessibilitySegment(
        currentText: String,
        tracked: TrackedAccessibilityInsertion
    ): String? {
        if (currentText.isBlank()) return null

        val start = when {
            tracked.beforeContext.isNotEmpty() -> {
                val beforeIndex = currentText.lastIndexOf(tracked.beforeContext)
                if (beforeIndex < 0) return null
                beforeIndex + tracked.beforeContext.length
            }
            else -> {
                val lastObservedIndex = currentText.indexOf(tracked.lastObservedText)
                if (lastObservedIndex >= 0) {
                    lastObservedIndex
                } else {
                    val originalIndex = currentText.indexOf(tracked.originalText)
                    if (originalIndex < 0) return null
                    originalIndex
                }
            }
        }.coerceIn(0, currentText.length)

        val end = if (tracked.afterContext.isNotEmpty()) {
            val afterIndex = currentText.indexOf(tracked.afterContext, start)
            if (afterIndex < 0) return null
            afterIndex
        } else {
            (start + max(tracked.originalText.length, tracked.lastObservedText.length) + LEARNING_MAX_GROWTH_CHARS)
                .coerceAtMost(currentText.length)
        }

        if (end < start) return null
        return currentText.substring(start, end)
    }

    private fun readCurrentFieldText(contextChars: Int = CONTEXT_CHARS): String? {
        val nodeText = findEditableTargetNode()
            ?.text
            ?.toString()
        if (!nodeText.isNullOrBlank()) return nodeText

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val connection = currentAccessibilityConnection() ?: return null
        return runCatching {
            connection.getSurroundingText(contextChars, contextChars, 0)
                ?.text
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun showAccessibilityLearningFeedback(additions: List<String>) {
        val message = if (additions.size == 1) {
            getString(R.string.feedback_learned_one, additions.first())
        } else {
            getString(R.string.feedback_learned_many, additions.size)
        }
        toast(message)
        clearProcessingStatus()
        bubbleCompletionIsError = false
        state = State.COMPLETE
        setBubbleStatus(getString(R.string.accessibility_overlay_learning_title), message)
        mainHandler.postDelayed({
            if (lastAccessibilityLearnedTerms == additions && state == State.COMPLETE) {
                state = State.IDLE
                hideBubble()
            }
        }, SUCCESS_FEEDBACK_MS)
    }

    private fun isKeyboardWindowVisible(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            bubbleView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        ) {
            return true
        }

        val minKeyboardHeight = dp(160)
        return windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            bounds.width() > minKeyboardHeight && bounds.height() > minKeyboardHeight
        }
    }

    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun stopCurrentRecording() {
        recordingTimer?.cancel()
        stopAccessibilityAudioMeter()
        if (audioRecorder.isCurrentlyRecording()) {
            audioRecorder.release()
            audioRecorder = AudioRecorder(this)
        }
        state = State.IDLE
        hideBubble()
    }

    private fun startAccessibilityAudioMeter() {
        mainHandler.removeCallbacks(accessibilityAmplitudeRunnable)
        mainHandler.post(accessibilityAmplitudeRunnable)
    }

    private fun stopAccessibilityAudioMeter() {
        mainHandler.removeCallbacks(accessibilityAmplitudeRunnable)
        bubbleOverlay?.updateAudioAmplitude(0)
    }

    private fun startDurationTimer() {
        val maxDurationMs = prefs.maxRecordingDuration * 1000L
        recordingTimer = object : CountDownTimer(maxDurationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) = Unit

            override fun onFinish() {
                if (state == State.RECORDING) {
                    stopRecordingAndProcess()
                }
            }
        }.start()
    }

    private fun updateBubble() {
        val bubble = bubbleView ?: return
        val overlay = bubbleOverlay ?: return

        val visualState = when (state) {
            State.ACTIVATING -> KeyVoiceAccessibilityOverlayView.VisualState.ACTIVATING
            State.IDLE -> KeyVoiceAccessibilityOverlayView.VisualState.IDLE
            State.RECORDING -> KeyVoiceAccessibilityOverlayView.VisualState.RECORDING
            State.PROCESSING -> KeyVoiceAccessibilityOverlayView.VisualState.PROCESSING
            State.COMPLETE -> if (bubbleCompletionIsError) {
                KeyVoiceAccessibilityOverlayView.VisualState.ERROR
            } else {
                KeyVoiceAccessibilityOverlayView.VisualState.SUCCESS
            }
        }

        val title = when (state) {
            State.ACTIVATING -> bubbleTitleText ?: getString(R.string.accessibility_overlay_activating_title)
            State.IDLE -> bubbleTitleText ?: getString(R.string.app_name)
            State.RECORDING -> bubbleTitleText ?: getString(R.string.accessibility_overlay_recording_title)
            State.PROCESSING -> bubbleTitleText ?: getString(R.string.state_transcribing)
            State.COMPLETE -> bubbleTitleText ?: getString(R.string.accessibility_overlay_inserted_title)
        }

        val subtitle = when (state) {
            State.ACTIVATING -> bubbleSubtitleText ?: getString(R.string.accessibility_overlay_activating_subtitle)
            State.IDLE -> bubbleSubtitleText ?: getString(R.string.accessibility_overlay_ready_subtitle)
            State.RECORDING -> bubbleSubtitleText ?: getString(R.string.accessibility_overlay_recording_subtitle)
            State.PROCESSING -> bubbleSubtitleText ?: getString(R.string.accessibility_overlay_processing_subtitle)
            State.COMPLETE -> bubbleSubtitleText.orEmpty()
        }

        overlay.setVisualState(visualState)
        overlay.setStatus(title, subtitle)
        bubble.alpha = 1f
    }

    private fun setBubbleStatus(title: String, subtitle: String? = null) {
        bubbleTitleText = title
        bubbleSubtitleText = subtitle
        showBubble()
        updateBubble()
    }

    private fun startProcessingStatus(
        title: String,
        subtitle: String,
        slowSubtitle: String = subtitle,
        verySlowSubtitle: String = slowSubtitle
    ) {
        processingStatusStartedAtMillis = SystemClock.uptimeMillis()
        processingStatusTitle = title
        processingStatusSubtitle = subtitle
        processingStatusSlowSubtitle = slowSubtitle
        processingStatusVerySlowSubtitle = verySlowSubtitle
        mainHandler.removeCallbacks(processingStatusRunnable)
        updateProcessingStatus()
        mainHandler.postDelayed(processingStatusRunnable, PROCESSING_STATUS_TICK_MS)
    }

    private fun updateProcessingStatus() {
        val elapsedMs = SystemClock.uptimeMillis() - processingStatusStartedAtMillis
        val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val subtitle = when {
            elapsedMs >= PROCESSING_VERY_SLOW_MS -> getString(
                R.string.accessibility_overlay_status_elapsed,
                processingStatusVerySlowSubtitle,
                elapsedSeconds
            )
            elapsedMs >= PROCESSING_SLOW_MS -> getString(
                R.string.accessibility_overlay_status_elapsed,
                processingStatusSlowSubtitle,
                elapsedSeconds
            )
            elapsedSeconds > 0L -> getString(
                R.string.accessibility_overlay_status_elapsed,
                processingStatusSubtitle,
                elapsedSeconds
            )
            else -> processingStatusSubtitle
        }
        setBubbleStatus(processingStatusTitle, subtitle)
    }

    private fun clearProcessingStatus() {
        mainHandler.removeCallbacks(processingStatusRunnable)
        processingStatusStartedAtMillis = 0L
        processingStatusTitle = ""
        processingStatusSubtitle = ""
        processingStatusSlowSubtitle = ""
        processingStatusVerySlowSubtitle = ""
    }

    private suspend fun showCompletionStatus(title: String, subtitle: String?, isError: Boolean) {
        clearProcessingStatus()
        bubbleCompletionIsError = isError
        state = State.COMPLETE
        setBubbleStatus(title, subtitle)
        delay(if (isError) ERROR_FEEDBACK_MS else SUCCESS_FEEDBACK_MS)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun updateBubblePositionFromPrefs() {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        applyStoredBubblePosition(
            params = params,
            viewWidth = bubble.width.takeIf { it > 0 } ?: params.width,
            viewHeight = bubble.height.takeIf { it > 0 } ?: params.height
        )
        runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun applyStoredBubblePosition(
        params: WindowManager.LayoutParams,
        viewWidth: Int,
        viewHeight: Int
    ) {
        val displayMetrics = resources.displayMetrics
        val maxX = max(0, displayMetrics.widthPixels - viewWidth)
        val maxY = max(0, displayMetrics.heightPixels - viewHeight)
        params.x = (maxX * prefs.accessibilityBubbleXFraction).roundToInt().coerceIn(0, maxX)
        params.y = (maxY * prefs.accessibilityBubbleYFraction).roundToInt().coerceIn(0, maxY)
    }

    private fun saveBubblePosition(params: WindowManager.LayoutParams, view: View) {
        val displayMetrics = resources.displayMetrics
        val viewWidth = view.width.takeIf { it > 0 } ?: params.width
        val viewHeight = view.height.takeIf { it > 0 } ?: params.height
        val maxX = max(0, displayMetrics.widthPixels - viewWidth)
        val maxY = max(0, displayMetrics.heightPixels - viewHeight)
        prefs.accessibilityBubbleXFraction = if (maxX == 0) 0f else params.x.toFloat() / maxX
        prefs.accessibilityBubbleYFraction = if (maxY == 0) 0f else params.y.toFloat() / maxY
    }

    private fun String.compactPreview(): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        return when {
            oneLine.isBlank() -> getString(R.string.accessibility_overlay_inserted_subtitle)
            oneLine.length <= 44 -> oneLine
            else -> oneLine.take(41) + "..."
        }
    }

    private inner class BubbleTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxX = max(0, resources.displayMetrics.widthPixels - view.width)
                        val maxY = max(0, resources.displayMetrics.heightPixels - view.height)
                        params.x = (startX + dx).coerceIn(0, maxX)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        saveBubblePosition(params, view)
                    } else {
                        handleTrigger()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private data class TrackedAccessibilityInsertion(
        val originalText: String,
        var lastObservedText: String,
        val beforeContext: String,
        val afterContext: String
    )
}
