package com.truelight.ime.emoji

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages 30 recent emojis with SharedPreferences persistence.
 * Zero-allocation in query path.
 */
class EmojiHistoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "true_light_emoji_prefs"
        private const val KEY_RECENTS = "key_recent_emojis"
        private const val MAX_RECENTS = 30
        private const val DELIMITER = "\u0001"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Preallocated fixed array for recents
    private val recents = ArrayList<String>(MAX_RECENTS)

    init {
        loadRecents()
    }

    private fun loadRecents() {
        recents.clear()
        val raw = prefs.getString(KEY_RECENTS, null) ?: return
        val tokens = raw.split(DELIMITER)
        for (token in tokens) {
            if (token.isNotEmpty() && recents.size < MAX_RECENTS) {
                recents.add(token)
            }
        }
    }

    fun getRecentList(): List<String> = recents

    fun addRecent(emoji: String) {
        if (emoji.isEmpty()) return

        // Remove if existing
        recents.remove(emoji)
        // Add to front
        recents.add(0, emoji)

        while (recents.size > MAX_RECENTS) {
            recents.removeAt(recents.size - 1)
        }

        saveRecents()
    }

    private fun saveRecents() {
        val sb = StringBuilder()
        for (i in recents.indices) {
            if (i > 0) sb.append(DELIMITER)
            sb.append(recents[i])
        }
        prefs.edit().putString(KEY_RECENTS, sb.toString()).apply()
    }
}
