package com.truelight.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.truelight.ime.bridge.InputConnectionBroker
import com.truelight.ime.engine.TelexFsmEngine
import com.truelight.ime.ui.KeyboardCanvasView

/**
 * True Light Keyboard - Ultra-lightweight, zero-allocation Android IME.
 *
 * Core Objectives:
 * - APK Size < 1.5 MB
 * - Operating RAM < 20 MB
 * - Response Latency < 15ms
 * - 100% Pure Kotlin/Java stdlib, ZERO external dependencies (No AndroidX)
 * - 0 Internet Permission
 */
class TrueLightIME : InputMethodService(), KeyboardCanvasView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardCanvasView
    private val broker = InputConnectionBroker()
    private val fsmEngine = TelexFsmEngine()

    override fun onCreate() {
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardCanvasView(this)
        keyboardView.actionListener = this
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Reset composing state
        fsmEngine.reset()
        broker.updateTarget(currentInputConnection, info)

        // Auto-detect numeric fields and switch to Numpad layout
        if (broker.shouldUseNumpad()) {
            keyboardView.switchToNumpad()
        } else {
            keyboardView.switchToAlpha()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        broker.finishComposingText()
        fsmEngine.reset()
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

        // If cursor moved outside the active composing range, finish composing
        // to prevent cursor hopping bugs in WebViews & hybrid frameworks
        if (candidatesStart >= 0 && candidatesEnd >= 0) {
            if (newSelStart < candidatesStart || newSelEnd > candidatesEnd) {
                if (fsmEngine.length() > 0) {
                    broker.finishComposingText()
                    fsmEngine.reset()
                }
            }
        }
    }

    // --- OnKeyboardActionListener Implementation ---

    override fun onChar(c: Char) {
        if (keyboardView.layout.isVietnamese && c.isLetter() && !broker.isDirectDispatch()) {
            val result = fsmEngine.processChar(c)
            if (result.isWordTerminated) {
                broker.finishComposingText()
                broker.commitText(c.toString(), 1)
            } else {
                val composingActive = broker.setComposingText(result.text, 1)
                if (!composingActive && broker.isDirectDispatch()) {
                    // Target editor refused composing and fell back to direct commit
                    fsmEngine.reset()
                }
            }
        } else {
            if (fsmEngine.length() > 0) {
                broker.finishComposingText()
                fsmEngine.reset()
            }
            broker.commitText(c.toString(), 1)
        }
    }

    override fun onSpace() {
        broker.handleSpace(fsmEngine)
    }

    override fun onBackspace() {
        broker.handleBackspace(fsmEngine)
    }

    override fun onEnter() {
        val isShiftPressed = keyboardView.layout.isShifted
        broker.handleEnter(isShiftPressed, fsmEngine)
    }

    override fun onEmoji(emoji: String) {
        if (fsmEngine.length() > 0) {
            broker.finishComposingText()
            fsmEngine.reset()
        }
        broker.commitText(emoji, 1)
    }

    override fun onLanguageToggle() {
        if (fsmEngine.length() > 0) {
            broker.finishComposingText()
            fsmEngine.reset()
        }
    }
}
