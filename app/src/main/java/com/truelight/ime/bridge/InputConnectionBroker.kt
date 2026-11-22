package com.truelight.ime.bridge

import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.truelight.ime.engine.TelexFsmEngine

/**
 * High-performance, zero-allocation broker for Android InputConnection.
 *
 * Resolves critical platform idiosyncrasies:
 * 1. Chrome / WebKit async input buffering & cursor hopping
 * 2. Flutter / React Native / Unity TYPE_NULL direct dispatch
 * 3. Chat apps (Zalo, Messenger, Telegram) Enter/Send vs NewLine resolution
 * 4. Numpad layout auto-selection
 */
class InputConnectionBroker {

    private var inputConnection: InputConnection? = null
    private var editorInfo: EditorInfo? = null

    // Direct Key Dispatch mode for games/engines (Flutter, Unity, TYPE_NULL)
    private var isDirectDispatchMode = false

    fun updateTarget(ic: InputConnection?, info: EditorInfo?) {
        this.inputConnection = ic
        this.editorInfo = info
        isDirectDispatchMode = (info == null || info.inputType == InputType.TYPE_NULL)
    }

    fun isDirectDispatch(): Boolean = isDirectDispatchMode

    /**
     * Executes an operation inside a beginBatchEdit/endBatchEdit block.
     * Prevents UI re-layouts and race conditions in Chromium and WebViews.
     */
    private inline fun withBatchEdit(block: (InputConnection) -> Unit) {
        val ic = inputConnection ?: return
        ic.beginBatchEdit()
        try {
            block(ic)
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Sets composing text with fallback to direct dispatch when target refuses.
     * Returns true if composing text is active, false if target fell back to direct commit.
     */
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ic = inputConnection ?: return false
        if (isDirectDispatchMode) {
            commitText(text, newCursorPosition)
            return false
        }

        var success = false
        withBatchEdit {
            try {
                success = ic.setComposingText(text, newCursorPosition)
                if (!success) {
                    // Hybrid view (Flutter/Unity/WebView) rejected composing text
                    isDirectDispatchMode = true
                    ic.commitText(text, newCursorPosition)
                }
            } catch (e: Exception) {
                isDirectDispatchMode = true
                ic.commitText(text, newCursorPosition)
                success = false
            }
        }
        return success
    }

    /**
     * Completes and commits the current composing region.
     */
    fun finishComposingText(): Boolean {
        val ic = inputConnection ?: return false
        var success = false
        withBatchEdit {
            success = ic.finishComposingText()
        }
        return success
    }

    /**
     * Commits text directly to target editor.
     */
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ic = inputConnection ?: return false
        var success = false
        withBatchEdit {
            success = ic.commitText(text, newCursorPosition)
        }
        return success
    }

    /**
     * Space Key Handling:
     * CRITICAL for Chrome Address Bar and Web Browsers:
     * MUST call finishComposingText() BEFORE committing " "
     * to eliminate duplicated word endings (tim -> ttimm) or dropped letters.
     */
    fun handleSpace(fsmEngine: TelexFsmEngine): Boolean {
        val ic = inputConnection ?: return false
        withBatchEdit {
            if (fsmEngine.length() > 0) {
                // Finalize composing text buffer in engine & editor
                ic.finishComposingText()
                fsmEngine.reset()
            }
            ic.commitText(" ", 1)
        }
        return true
    }

    /**
     * Backspace Handling:
     * If Telex engine is composing, undo the last step or char.
     * Otherwise delete the preceding character via deleteSurroundingText
     * or direct KeyEvent.KEYCODE_DEL.
     */
    fun handleBackspace(fsmEngine: TelexFsmEngine): Boolean {
        val ic = inputConnection ?: return false

        withBatchEdit {
            if (fsmEngine.length() > 0) {
                fsmEngine.handleBackspace()
                if (fsmEngine.isEmpty()) {
                    ic.setComposingText("", 0)
                    ic.finishComposingText()
                } else {
                    ic.setComposingText(fsmEngine.getComposingText(), 1)
                }
            } else {
                if (isDirectDispatchMode) {
                    sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                } else {
                    // Try deleteSurroundingText(1, 0)
                    val deleted = ic.deleteSurroundingText(1, 0)
                    if (!deleted) {
                        sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                    }
                }
            }
        }
        return true
    }

    /**
     * Enter Key Handling:
     * Resolves Chat Apps (Zalo, Messenger, Telegram) vs Multiline Notes vs Search Bars.
     *
     * Rules:
     * 1. Shift + Enter is ALWAYS Newline (\n).
     * 2. Multiline editors with IME_FLAG_NO_ENTER_ACTION or no explicit action receive \n.
     * 3. Explicit IME actions (Send, Search, Go, Done, Next) trigger performEditorAction.
     */
    fun handleEnter(isShiftPressed: Boolean, fsmEngine: TelexFsmEngine): Boolean {
        val ic = inputConnection ?: return false

        withBatchEdit {
            // Always commit in-flight composing text before Enter
            if (fsmEngine.length() > 0) {
                ic.finishComposingText()
                fsmEngine.reset()
            }

            // 1. Shift + Enter -> Always Newline
            if (isShiftPressed) {
                ic.commitText("\n", 1)
                return@withBatchEdit
            }

            val info = editorInfo
            if (info == null) {
                sendDirectKeyEvent(KeyEvent.KEYCODE_ENTER)
                return@withBatchEdit
            }

            val actionId = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val isMultiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            val hasNoEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

            // 2. Multiline chat input fields configured for Newline
            if (isMultiLine && (hasNoEnterAction || actionId == EditorInfo.IME_ACTION_NONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED)) {
                ic.commitText("\n", 1)
                return@withBatchEdit
            }

            // 3. Explicit action triggered (Send, Search, Go, Done, Next)
            when (actionId) {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE -> {
                    ic.performEditorAction(actionId)
                }
                else -> {
                    if (isMultiLine) {
                        ic.commitText("\n", 1)
                    } else {
                        sendDirectKeyEvent(KeyEvent.KEYCODE_ENTER)
                    }
                }
            }
        }
        return true
    }

    /**
     * Sends down and up KeyEvent directly to the InputConnection.
     * Crucial for games, Unity, Flutter, and native terminal apps.
     */
    fun sendDirectKeyEvent(keyCode: Int) {
        val ic = inputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val eventDown = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD
        )
        val eventUp = KeyEvent(
            now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD
        )

        ic.sendKeyEvent(eventDown)
        ic.sendKeyEvent(eventUp)
    }

    /**
     * Determines whether the current input field expects a numeric keypad.
     */
    fun shouldUseNumpad(): Boolean {
        val info = editorInfo ?: return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME
    }
}
