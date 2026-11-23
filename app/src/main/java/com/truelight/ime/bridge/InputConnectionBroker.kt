package com.truelight.ime.bridge

import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.truelight.ime.engine.InputEngine
import com.truelight.ime.suggest.SuggestionManager
import kotlin.math.abs

/**
 * High-performance, zero-allocation broker for Android InputConnection.
 *
 * Resolves critical platform idiosyncrasies:
 * 1. Chrome / WebKit async input buffering & cursor hopping
 * 2. Flutter / React Native / Unity TYPE_NULL direct dispatch
 * 3. Chat apps (Zalo, Messenger, Telegram) Enter/Send vs NewLine resolution
 * 4. Numpad layout auto-selection
 * 5. Auto-correct on Space + instant Backspace Undo
 * 6. Selection-safe & Emoji-grapheme-safe Backspace
 * 7. Smart Punctuation, Double-Space Period, Swipe-to-Delete Word, & Cursor Glide
 */
class InputConnectionBroker {

    companion object {
        private const val DOUBLE_SPACE_WINDOW_MS = 400L
    }

    private var inputConnection: InputConnection? = null
    private var editorInfo: EditorInfo? = null

    // Direct Key Dispatch mode for games/engines (Flutter, Unity, TYPE_NULL)
    private var isDirectDispatchMode = false

    // Smart punctuation & double-space-to-period state
    private var lastCommittedFromSuggestion = false
    private var lastSpaceTimeMs = 0L

    fun updateTarget(ic: InputConnection?, info: EditorInfo?) {
        this.inputConnection = ic
        this.editorInfo = info
        isDirectDispatchMode = (info == null || info.inputType == InputType.TYPE_NULL)
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L
    }

    fun isDirectDispatch(): Boolean = isDirectDispatchMode

    private inline fun withBatchEdit(block: (InputConnection) -> Unit) {
        val ic = inputConnection ?: return
        ic.beginBatchEdit()
        try {
            block(ic)
        } finally {
            ic.endBatchEdit()
        }
    }

    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ic = inputConnection ?: return false
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L

        if (isDirectDispatchMode) {
            commitText(text, newCursorPosition)
            return false
        }

