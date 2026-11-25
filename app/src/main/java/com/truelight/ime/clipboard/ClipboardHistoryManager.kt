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
 * Supports pinning items to top, single-item delete, and optional time-based auto-clear for
 * unpinned clips. Never persists clips to disk, never decodes Bitmaps, and automatically skips
 * sensitive clips (API 33+ EXTRA_IS_SENSITIVE).
 */
class ClipboardHistoryManager private constructor(
    private val appContext: Context?,
    private val systemClipboard: ClipboardManager?
) {

    constructor(context: Context) : this(
        context.applicationContext,
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    )

    internal constructor() : this(null, null)

    data class ClipItem(
        val text: String? = null,
        val imageUri: String? = null,
        val mimeType: String? = null,
        val isPinned: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        val isImage: Boolean get() = imageUri != null
    }

    companion object {
        private const val MAX_CLIPS = 10
        private const val MAX_IMAGE_CLIPS = 5
        private const val MAX_CLIP_LENGTH = 4096
        private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
    }

    private val items = ArrayList<ClipItem>(MAX_CLIPS)
    private var autoClearMinutes: Int = 0
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
    fun setAutoClearMinutes(minutes: Int) {
        autoClearMinutes = minutes.coerceAtLeast(0)
        pruneExpired()
    }

    @Synchronized
    fun pruneExpired(nowMs: Long = System.currentTimeMillis()) {
        val mins = autoClearMinutes
        if (mins <= 0 || items.isEmpty()) return
        val cutoffMs = mins * 60_000L
        for (i in items.size - 1 downTo 0) {
            val item = items[i]
            if (!item.isPinned && nowMs - item.timestampMs >= cutoffMs) {
                items.removeAt(i)
            }
        }
    }

    @Synchronized
    fun togglePin(index: Int): Boolean {
        if (index !in 0 until items.size) return false
        val current = items.removeAt(index)
        val nowPinned = !current.isPinned
        val updated = current.copy(isPinned = nowPinned)
        val pinnedCount = countPinned()
        if (nowPinned) {
            items.add(pinnedCount, updated)
        } else {
            var insertIdx = pinnedCount
            while (insertIdx < items.size && items[insertIdx].timestampMs > updated.timestampMs) {
                insertIdx++
            }
            items.add(insertIdx, updated)
        }
        return nowPinned
    }

    @Synchronized
    fun removeAt(index: Int): Boolean {
        if (index !in 0 until items.size) return false
        items.removeAt(index)
        return true
    }

    @Synchronized
    fun getClips(): List<String> {
        pruneExpired()
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
        pruneExpired()
        if (items.isEmpty()) return emptyList()
        return ArrayList(items)
    }

    @Synchronized
    fun clear(includePinned: Boolean = false) {
        if (includePinned) {
            items.clear()
        } else {
            for (i in items.size - 1 downTo 0) {
                if (!items[i].isPinned) {
                    items.removeAt(i)
                }
            }
        }
    }

    @Synchronized
    internal fun addImageClip(
        uriString: String,
        mimeType: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (uriString.isBlank() || !mimeType.startsWith("image/", ignoreCase = true)) return
        pruneExpired(timestampMs)
        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        for (i in items.size - 1 downTo 0) {
            val existing = items[i]
            if (existing.imageUri == uriString) {
                if (existing.isPinned) {
                    items[i] = existing.copy(mimeType = normalizedMime, timestampMs = timestampMs)
                    return
                }
                items.removeAt(i)
            }
        }

        val insertIdx = countPinned()
        items.add(
            insertIdx,
            ClipItem(
                imageUri = uriString,
                mimeType = normalizedMime,
                isPinned = false,
                timestampMs = timestampMs
            )
        )

        var imageCount = 0
        for (i in 0 until items.size) {
            if (items[i].isImage) imageCount++
        }
        while (imageCount > MAX_IMAGE_CLIPS) {
            var evicted = false
            for (i in items.size - 1 downTo 0) {
                if (!items[i].isPinned && items[i].isImage) {
                    items.removeAt(i)
                    imageCount--
                    evicted = true
                    break
                }
            }
            if (!evicted) break
        }

        trimToMaxClips()
    }

    @Synchronized
    internal fun addTextClip(text: String, timestampMs: Long = System.currentTimeMillis()) {
        if (text.isBlank() || text.length > MAX_CLIP_LENGTH) return
        pruneExpired(timestampMs)
        for (i in items.size - 1 downTo 0) {
            val existing = items[i]
            if (!existing.isImage && existing.text == text) {
                if (existing.isPinned) {
                    items[i] = existing.copy(timestampMs = timestampMs)
                    return
                }
                items.removeAt(i)
            }
        }

        val insertIdx = countPinned()
        items.add(
            insertIdx,
            ClipItem(text = text, isPinned = false, timestampMs = timestampMs)
        )
        trimToMaxClips()
    }

    private fun countPinned(): Int {
        var count = 0
        for (i in 0 until items.size) {
            if (items[i].isPinned) {
                count++
            } else {
                break
            }
        }
        return count
    }

    private fun trimToMaxClips() {
        while (items.size > MAX_CLIPS) {
            var evicted = false
            for (i in items.size - 1 downTo 0) {
                if (!items[i].isPinned) {
                    items.removeAt(i)
                    evicted = true
                    break
                }
            }
            if (!evicted) {
                items.removeAt(items.size - 1)
            }
        }
    }

    private fun resolveImageMimeType(uri: Uri, description: ClipDescription?): String? {
        val contentMime = try {
            appContext?.contentResolver?.getType(uri)
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

            val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                clip.description?.extras
            } else {
                null
            }
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

            val ctx = appContext ?: return
            val raw = item.text?.toString() ?: item.coerceToText(ctx)?.toString() ?: return
            addTextClip(raw)
        } catch (_: Exception) {
            // Silently ignore platform clipboard access restrictions
        }
    }
}
