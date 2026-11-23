package com.truelight.ime.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * Privacy-first, RAM-only clipboard history manager (max 10 recent clips).
 * Never persists clips to disk and automatically skips sensitive clips (API 33+ EXTRA_IS_SENSITIVE).
 */
class ClipboardHistoryManager(context: Context) {

    companion object {
        private const val MAX_CLIPS = 10
        private const val MAX_CLIP_LENGTH = 4096
        private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
    }

    private val appContext = context.applicationContext
    private val systemClipboard =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val clips = ArrayList<String>(MAX_CLIPS)
    private var isEnabled = false
    private var isRegistered = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        captureCurrentClip()
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        isEnabled = enabled
        val cm = systemClipboard ?: return

        if (enabled) {
            if (!isRegistered) {
                try {
                    cm.addPrimaryClipChangedListener(listener)
                    isRegistered = true
                } catch (_: Exception) {
                }
            }
            captureCurrentClip()
        } else {
            if (isRegistered) {
                try {
                    cm.removePrimaryClipChangedListener(listener)
                } catch (_: Exception) {
                }
                isRegistered = false
            }
            clips.clear()
        }
    }

    @Synchronized
    fun getClips(): List<String> {
        if (clips.isEmpty()) return emptyList()
        return ArrayList(clips)
    }

    @Synchronized
    fun clear() {
        clips.clear()
    }

    @Synchronized
    private fun captureCurrentClip() {
        if (!isEnabled) return
        val cm = systemClipboard ?: return
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount <= 0) return

            val extras = clip.description?.extras
            if (extras != null) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
                ) {
                    return
                }
                if (extras.getBoolean(EXTRA_IS_SENSITIVE_COMPAT, false)) {
                    return
                }
            }

            val item = clip.getItemAt(0) ?: return
            val raw = item.text?.toString() ?: item.coerceToText(appContext)?.toString() ?: return
            if (raw.isBlank() || raw.length > MAX_CLIP_LENGTH) return

            clips.remove(raw)
            clips.add(0, raw)
            while (clips.size > MAX_CLIPS) {
                clips.removeAt(clips.size - 1)
            }
        } catch (_: Exception) {
            // Silently ignore platform clipboard access restrictions
        }
    }
}
