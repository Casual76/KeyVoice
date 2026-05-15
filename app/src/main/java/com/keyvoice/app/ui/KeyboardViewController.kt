package com.keyvoice.app.ui

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.keyvoice.app.R

/**
 * Controller class that manages the visual state of the keyboard view.
 * Handles transitions between Idle, Recording, Processing, and Error states.
 */
class KeyboardViewController(private val rootView: View) {

    // UI elements
    val btnMic: ImageButton = rootView.findViewById(R.id.btn_mic)
    val btnKeyboard: ImageButton = rootView.findViewById(R.id.btn_keyboard)
    val btnSettings: ImageButton = rootView.findViewById(R.id.btn_settings)
    val btnUndo: ImageButton = rootView.findViewById(R.id.btn_undo)

    private val tvStatus: TextView = rootView.findViewById(R.id.tv_status)
    private val tvCountdown: TextView = rootView.findViewById(R.id.tv_countdown)
    private val tvHint: TextView = rootView.findViewById(R.id.tv_hint)
    private val tvError: TextView = rootView.findViewById(R.id.tv_error)
    private val errorContainer: LinearLayout = rootView.findViewById(R.id.error_container)
    private val progressSpinner: ProgressBar = rootView.findViewById(R.id.progress_spinner)
    val audioLevelView: AudioLevelView = rootView.findViewById(R.id.audio_level_view)

    private var pulseAnimation: android.view.animation.Animation? = null

    /** Possible states for the keyboard UI */
    enum class State {
        IDLE,
        RECORDING,
        PROCESSING_PHASE1,
        PROCESSING_PHASE2,
        ERROR
    }

    /**
     * Transitions the keyboard UI to the given state.
     */
    fun setState(state: State, errorMessage: String? = null) {
        // Reset all visibility first
        tvStatus.visibility = View.GONE
        tvCountdown.visibility = View.GONE
        tvHint.visibility = View.GONE
        errorContainer.visibility = View.GONE
        progressSpinner.visibility = View.GONE
        audioLevelView.visibility = View.GONE

        // Stop pulse animation
        btnMic.clearAnimation()

        when (state) {
            State.IDLE -> {
                btnMic.isEnabled = true
                btnMic.setBackgroundResource(R.drawable.bg_mic_idle)
                btnMic.setImageResource(R.drawable.ic_mic)
                tvHint.visibility = View.VISIBLE
                audioLevelView.reset()
            }

            State.RECORDING -> {
                btnMic.isEnabled = true
                btnMic.setBackgroundResource(R.drawable.bg_mic_recording)
                btnMic.setImageResource(R.drawable.ic_mic_active)
                audioLevelView.visibility = View.VISIBLE
                tvStatus.text = rootView.context.getString(R.string.state_recording)
                tvStatus.visibility = View.VISIBLE

                // Start pulse animation
                if (pulseAnimation == null) {
                    pulseAnimation = AnimationUtils.loadAnimation(rootView.context, R.anim.pulse)
                }
                btnMic.startAnimation(pulseAnimation)
            }

            State.PROCESSING_PHASE1 -> {
                btnMic.isEnabled = false
                btnMic.clearAnimation()
                btnMic.setBackgroundResource(R.drawable.bg_mic_idle)
                btnMic.setImageResource(R.drawable.ic_mic)
                btnMic.alpha = 0.5f
                progressSpinner.visibility = View.VISIBLE
                tvStatus.text = rootView.context.getString(R.string.state_transcribing)
                tvStatus.visibility = View.VISIBLE
                audioLevelView.reset()
            }

            State.PROCESSING_PHASE2 -> {
                btnMic.isEnabled = false
                btnMic.alpha = 0.5f
                progressSpinner.visibility = View.VISIBLE
                tvStatus.text = rootView.context.getString(R.string.state_refining)
                tvStatus.visibility = View.VISIBLE
            }

            State.ERROR -> {
                btnMic.isEnabled = true
                btnMic.alpha = 1f
                btnMic.setBackgroundResource(R.drawable.bg_mic_idle)
                btnMic.setImageResource(R.drawable.ic_mic)
                errorContainer.visibility = View.VISIBLE
                tvError.text = errorMessage ?: ""
                audioLevelView.reset()
            }
        }

        // Restore alpha for non-processing states
        if (state != State.PROCESSING_PHASE1 && state != State.PROCESSING_PHASE2) {
            btnMic.alpha = 1f
        }
    }

    /**
     * Shows the countdown timer text.
     * @param secondsRemaining Number of seconds remaining
     */
    fun showCountdown(secondsRemaining: Int) {
        tvCountdown.visibility = View.VISIBLE
        tvCountdown.text = rootView.context.getString(R.string.state_countdown, secondsRemaining)
    }

    /** Hides the countdown timer */
    fun hideCountdown() {
        tvCountdown.visibility = View.GONE
    }

    /** Shows the undo button */
    fun showUndo() {
        btnUndo.visibility = View.VISIBLE
    }

    /** Hides the undo button */
    fun hideUndo() {
        btnUndo.visibility = View.GONE
    }

    /** Hides any error message and returns to idle hint */
    fun clearError() {
        errorContainer.visibility = View.GONE
    }
}
