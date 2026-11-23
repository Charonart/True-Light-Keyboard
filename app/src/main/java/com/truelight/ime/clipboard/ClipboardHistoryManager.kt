package com.truelight.ime.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import java.util.Locale

typealias ClipItem = ClipboardHistoryManager.ClipItem

/**
 * Privacy-first, RAM-only clipboard history manager (max 10 recent clips, up to 5 image URIs).
 * Never persists clips to disk, never decodes Bitmaps, and automatically skips sensitive clips
 * (API 33+ EXTRA_IS_SENSITIVE).
 */
class ClipboardHistoryManager(context: Context) {

    data class ClipItem(
        val text: String? = null,
        val imageUri: String? = null,
        val mimeType: String? = null
    ) {
        val isImage: Boolean get() = imageUri != null
    }

    companion object {
        private const val MAX_CLIPS = 10
        private const val MAX_IMAGE_CLIPS = 5
        private const val MAX_CLIP_LENGTH = 4096
        private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
    }

    private val appContext = context.applicationContext
    private val systemClipboard =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val items = ArrayList<ClipItem>(MAX_CLIPS)
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
            items.clear()
        }
    }

    @Synchronized
    fun getClips(): List<String> {
        if (items.isEmpty()) return emptyList()
        val result = ArrayList<String>(items.size)
        for (i in 0 until items.size) {
            val text = items[i].text
            if (text != null) {
                result.add(text)
            }
        }
        return result
    }

    @Synchronized
    fun getClipItems(): List<ClipItem> {
        if (items.isEmpty()) return emptyList()
        return ArrayList(items)
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    @Synchronized
    internal fun addImageClip(uriString: String, mimeType: String) {
        if (uriString.isBlank() || !mimeType.startsWith("image/", ignoreCase = true)) return
        for (i in items.size - 1 downTo 0) {
            if (items[i].imageUri == uriString) {
                items.removeAt(i)
            }
        }
        items.add(0, ClipItem(imageUri = uriString, mimeType = mimeType.lowercase(Locale.ROOT)))

        var imageCount = 0
        for (i in 0 until items.size) {
            if (items[i].isImage) imageCount++
        }
        var idx = items.size - 1
        while (imageCount > MAX_IMAGE_CLIPS && idx >= 0) {
            if (items[idx].isImage) {
                items.removeAt(idx)
                imageCount--
            }
            idx--
        }
        while (items.size > MAX_CLIPS) {
            items.removeAt(items.size - 1)
        }
    }

    @Synchronized
    internal fun addTextClip(text: String) {
        if (text.isBlank() || text.length > MAX_CLIP_LENGTH) return
        for (i in items.size - 1 downTo 0) {
            val existing = items[i]
            if (!existing.isImage && existing.text == text) {
                items.removeAt(i)
            }
        }
        items.add(0, ClipItem(text = text))
        while (items.size > MAX_CLIPS) {
            items.removeAt(items.size - 1)
        }
    }

    private fun resolveImageMimeType(uri: Uri, description: ClipDescription?): String? {
        val contentMime = try {
            appContext.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        if (!contentMime.isNullOrBlank() && contentMime.startsWith("image/", ignoreCase = true)) {
            return contentMime.lowercase(Locale.ROOT)
        }
        if (description != null) {
            var wildcardFallback: String? = null
            for (i in 0 until description.mimeTypeCount) {
                val mime = description.getMimeType(i) ?: continue
                if (mime.startsWith("image/", ignoreCase = true)) {
                    val lower = mime.lowercase(Locale.ROOT)
                    if (lower != "image/*") return lower
                    wildcardFallback = lower
                }
            }
            if (wildcardFallback != null) return wildcardFallback
        }
        return null
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
            val uri = item.uri
            if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
                val resolvedMime = resolveImageMimeType(uri, clip.description)
                if (resolvedMime != null) {
                    addImageClip(uri.toString(), resolvedMime)
                    return
                }
            }

            val raw = item.text?.toString() ?: item.coerceToText(appContext)?.toString() ?: return
            addTextClip(raw)
        } catch (_: Exception) {
            // Silently ignore platform clipboard access restrictions
        }
    }
}
