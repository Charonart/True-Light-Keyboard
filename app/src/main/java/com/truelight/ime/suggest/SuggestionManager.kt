package com.truelight.ime.suggest

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File

/**
 * Coordinates multi-language Text Suggestions, Auto-Correct, and Undo Auto-Correct.
 *
 * Respects both toggles:
 *  - suggestionsEnabled == false -> unloads dictionaries immediately (0 dictionary RAM)
 *  - autoCorrectEnabled == true  -> highlights primary correction & auto-replaces on Space
 *                                   with instant Backspace Undo & code-switching guard
 */
class SuggestionManager(private val context: Context?) {

    data class UndoRecord(
        val originalWord: String,
        val replacedWord: String,
        val trailing: String
    )

    private val primaryDict = CompactDictionary()
    private val englishGuardDict = CompactDictionary()
    private var activeDictKey: String = "en"

    var suggestionsEnabled: Boolean = true
        private set
    var autoCorrectEnabled: Boolean = false
        private set
    var isPrivateField: Boolean = false
        private set

    // Preallocated 3-slot suggestion output for zero-allocation Canvas drawing
    val slots = arrayOf("", "", "")
    var activeSlotCount: Int = 0
        private set

    /** Index of the slot that will be auto-applied on Space (-1 if none) */
    var autoCorrectSlotIndex: Int = -1
        private set

    private var pendingUndo: UndoRecord? = null

    fun configure(
        dictKey: String,
        suggestionsOn: Boolean,
        autoCorrectOn: Boolean,
        forceReload: Boolean = false
    ) {
        this.activeDictKey = dictKey
        this.suggestionsEnabled = suggestionsOn
        this.autoCorrectEnabled = suggestionsOn && autoCorrectOn

        if (!suggestionsOn) {
            primaryDict.unload()
            englishGuardDict.unload()
            clearSuggestions()
            pendingUndo = null
            return
        }

        if (context != null) {
            primaryDict.load(context, dictKey, forceReload = forceReload)
            // Keep English code-switching guard loaded only when auto-correct is active on non-English
            if (autoCorrectEnabled && dictKey != "en") {
                englishGuardDict.load(context, "en", forceReload = forceReload)
            } else {
                englishGuardDict.unload()
            }
        }
    }

    fun forceReload() {
        configure(
            dictKey = activeDictKey,
            suggestionsOn = suggestionsEnabled,
            autoCorrectOn = autoCorrectEnabled,
            forceReload = true
        )
    }

    /** For unit tests without Android Context */
    fun loadForTest(dictKey: String, words: List<String>, enGuardWords: List<String> = emptyList()) {
        activeDictKey = dictKey
        suggestionsEnabled = true
        primaryDict.loadFromList(dictKey, words)
        if (enGuardWords.isNotEmpty()) {
            englishGuardDict.loadFromList("en", enGuardWords)
        }
    }

    fun setTestFlags(suggestionsOn: Boolean, autoCorrectOn: Boolean) {
        suggestionsEnabled = suggestionsOn
        autoCorrectEnabled = suggestionsOn && autoCorrectOn
        if (!suggestionsOn) {
            primaryDict.unload()
            englishGuardDict.unload()
            clearSuggestions()
        }
    }

    fun updateEditorPrivacy(info: EditorInfo?) {
        pendingUndo = null
        if (info == null) {
            isPrivateField = false
            return
        }
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val noSuggestions = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        isPrivateField = when (inputClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            InputType.TYPE_CLASS_TEXT -> {
                noSuggestions ||
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            }
            else -> false
        }
        if (isPrivateField) {
            clearSuggestions()
        }
    }

    fun isStripVisible(): Boolean = suggestionsEnabled && !isPrivateField

    fun clearSuggestions() {
        slots[0] = ""
        slots[1] = ""
        slots[2] = ""
        activeSlotCount = 0
        autoCorrectSlotIndex = -1
    }

    fun clearUndo() {
        pendingUndo = null
    }

    fun consumeUndo(): UndoRecord? {
        val u = pendingUndo
        pendingUndo = null
        return u
    }

    fun recordUndo(originalWord: String, replacedWord: String, trailing: String) {
        pendingUndo = UndoRecord(originalWord, replacedWord, trailing)
    }

    /**
     * Updates the 3 suggestion slots based on the current composing word.
     */
    fun updateForWord(word: String) {
        pendingUndo = null
        clearSuggestions()
        if (!isStripVisible() || word.isEmpty()) return

        val completions = primaryDict.getPrefixSuggestions(word, maxResults = 3)
        val exactKnown = primaryDict.containsExact(word) ||
            (activeDictKey != "en" && englishGuardDict.containsExact(word))

        var correction: String? = null
        if (!exactKnown && word.length >= 2 && completions.isEmpty()) {
            correction = primaryDict.findBestCorrection(word)
        }

        if (correction != null && !correction.equals(word, ignoreCase = true)) {
            // Slot 0 = typed literal (keeps user in control), Slot 1 = primary correction, Slot 2 = top completion
            slots[0] = word
            slots[1] = correction
            var count = 2
            for (c in completions) {
                if (!c.equals(correction, ignoreCase = true) && !c.equals(word, ignoreCase = true)) {
                    slots[2] = c
                    count = 3
                    break
                }
            }
            activeSlotCount = count
            autoCorrectSlotIndex = if (autoCorrectEnabled) 1 else -1
        } else {
            var idx = 0
            for (c in completions) {
                if (idx >= 3) break
                slots[idx++] = c
            }
            activeSlotCount = idx
            autoCorrectSlotIndex = -1
        }
    }

    /**
     * Returns the auto-correct replacement word if Auto-Correct should fire on Space,
     * or null if the user's exact typed word should be kept.
     */
    fun getAutoCorrectReplacement(typedWord: String): String? {
        if (!autoCorrectEnabled || !isStripVisible()) return null
        if (autoCorrectSlotIndex !in 0 until activeSlotCount) return null
        val replacement = slots[autoCorrectSlotIndex]
        if (replacement.isEmpty() || replacement == typedWord) return null
        return replacement
    }

    fun learnCommittedWord(word: String) {
        if (!isStripVisible() || word.length < 2) return
        primaryDict.learnWord(context, activeDictKey, word)
    }

    fun clearAllUserDictionaries() {
        if (context == null) return
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("user_dict_") && f.name.endsWith(".txt")) {
                f.delete()
            }
        }
        val key = activeDictKey
        if (suggestionsEnabled) {
            primaryDict.forceReload(context, key)
        }
    }

    companion object {
        fun importCustomDictionary(context: Context, dictKey: String, content: String): Int {
            val dir = File(context.filesDir, "dicts")
            dir.mkdirs()
            val outFile = File(dir, "$dictKey.txt")
            val lines = content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            outFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)
            return lines.size
        }

        fun hasCustomDictionary(context: Context, dictKey: String): Boolean {
            return File(File(context.filesDir, "dicts"), "$dictKey.txt").exists()
        }
    }
}
