package com.keyvoice.app.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.keyvoice.app.MainSetupActivity
import com.keyvoice.app.R
import com.keyvoice.app.api.RefinementRepository
import com.keyvoice.app.audio.AudioRecorder
import com.keyvoice.app.dictation.DictationHistoryItems
import com.keyvoice.app.dictation.DictationPipeline
import com.keyvoice.app.settings.DictationHistoryStore
import com.keyvoice.app.settings.PreferencesManager
import com.keyvoice.app.update.KeyVoiceUpdateCoordinator
import com.keyvoice.app.ui.KeyboardViewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "VoiceKeyboardService"
        private const val LEARNING_CONTEXT_CHARS = 80
        private const val LEARNING_MAX_GROWTH_CHARS = 120
        private const val PREVIEW_MIN_CHARS = 240
        private const val PREVIEW_MIN_WORDS = 35
        private const val SLOW_NETWORK_DELAY_MS = 8_000L
        private const val VERY_SLOW_NETWORK_DELAY_MS = 20_000L
        private const val FEEDBACK_DURATION_MS = 4_000L
        private const val AUTO_START_DELAY_MS = 250L
        private const val AUTO_RETURN_DELAY_MS = 900L
    }

    private lateinit var prefs: PreferencesManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var refinementRepo: RefinementRepository
    private lateinit var dictationPipeline: DictationPipeline
    private lateinit var historyStore: DictationHistoryStore

    private var viewController: KeyboardViewController? = null
    private var serviceScope: CoroutineScope? = null

    private var currentState = KeyboardViewController.State.IDLE
    private var lastInsertedTextLength = 0
    private var canUndo = false
    private var pendingInsertion: PendingInsertion? = null
    private var trackedInsertion: TrackedInsertion? = null
    private var latestSelectionStart = -1
    private var latestSelectionEnd = -1
    private var pendingPreview: PendingDictation? = null
    private var lastInsertion: LastInsertion? = null
    private var lastLearnedTerms: List<String> = emptyList()
    private var latestEditorInfo: EditorInfo? = null

    private val amplitudeHandler = Handler(Looper.getMainLooper())
    private val uiHandler = Handler(Looper.getMainLooper())

    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (audioRecorder.isCurrentlyRecording()) {
                val amplitude = audioRecorder.getMaxAmplitude()
                viewController?.audioLevelView?.updateLevel(amplitude)
                amplitudeHandler.postDelayed(this, AudioRecorder.AMPLITUDE_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private var recordingTimer: CountDownTimer? = null
    private var slowNetworkRunnable: Runnable? = null
    private var verySlowNetworkRunnable: Runnable? = null
    private var autoStartRunnable: Runnable? = null
    private var autoReturnRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager.getInstance(this)
        audioRecorder = AudioRecorder(this)
        refinementRepo = RefinementRepository()
        dictationPipeline = DictationPipeline()
        historyStore = DictationHistoryStore(this)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope?.launch {
            runCatching {
                KeyVoiceUpdateCoordinator(this@VoiceKeyboardService, prefs)
                    .checkForUpdate(manual = false, notifyIfAvailable = true)
            }.onFailure { error ->
                Log.d(TAG, "Automatic update check skipped", error)
            }
        }
    }

    override fun onCreateInputView(): View {
        return try {
            createKeyboardInputView()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate keyboard view; showing fallback input view", e)
            createFallbackInputView()
        }
    }

    private fun createKeyboardInputView(): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_KeyVoice)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)
        val controller = KeyboardViewController(view)
        viewController = controller

        controller.setState(KeyboardViewController.State.IDLE)
        controller.hideUndo()
        controller.hidePostInsertActions()
        controller.hideLearningFeedback()

        controller.btnMic.setOnClickListener {
            cancelAutoStart()
            cancelAutoReturn()
            if (prefs.hapticFeedback) {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            handleMicClick()
        }

        controller.btnSettings.setOnClickListener { openSettings() }
        controller.btnKeyboard.setOnClickListener {
            cancelAutoStart()
            cancelAutoReturn()
            switchKeyboard()
        }
        controller.btnUndo.setOnClickListener {
            cancelAutoReturn()
            undoLastInsert()
        }
        controller.btnPreviewInsert.setOnClickListener { confirmPreview() }
        controller.btnPreviewCancel.setOnClickListener { cancelPreview() }
        controller.btnRewrite.setOnClickListener {
            cancelAutoReturn()
            rewriteLastInsertion(RewriteAction.IMPROVE)
        }
        controller.btnRewriteMore.setOnClickListener {
            cancelAutoReturn()
            showRewriteMenu()
        }
        controller.btnHistory.setOnClickListener {
            cancelAutoReturn()
            showHistoryPopup()
        }
        controller.btnLearningUndo.setOnClickListener { undoLastLearning() }

        return view
    }

    private fun createFallbackInputView(): View {
        viewController = null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
            setPadding(dp(12), dp(12), dp(12), dp(8))

            addView(TextView(context).apply {
                text = getString(R.string.state_idle)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setBackgroundResource(R.drawable.bg_keyboard_action_tonal)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { handleMicClick() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            addView(TextView(context).apply {
                text = getString(R.string.cd_settings)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(dp(16), dp(10), dp(16), dp(6))
                setOnClickListener { openSettings() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        cancelAutoStart()
        cancelAutoReturn()
        clearApiStatusHints()
        recordingTimer?.cancel()
        audioRecorder.release()
        super.onDestroy()
    }

    private fun handleMicClick() {
        when (currentState) {
            KeyboardViewController.State.IDLE,
            KeyboardViewController.State.ERROR -> startRecording()
            KeyboardViewController.State.RECORDING -> stopRecordingAndProcess()
            KeyboardViewController.State.PREVIEW -> {
                // Preview owns the visible actions; ignore mic taps until insert/cancel.
            }
            else -> {
                // Ignore clicks during processing.
            }
        }
    }

    private fun startRecording() {
        cancelAutoReturn()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showError(getString(R.string.error_no_permission))
            return
        }

        if (!prefs.hasApiKey()) {
            showError(getString(R.string.error_no_api_key))
            return
        }

        try {
            audioRecorder.startRecording()
            currentState = KeyboardViewController.State.RECORDING
            viewController?.setState(KeyboardViewController.State.RECORDING)
            viewController?.hideUndo()
            viewController?.hidePostInsertActions()
            viewController?.hideLearningFeedback()
            canUndo = false

            amplitudeHandler.post(amplitudeRunnable)
            startDurationTimer()
        } catch (e: Exception) {
            showError(getString(R.string.error_recording_failed))
        }
    }

    private fun stopRecordingAndProcess() {
        recordingTimer?.cancel()
        amplitudeHandler.removeCallbacks(amplitudeRunnable)

        val result = audioRecorder.stopRecording()

        result.onSuccess { audioFile ->
            processAudio(audioFile)
        }.onFailure { error ->
            when (error) {
                is AudioRecorder.RecordingTooShortException -> showError(getString(R.string.error_too_short))
                else -> showError(getString(R.string.error_recording_failed))
            }
        }
    }

    private fun startDurationTimer() {
        val maxDurationMs = prefs.maxRecordingDuration * 1000L
        val countdownStartMs = 10_000L

        recordingTimer = object : CountDownTimer(maxDurationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (millisUntilFinished <= countdownStartMs) {
                    viewController?.showCountdown((millisUntilFinished / 1000).toInt())
                }
            }

            override fun onFinish() {
                viewController?.hideCountdown()
                if (currentState == KeyboardViewController.State.RECORDING) {
                    stopRecordingAndProcess()
                }
            }
        }.start()
    }

    private fun processAudio(audioFile: java.io.File) {
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

        serviceScope?.launch {
            try {
                val pipelineResult = withContext(Dispatchers.IO) {
                    dictationPipeline.process(audioFile, request) { stage ->
                        withContext(Dispatchers.Main) {
                            clearApiStatusHints()
                            when (stage) {
                                DictationPipeline.Stage.TRANSCRIBING -> {
                                    currentState = KeyboardViewController.State.PROCESSING_PHASE1
                                    viewController?.setState(KeyboardViewController.State.PROCESSING_PHASE1)
                                    startApiStatusHints()
                                }
                                DictationPipeline.Stage.REFINING -> {
                                    currentState = KeyboardViewController.State.PROCESSING_PHASE2
                                    viewController?.setState(KeyboardViewController.State.PROCESSING_PHASE2)
                                    startApiStatusHints()
                                }
                            }
                        }
                    }
                }

                clearApiStatusHints()
                val output = pipelineResult.getOrElse { error ->
                    showError(error.message ?: getString(R.string.error_api_generic, 0))
                    return@launch
                }

                val notice = when (output.fallbackReason) {
                    DictationPipeline.FallbackReason.PHASE2_DISABLED -> {
                        viewController?.showTransientStatus(getString(R.string.state_quick_insert))
                        getString(R.string.feedback_phase2_disabled)
                    }
                    DictationPipeline.FallbackReason.PHASE2_UNAVAILABLE -> {
                        getString(R.string.feedback_phase2_fallback)
                    }
                    null -> null
                }

                val pending = PendingDictation(
                    rawText = output.rawText,
                    finalText = output.finalText,
                    phase2Used = output.phase2Used,
                    promptPreset = output.promptPreset
                )

                if (shouldPreview(output.finalText)) {
                    pendingPreview = pending
                    currentState = KeyboardViewController.State.PREVIEW
                    viewController?.setPreviewText(output.finalText)
                    viewController?.setState(KeyboardViewController.State.PREVIEW)
                    viewController?.hidePostInsertActions()
                    notice?.let { showNotice(it) }
                } else {
                    commitDictation(pending)
                    currentState = KeyboardViewController.State.IDLE
                    viewController?.setState(KeyboardViewController.State.IDLE)
                    notice?.let { showNotice(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearApiStatusHints()
                showError(e.message ?: getString(R.string.error_api_generic, 0))
            } finally {
                audioRecorder.cleanupFile()
            }
        }
    }

    private fun confirmPreview() {
        val pending = pendingPreview ?: return
        val edited = viewController?.getPreviewText()?.trim().orEmpty()
        pendingPreview = null

        if (edited.isBlank()) {
            currentState = KeyboardViewController.State.IDLE
            viewController?.setState(KeyboardViewController.State.IDLE)
            scheduleReturnToPreviousKeyboard()
            return
        }

        commitDictation(pending.copy(finalText = edited))
        currentState = KeyboardViewController.State.IDLE
        viewController?.setState(KeyboardViewController.State.IDLE)
    }

    private fun cancelPreview() {
        pendingPreview = null
        currentState = KeyboardViewController.State.IDLE
        viewController?.setState(KeyboardViewController.State.IDLE)
        scheduleReturnToPreviousKeyboard()
    }

    private fun shouldPreview(text: String): Boolean {
        if (!prefs.previewLongTextEnabled) return false
        val wordCount = Regex("\\S+").findAll(text).count()
        return text.length >= PREVIEW_MIN_CHARS || wordCount >= PREVIEW_MIN_WORDS
    }

    private fun commitDictation(pending: PendingDictation) {
        val inserted = insertText(
            text = pending.finalText,
            rawText = pending.rawText,
            finalText = pending.finalText,
            phase2Used = pending.phase2Used,
            promptPreset = pending.promptPreset,
            saveHistory = true
        )

        if (inserted.isNotBlank()) {
            viewController?.showPostInsertActions()
        }
        scheduleReturnToPreviousKeyboard()
    }

    private fun insertText(
        text: String,
        rawText: String = text,
        finalText: String = text,
        phase2Used: Boolean = false,
        promptPreset: String = prefs.promptPreset.id,
        saveHistory: Boolean = true
    ): String {
        val ic = currentInputConnection ?: return ""
        val selectedText = ic.getSelectedText(0)?.toString().orEmpty()
        val beforeContext = ic.getTextBeforeCursor(LEARNING_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val afterContext = ic.getTextAfterCursor(LEARNING_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val insertedText = InsertionFormatter.format(
            rawText = text,
            context = InsertionFormatter.Context(
                beforeCursor = beforeContext,
                afterCursor = afterContext,
                selectedText = selectedText
            )
        )

        if (insertedText.isBlank()) return ""

        pendingInsertion = PendingInsertion(
            text = insertedText,
            beforeContext = beforeContext.takeLast(LEARNING_CONTEXT_CHARS),
            afterContext = afterContext.take(LEARNING_CONTEXT_CHARS)
        )

        ic.commitText(insertedText, 1)
        capturePendingInsertionFromExtractedText()

        lastInsertedTextLength = insertedText.length
        canUndo = true
        lastInsertion = LastInsertion(
            insertedText = insertedText,
            rawText = rawText,
            finalText = finalText,
            promptPreset = promptPreset,
            phase2Used = phase2Used
        )

        viewController?.showUndo()
        viewController?.showPostInsertActions()

        if (saveHistory) {
            historyStore.add(
                DictationHistoryItems.create(
                    rawText = rawText,
                    finalText = finalText,
                    insertedText = insertedText,
                    promptPreset = promptPreset,
                    phase2Used = phase2Used
                )
            )
        }

        return insertedText
    }

    private fun undoLastInsert() {
        val insertion = lastInsertion
        if (!canUndo || insertion == null || lastInsertedTextLength <= 0) return

        if (!isInsertionBeforeCursor(insertion.insertedText)) {
            showNotice(getString(R.string.feedback_undo_unavailable))
            disableUndoForModifiedInsertion()
            return
        }

        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(lastInsertedTextLength, 0)
        lastInsertedTextLength = 0
        canUndo = false
        lastInsertion = null
        pendingInsertion = null
        trackedInsertion = null
        viewController?.hideUndo()
        viewController?.hidePostInsertActions()
    }

    private fun rewriteLastInsertion(action: RewriteAction) {
        val insertion = lastInsertion
        if (insertion == null || !canUndo) {
            showNotice(getString(R.string.feedback_rewrite_unavailable))
            return
        }

        if (!prefs.hasApiKey()) {
            showError(getString(R.string.error_no_api_key))
            return
        }

        if (!isInsertionBeforeCursor(insertion.insertedText)) {
            showNotice(getString(R.string.feedback_rewrite_unavailable))
            disableUndoForModifiedInsertion()
            return
        }

        serviceScope?.launch {
            currentState = KeyboardViewController.State.REWRITING
            viewController?.setState(KeyboardViewController.State.REWRITING)
            startApiStatusHints()

            val result = withContext(Dispatchers.IO) {
                refinementRepo.rewrite(
                    text = insertion.insertedText.trim(),
                    apiKey = prefs.apiKey,
                    model = prefs.llmModel,
                    language = prefs.getLanguageFullName(),
                    instruction = getRewriteInstruction(action)
                )
            }

            clearApiStatusHints()

            result.fold(
                onSuccess = { rewritten ->
                    replaceLastInsertion(rewritten, insertion, action)
                    currentState = KeyboardViewController.State.IDLE
                    viewController?.setState(KeyboardViewController.State.IDLE)
                    viewController?.showPostInsertActions()
                },
                onFailure = { error ->
                    currentState = KeyboardViewController.State.IDLE
                    viewController?.setState(KeyboardViewController.State.IDLE)
                    viewController?.showPostInsertActions()
                    showNotice(error.message ?: getString(R.string.error_api_generic, 0))
                }
            )
        }
    }

    private fun replaceLastInsertion(
        rewrittenText: String,
        previous: LastInsertion,
        action: RewriteAction
    ) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(previous.insertedText.length, 0)
        lastInsertedTextLength = 0
        canUndo = false
        pendingInsertion = null
        trackedInsertion = null

        insertText(
            text = rewrittenText,
            rawText = previous.rawText,
            finalText = rewrittenText,
            phase2Used = true,
            promptPreset = "${previous.promptPreset}:${action.id}",
            saveHistory = true
        )
    }

    private fun showRewriteMenu() {
        val controller = viewController ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_keyboard_panel)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        var popup: PopupWindow? = null

        listOf(RewriteAction.SHORTER, RewriteAction.FORMAL, RewriteAction.CASUAL).forEach { action ->
            container.addView(menuText(action.label) {
                popup?.dismiss()
                rewriteLastInsertion(action)
            })
        }

        popup = PopupWindow(container, dp(220), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.showAsDropDown(controller.btnRewriteMore, -dp(150), -dp(170))
    }

    private fun showHistoryPopup() {
        val controller = viewController ?: return
        val items = historyStore.getItems()

        if (items.isEmpty()) {
            showNotice(getString(R.string.feedback_history_empty))
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_keyboard_panel)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        var popup: PopupWindow? = null

        items.forEach { item ->
            container.addView(menuText(item.insertedText.previewLine()) {
                popup?.dismiss()
                insertText(item.insertedText, saveHistory = false)
            })
        }

        container.addView(menuText(getString(R.string.action_history_clear)) {
            popup?.dismiss()
            historyStore.clear()
            showNotice(getString(R.string.feedback_history_cleared))
        })

        popup = PopupWindow(container, controller.rootView.width.coerceAtLeast(dp(280)), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.showAtLocation(controller.rootView, Gravity.BOTTOM, 0, dp(48))
    }

    private fun menuText(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                onClick()
            }
        }
    }

    private fun undoLastLearning() {
        if (lastLearnedTerms.isEmpty()) return
        prefs.removeLearnedVocabularyTerms(lastLearnedTerms)
        lastLearnedTerms = emptyList()
        viewController?.hideLearningFeedback()
    }

    private fun isInsertionBeforeCursor(insertedText: String): Boolean {
        val beforeCursor = currentInputConnection
            ?.getTextBeforeCursor(insertedText.length, 0)
            ?.toString()
            .orEmpty()
        return beforeCursor == insertedText
    }

    private fun capturePendingInsertionAtCursor(cursorPosition: Int) {
        val pending = pendingInsertion ?: return
        if (cursorPosition < pending.text.length) return

        trackedInsertion = TrackedInsertion(
            originalText = pending.text,
            lastObservedText = pending.text,
            startOffset = cursorPosition - pending.text.length,
            endOffset = cursorPosition,
            beforeContext = pending.beforeContext,
            afterContext = pending.afterContext
        )
        pendingInsertion = null
    }

    private fun capturePendingInsertionFromExtractedText() {
        val pending = pendingInsertion ?: return
        val extractedText = currentInputConnection
            ?.getExtractedText(ExtractedTextRequest(), 0)
            ?: return

        val cursorPosition = resolveExtractedOffset(
            startOffset = extractedText.startOffset,
            textLength = extractedText.text?.length ?: 0,
            offset = extractedText.selectionEnd
        )
        if (cursorPosition >= pending.text.length) {
            capturePendingInsertionAtCursor(cursorPosition)
        }
    }

    private fun learnFromTrackedInsertion() {
        if (!prefs.autoLearningEnabled) {
            trackedInsertion = null
            return
        }

        val tracked = trackedInsertion ?: return
        val extractedText = currentInputConnection
            ?.getExtractedText(ExtractedTextRequest(), 0)
            ?: return

        val text = extractedText.text?.toString() ?: return
        val segment = findTrackedSegment(text, extractedText.startOffset, tracked) ?: return
        val observedText = segment.text

        if (observedText == tracked.lastObservedText) return

        val appendedText = observedText.removePrefix(tracked.lastObservedText)
        val looksLikeContinuation = observedText.startsWith(tracked.lastObservedText) &&
            appendedText.any { it.isWhitespace() } &&
            tracked.afterContext.isEmpty()

        if (looksLikeContinuation) {
            trackedInsertion = null
            return
        }

        if (observedText != tracked.originalText) {
            val learnedTerms = TextCorrectionLearner.extractNewTerms(tracked.originalText, observedText)
            val additions = prefs.addLearnedVocabularyTerms(learnedTerms)
            if (additions.isNotEmpty()) {
                lastLearnedTerms = additions
                Log.d(TAG, "Learned vocabulary terms: ${additions.joinToString()}")
                showLearningFeedback(additions)
            }
            disableUndoForModifiedInsertion()
        }

        tracked.lastObservedText = observedText
        tracked.endOffset = segment.absoluteEnd
    }

    private fun findTrackedSegment(
        extractedText: String,
        extractedStartOffset: Int,
        tracked: TrackedInsertion
    ): TrackedSegment? {
        if (extractedText.isEmpty()) return null

        val expectedStart = (tracked.startOffset - extractedStartOffset)
            .coerceIn(0, extractedText.length)
        var start = expectedStart

        if (tracked.beforeContext.isNotEmpty()) {
            val beforeIndex = extractedText.lastIndexOf(tracked.beforeContext, expectedStart)
            if (beforeIndex >= 0) {
                start = beforeIndex + tracked.beforeContext.length
            }
        }

        val end = if (tracked.afterContext.isNotEmpty()) {
            val afterIndex = extractedText.indexOf(tracked.afterContext, start)
            if (afterIndex < 0) return null
            afterIndex
        } else {
            val expectedEnd = (tracked.endOffset - extractedStartOffset)
                .coerceIn(start, extractedText.length)
            val selectionEnd = if (latestSelectionEnd >= tracked.startOffset) {
                (latestSelectionEnd - extractedStartOffset)
                    .coerceIn(start, extractedText.length)
            } else {
                expectedEnd
            }
            maxOf(expectedEnd, selectionEnd)
                .coerceAtMost(start + tracked.originalText.length + LEARNING_MAX_GROWTH_CHARS)
                .coerceAtMost(extractedText.length)
        }

        if (end < start) return null

        return TrackedSegment(
            text = extractedText.substring(start, end),
            absoluteEnd = extractedStartOffset + end
        )
    }

    private fun resolveExtractedOffset(startOffset: Int, textLength: Int, offset: Int): Int {
        return when {
            offset < 0 -> offset
            startOffset > 0 && offset <= textLength -> startOffset + offset
            else -> offset
        }
    }

    private fun disableUndoForModifiedInsertion() {
        if (!canUndo) return
        lastInsertedTextLength = 0
        canUndo = false
        viewController?.hideUndo()
        viewController?.hidePostInsertActions()
    }

    private fun showLearningFeedback(additions: List<String>) {
        val message = if (additions.size == 1) {
            getString(R.string.feedback_learned_one, additions.first())
        } else {
            getString(R.string.feedback_learned_many, additions.size)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        viewController?.showLearningFeedback(message)
        uiHandler.postDelayed({
            if (lastLearnedTerms == additions) {
                viewController?.hideLearningFeedback()
            }
        }, FEEDBACK_DURATION_MS)
    }

    private fun showNotice(message: String) {
        viewController?.showFeedback(message, showUndoAction = false)
        uiHandler.postDelayed({
            viewController?.hideLearningFeedback()
        }, FEEDBACK_DURATION_MS)
    }

    private fun startApiStatusHints() {
        clearApiStatusHints()
        slowNetworkRunnable = Runnable {
            viewController?.showSecondaryStatus(getString(R.string.state_network_slow))
        }
        verySlowNetworkRunnable = Runnable {
            viewController?.showSecondaryStatus(getString(R.string.state_network_waiting))
        }
        uiHandler.postDelayed(slowNetworkRunnable!!, SLOW_NETWORK_DELAY_MS)
        uiHandler.postDelayed(verySlowNetworkRunnable!!, VERY_SLOW_NETWORK_DELAY_MS)
    }

    private fun clearApiStatusHints() {
        slowNetworkRunnable?.let { uiHandler.removeCallbacks(it) }
        verySlowNetworkRunnable?.let { uiHandler.removeCallbacks(it) }
        slowNetworkRunnable = null
        verySlowNetworkRunnable = null
        viewController?.showSecondaryStatus(null)
    }

    private fun getRewriteInstruction(action: RewriteAction): String {
        return when (action) {
            RewriteAction.IMPROVE -> "Improve clarity and flow while keeping the same tone."
            RewriteAction.SHORTER -> "Make the text shorter and more concise."
            RewriteAction.FORMAL -> "Rewrite the text in a more formal and professional tone."
            RewriteAction.CASUAL -> "Rewrite the text in a natural, conversational tone."
        }
    }

    private fun openSettings() {
        cancelAutoStart()
        cancelAutoReturn()
        val intent = Intent(this, MainSetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        requestHideSelf(0)
    }

    private fun scheduleAutoStartRecording(info: EditorInfo?, restarting: Boolean) {
        cancelAutoStart()
        if (restarting || !prefs.autoStartRecording || !canAutoStartRecording(info)) return

        autoStartRunnable = Runnable {
            autoStartRunnable = null
            if (currentState == KeyboardViewController.State.IDLE && canAutoStartRecording(latestEditorInfo)) {
                startRecording()
            }
        }
        uiHandler.postDelayed(autoStartRunnable!!, AUTO_START_DELAY_MS)
    }

    private fun canAutoStartRecording(info: EditorInfo?): Boolean {
        if (!prefs.hasApiKey()) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (info?.packageName == packageName) return false
        if (isSensitiveEditor(info)) return false
        return currentInputConnection != null
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

    private fun scheduleReturnToPreviousKeyboard() {
        cancelAutoReturn()
        if (!prefs.returnToPreviousKeyboard) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        autoReturnRunnable = Runnable {
            autoReturnRunnable = null
            returnToPreviousKeyboard()
        }
        uiHandler.postDelayed(autoReturnRunnable!!, AUTO_RETURN_DELAY_MS)
    }

    private fun returnToPreviousKeyboard() {
        if (currentState == KeyboardViewController.State.RECORDING ||
            currentState == KeyboardViewController.State.PROCESSING_PHASE1 ||
            currentState == KeyboardViewController.State.PROCESSING_PHASE2 ||
            currentState == KeyboardViewController.State.PREVIEW ||
            currentState == KeyboardViewController.State.REWRITING
        ) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            viewController?.showTransientStatus(getString(R.string.state_returning_keyboard))
            val switched = runCatching {
                switchToPreviousInputMethod()
            }.getOrElse { error ->
                Log.w(TAG, "Unable to return to previous input method", error)
                false
            }
            if (!switched) {
                viewController?.setState(KeyboardViewController.State.IDLE)
            }
        }
    }

    private fun cancelAutoStart() {
        autoStartRunnable?.let { uiHandler.removeCallbacks(it) }
        autoStartRunnable = null
    }

    private fun cancelAutoReturn() {
        autoReturnRunnable?.let { uiHandler.removeCallbacks(it) }
        autoReturnRunnable = null
    }

    private fun switchKeyboard() {
        val switched = switchToNextInputMethod(false)
        if (!switched) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun showError(message: String) {
        currentState = KeyboardViewController.State.ERROR
        viewController?.setState(KeyboardViewController.State.ERROR, message)

        Handler(Looper.getMainLooper()).postDelayed({
            if (currentState == KeyboardViewController.State.ERROR) {
                currentState = KeyboardViewController.State.IDLE
                viewController?.setState(KeyboardViewController.State.IDLE)
            }
        }, FEEDBACK_DURATION_MS)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        latestEditorInfo = info
        if (!restarting) {
            canUndo = false
            pendingPreview = null
            pendingInsertion = null
            trackedInsertion = null
            lastInsertion = null
            viewController?.hideUndo()
            viewController?.hidePostInsertActions()
        }
        scheduleAutoStartRecording(info, restarting)
    }

    override fun onFinishInput() {
        cancelAutoStart()
        cancelAutoReturn()
        latestEditorInfo = null
        learnFromTrackedInsertion()
        super.onFinishInput()
        if (currentState == KeyboardViewController.State.RECORDING) {
            recordingTimer?.cancel()
            amplitudeHandler.removeCallbacks(amplitudeRunnable)
            audioRecorder.release()
            audioRecorder = AudioRecorder(this)
            currentState = KeyboardViewController.State.IDLE
            viewController?.setState(KeyboardViewController.State.IDLE)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        latestSelectionStart = newSelStart
        latestSelectionEnd = newSelEnd
        if (newSelStart == newSelEnd) {
            capturePendingInsertionAtCursor(newSelEnd)
        }
        learnFromTrackedInsertion()
    }

    private fun String.previewLine(): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 72) oneLine else oneLine.take(69) + "..."
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private enum class RewriteAction(
        val id: String,
        val label: String
    ) {
        IMPROVE("improve", "Migliora"),
        SHORTER("shorter", "Breve"),
        FORMAL("formal", "Formale"),
        CASUAL("casual", "Colloquiale")
    }

    private data class PendingDictation(
        val rawText: String,
        val finalText: String,
        val phase2Used: Boolean,
        val promptPreset: String
    )

    private data class LastInsertion(
        val insertedText: String,
        val rawText: String,
        val finalText: String,
        val promptPreset: String,
        val phase2Used: Boolean
    )

    private data class PendingInsertion(
        val text: String,
        val beforeContext: String,
        val afterContext: String
    )

    private data class TrackedInsertion(
        val originalText: String,
        var lastObservedText: String,
        val startOffset: Int,
        var endOffset: Int,
        val beforeContext: String,
        val afterContext: String
    )

    private data class TrackedSegment(
        val text: String,
        val absoluteEnd: Int
    )
}