        var success = false
        withBatchEdit {
            try {
                success = ic.setComposingText(text, newCursorPosition)
                if (!success) {
                    // Transient fallback for this call only (do NOT permanently latch direct dispatch)
                    ic.commitText(text, newCursorPosition)
                }
            } catch (_: Exception) {
                ic.commitText(text, newCursorPosition)
                success = false
            }
        }
        return success
    }

    fun finishComposingText(): Boolean {
        val ic = inputConnection ?: return false
        var success = false
        withBatchEdit {
            success = ic.finishComposingText()
        }
        return success
    }

    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ic = inputConnection ?: return false
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L
        var success = false
        withBatchEdit {
            success = ic.commitText(text, newCursorPosition)
        }
        return success
    }

    /**
     * Commits a punctuation or symbol char. If it immediately follows a suggestion tap
     * that appended a trailing space ("hello " + "."), strips that trailing space first -> "hello.".
     */
    fun commitCharWithSmartPunctuation(c: Char): Boolean {
        val ic = inputConnection ?: return false
        val wasSuggestion = lastCommittedFromSuggestion
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L

        if (wasSuggestion && !isDirectDispatchMode && isSmartPunctuation(c)) {
            var success = false
            withBatchEdit {
                val before = ic.getTextBeforeCursor(1, 0)
                if (before != null && before.length == 1 && before[0] == ' ') {
                    ic.deleteSurroundingText(1, 0)
                }
                success = ic.commitText(c.toString(), 1)
            }
            return success
        }

        return commitText(c.toString(), 1)
    }

    /**
     * Replaces current composing word with user-selected suggestion + trailing space.
     */
    fun commitSuggestion(word: String, engine: InputEngine, suggestionManager: SuggestionManager?): Boolean {
        val ic = inputConnection ?: return false
        withBatchEdit {
            if (isDirectDispatchMode) {
                ic.commitText("$word ", 1)
            } else {
                ic.setComposingText(word, 1)
                ic.finishComposingText()
                ic.commitText(" ", 1)
            }
            engine.reset()
        }
        lastCommittedFromSuggestion = true
        lastSpaceTimeMs = 0L
        suggestionManager?.learnCommittedWord(word)
        suggestionManager?.clearSuggestions()
        suggestionManager?.clearUndo()
        return true
    }

    /**
     * Space Key Handling:
     * CRITICAL for Chrome Address Bar and Web Browsers:
     * MUST call finishComposingText() BEFORE committing " ".
     * Also applies Auto-Correct (if enabled), stores Undo snapshot, and supports Double-Space Period.
     */
    fun handleSpace(
        engine: InputEngine,
        suggestionManager: SuggestionManager? = null,
        doubleSpacePeriodEnabled: Boolean = false
    ): Boolean {
        val ic = inputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        lastCommittedFromSuggestion = false

        withBatchEdit {
            if (engine.length() > 0) {
                val typed = engine.getComposingText()
                val replacement = if (!isDirectDispatchMode) {
                    suggestionManager?.getAutoCorrectReplacement(typed)
                } else {
                    null
                }

                if (replacement != null && replacement != typed) {
                    ic.setComposingText(replacement, 1)
                    ic.finishComposingText()
                    ic.commitText(" ", 1)
                    engine.reset()
                    suggestionManager?.clearSuggestions()
                    suggestionManager?.recordUndo(typed, replacement, " ")
                    suggestionManager?.learnCommittedWord(replacement)
                } else {
                    ic.finishComposingText()
                    engine.reset()
                    ic.commitText(" ", 1)
                    suggestionManager?.clearSuggestions()
                    suggestionManager?.clearUndo()
                    suggestionManager?.learnCommittedWord(typed)
                }
                lastSpaceTimeMs = now
            } else {
                suggestionManager?.clearUndo()
                val elapsed = now - lastSpaceTimeMs
                if (doubleSpacePeriodEnabled &&
                    !isDirectDispatchMode &&
                    lastSpaceTimeMs > 0L &&
                    elapsed in 0 until DOUBLE_SPACE_WINDOW_MS
                ) {
                    val before = ic.getTextBeforeCursor(2, 0)
                    if (before != null && before.length == 2 &&
                        before[1] == ' ' && before[0].isLetterOrDigit()
                    ) {
                        ic.deleteSurroundingText(1, 0)
                        ic.commitText(". ", 1)
                        lastSpaceTimeMs = 0L
                        return@withBatchEdit
                    }
                }
                ic.commitText(" ", 1)
                lastSpaceTimeMs = now
            }
        }
        return true
    }

    /**
     * Backspace Handling:
     * 1. If engine is composing -> undo last step/char.
     * 2. If editor has selected text -> delete entire selection via commitText("", 1).
     * 3. If user just triggered Auto-Correct -> Undo Auto-Correct and restore original word.
     * 4. Otherwise delete exact last grapheme/emoji cluster (surrogate pairs, ZWJ, flags, skin tones)
     *    via deleteSurroundingText(deleteLen, 0), falling back to KEYCODE_DEL.
     */
    fun handleBackspace(engine: InputEngine, suggestionManager: SuggestionManager? = null): Boolean {
        val ic = inputConnection ?: return false
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L

        withBatchEdit {
            if (engine.length() > 0) {
                engine.handleBackspace()
                if (engine.isEmpty()) {
                    ic.setComposingText("", 0)
                    ic.finishComposingText()
                    suggestionManager?.clearSuggestions()
                } else {
                    val text = engine.getComposingText()
                    ic.setComposingText(text, 1)
                    suggestionManager?.updateForWord(text)
                }
            } else {
                // 1. Delete active text selection safely if present
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    suggestionManager?.clearUndo()
                    ic.commitText("", 1)
                    return@withBatchEdit
                }

                // 2. Undo Auto-Correct if available
                val undo = suggestionManager?.consumeUndo()
                if (undo != null && !isDirectDispatchMode) {
                    val deleteLen = undo.replacedWord.length + undo.trailing.length
                    ic.deleteSurroundingText(deleteLen, 0)
                    ic.commitText(undo.originalWord, 1)
                    // Learn user's preferred original word so it isn't corrected next time
                    suggestionManager.learnCommittedWord(undo.originalWord)
                    suggestionManager.clearSuggestions()
                } else if (isDirectDispatchMode) {
                    sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                } else {
                    // 3. Emoji/grapheme-safe deletion
                    val before = ic.getTextBeforeCursor(32, 0)
                    if (before.isNullOrEmpty()) {
                        sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                    } else {
                        val deleteLen = computeLastGraphemeLength(before)
                        val deleted = ic.deleteSurroundingText(deleteLen, 0)
                        if (!deleted) {
                            sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * Swipe-to-Delete Word:
     * Clears the active composing word, or deletes trailing spaces + the last word before the cursor.
     */
    fun handleDeleteWord(engine: InputEngine, suggestionManager: SuggestionManager? = null): Boolean {
        val ic = inputConnection ?: return false
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L
        suggestionManager?.clearUndo()

        withBatchEdit {
            if (engine.length() > 0) {
                ic.setComposingText("", 0)
                ic.finishComposingText()
                engine.reset()
                suggestionManager?.clearSuggestions()
                return@withBatchEdit
            }

            if (isDirectDispatchMode) {
                sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                return@withBatchEdit
            }

            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
                return@withBatchEdit
            }

            val before = ic.getTextBeforeCursor(48, 0)
            if (before.isNullOrEmpty()) {
                sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                return@withBatchEdit
            }

            var idx = before.length
            // Trim trailing horizontal whitespace
            while (idx > 0 && (before[idx - 1] == ' ' || before[idx - 1] == '\t')) {
                idx--
            }
            if (idx > 0) {
                val ch = before[idx - 1]
                if (ch == '\n' || ch == '\r') {
                    if (idx == before.length) idx--
                } else if (ch.isLetterOrDigit() || ch == '_' || ch == '\'') {
                    while (idx > 0 && (before[idx - 1].isLetterOrDigit() || before[idx - 1] == '_' || before[idx - 1] == '\'')) {
                        idx--
                    }
                } else {
                    val graphemeLen = computeLastGraphemeLength(before.subSequence(0, idx))
                    idx = (idx - graphemeLen).coerceAtLeast(0)
                }
            }

            val delCount = before.length - idx
            if (delCount > 0) {
                val deleted = ic.deleteSurroundingText(delCount, 0)
                if (!deleted) {
                    sendDirectKeyEvent(KeyEvent.KEYCODE_DEL)
                }
            }
        }
        return true
    }

    /**
     * Cursor Glide: finishes composing text if active and moves cursor left/right by delta steps.
     */
    fun moveCursor(
        delta: Int,
        engine: InputEngine? = null,
        suggestionManager: SuggestionManager? = null
    ) {
        if (delta == 0) return
        val ic = inputConnection ?: return
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L

        if (engine != null && engine.length() > 0) {
            ic.finishComposingText()
            engine.reset()
            suggestionManager?.clearSuggestions()
        }
        suggestionManager?.clearUndo()

        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val steps = abs(delta).coerceAtMost(8)
        for (i in 0 until steps) {
            sendDirectKeyEvent(keyCode)
        }
    }

    /**
     * Enter Key Handling:
     * Resolves Chat Apps (Zalo, Messenger, Telegram) vs Multiline Notes vs Search Bars.
     */
    fun handleEnter(isShiftPressed: Boolean, engine: InputEngine, suggestionManager: SuggestionManager? = null): Boolean {
        val ic = inputConnection ?: return false
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L

        withBatchEdit {
            if (engine.length() > 0) {
                val typed = engine.getComposingText()
                ic.finishComposingText()
                engine.reset()
                suggestionManager?.learnCommittedWord(typed)
                suggestionManager?.clearSuggestions()
            }
            suggestionManager?.clearUndo()

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

            if (isMultiLine && (hasNoEnterAction || actionId == EditorInfo.IME_ACTION_NONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED)) {
                ic.commitText("\n", 1)
                return@withBatchEdit
            }

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
     * Explicit Newline Insertion (e.g. Long-Press Enter = Insert Newline):
     * Commits active composing text (if any) and inserts a literal "\n".
     */
    fun handleInsertNewline(engine: InputEngine, suggestionManager: SuggestionManager? = null): Boolean {
        val ic = inputConnection ?: return false
        lastCommittedFromSuggestion = false
        lastSpaceTimeMs = 0L

        withBatchEdit {
            if (engine.length() > 0) {
                val typed = engine.getComposingText()
                ic.finishComposingText()
                engine.reset()
                suggestionManager?.learnCommittedWord(typed)
                suggestionManager?.clearSuggestions()
            }
            suggestionManager?.clearUndo()
            ic.commitText("\n", 1)
        }
        return true
    }

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

    fun shouldUseNumpad(): Boolean {
        val info = editorInfo ?: return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        return inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME
    }

    private fun isSmartPunctuation(c: Char): Boolean {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':'
    }

    /**
     * Computes exact UTF-16 char length of the final grapheme/emoji cluster in [text].
     * Handles surrogate pairs, variation selectors (\uFE0F/\uFE0E), keycaps (\u20E3),
     * Fitzpatrick skin tone modifiers (U+1F3FB..U+1F3FF), tag sequences, regional indicator
     * flag pairs (U+1F1E6..U+1F1FF), and ZWJ (\u200D) sequences with zero allocations.
     */
    internal fun computeLastGraphemeLength(text: CharSequence): Int {
        if (text.isEmpty()) return 1
        var idx = text.length

        // Regional indicator flag pairs (U+1F1E6..U+1F1FF)
        val lastCp = Character.codePointBefore(text, idx)
        if (lastCp in 0x1F1E6..0x1F1FF) {
            var riCount = 0
            var scan = idx
            while (scan > 0) {
                val cp = Character.codePointBefore(text, scan)
                if (cp in 0x1F1E6..0x1F1FF) {
                    riCount++
                    scan -= Character.charCount(cp)
                } else {
                    break
                }
            }
            return if (riCount >= 2 && riCount % 2 == 0) 4 else 2
        }

        // General emoji / ZWJ / variation selector / skin tone / keycap / tag sequence walker
        while (idx > 0) {
            while (idx > 0) {
                val cp = Character.codePointBefore(text, idx)
                if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 ||
                    cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
                ) {
                    idx -= Character.charCount(cp)
                } else {
                    break
                }
            }

            if (idx > 0) {
                val baseCp = Character.codePointBefore(text, idx)
                idx -= Character.charCount(baseCp)
            }

            if (idx > 0 && text[idx - 1] == '\u200D') {
                idx--
                continue
            }

            break
        }

        return (text.length - idx).coerceAtLeast(1)
    }
}
