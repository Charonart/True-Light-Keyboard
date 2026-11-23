package com.truelight.ime.suggest

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-lightweight, lazy-loaded dictionary for prefix completions & 1-edit-distance typo correction.
 *
 * Loading priority for a language dictKey (e.g. "en", "vi", "fr"):
 *  1. Offline imported dictionary pack in filesDir/dicts/<dictKey>.txt (user-imported via SAF)
 *  2. Bundled starter asset in assets/dicts/<dictKey>.txt (if present)
 *  3. User-learned words in filesDir/user_dict_<dictKey>.txt (always merged)
 *
 * All user dictionary persistence runs asynchronously on a single daemon background thread
 * with write coalescing so Spacebar never blocks on disk I/O.
 * When unloaded, all arrays are cleared so RAM footprint drops to 0.
 */
class CompactDictionary {

    private data class Entry(
        val word: String,
        val lower: String,
        val folded: String,
        var freq: Int
    )

    private var entriesByFolded: ArrayList<Entry> = ArrayList()
    private val byLength: Array<ArrayList<Entry>> = Array(MAX_BUCKET_LEN + 1) { ArrayList() }
    private var exactWordSet: HashSet<String> = HashSet()
    private val userWords = HashMap<String, Int>()
    private val userWordsLock = Any()

    @Volatile
    private var loadedDictKey: String? = null

    @Volatile
    private var pendingPersistContext: Context? = null

    @Volatile
    private var pendingPersistDictKey: String? = null

    private val persistQueued = AtomicBoolean(false)

    fun isLoadedFor(dictKey: String): Boolean = loadedDictKey == dictKey

    fun unload() {
        entriesByFolded = ArrayList()
        for (bucket in byLength) {
            bucket.clear()
        }
        exactWordSet = HashSet()
        synchronized(userWordsLock) {
            userWords.clear()
            loadedDictKey = null
        }
    }

    fun forceReload(context: Context, dictKey: String) {
        load(context, dictKey, forceReload = true)
    }

    fun load(context: Context, dictKey: String, forceReload: Boolean = false) {
        if (!forceReload && loadedDictKey == dictKey) return
        unload()

        val merged = HashMap<String, Int>(2048)

        // 1. Check user-imported offline pack first
        val customFile = File(File(context.filesDir, "dicts"), "$dictKey.txt")
        if (customFile.exists() && customFile.canRead()) {
            try {
                customFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    parseWordList(reader, merged, baseFreq = 10000)
                }
            } catch (_: Exception) {
            }
        } else {
            // 2. Fallback to bundled starter asset if available
            try {
                context.assets.open("dicts/$dictKey.txt").use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        parseWordList(reader, merged, baseFreq = 10000)
                    }
                }
            } catch (_: Exception) {
                // Language has no bundled asset; will still work with user-learned words
            }
        }

        // 3. Merge offline user-learned words
        val userFile = File(context.filesDir, "user_dict_$dictKey.txt")
        if (userFile.exists() && userFile.canRead()) {
            try {
                userFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        val parts = line.trim().split('\t')
                        if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                            val w = parts[0]
                            val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
                            synchronized(userWordsLock) {
                                userWords[w] = count
                            }
                            val boost = 12000 + count * 200
                            val existing = merged[w] ?: 0
                            merged[w] = existing + boost
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        rebuildIndex(merged)
        synchronized(userWordsLock) {
            loadedDictKey = dictKey
        }
    }

    /**
     * Load directly from an in-memory list (useful for JVM unit tests without Android Context).
     */
    fun loadFromList(dictKey: String, wordList: List<String>) {
        unload()
        val merged = HashMap<String, Int>(wordList.size)
        var rank = 10000
        for (raw in wordList) {
            val w = raw.trim()
            if (w.isNotEmpty()) {
                merged[w] = rank.coerceAtLeast(1)
                rank -= 25
            }
        }
        rebuildIndex(merged)
        synchronized(userWordsLock) {
            loadedDictKey = dictKey
        }
    }

    fun learnWord(context: Context?, dictKey: String, rawWord: String) {
        val word = rawWord.trim()
        if (word.length < 2 || word.length > MAX_BUCKET_LEN) return
        if (!word.any { it.isLetter() }) return

        val lower = word.lowercase(Locale.ROOT)
        val newCount: Int
        synchronized(userWordsLock) {
            newCount = (userWords[lower] ?: 0) + 1
            userWords[lower] = newCount
        }

        // Update exact set immediately so auto-correct respects newly typed words right away
        exactWordSet.add(lower)

        // Binary-search insert into sorted ArrayList & length bucket (no full-array copy/sort)
        if (loadedDictKey == dictKey) {
            val folded = foldAccents(lower)
            val existingIdx = findExactIndex(lower, folded)
            if (existingIdx >= 0) {
                entriesByFolded[existingIdx].freq += 250
            } else {
                val entry = Entry(lower, lower, folded, 12000 + newCount * 200)
                val insertPos = lowerBound(folded)
                entriesByFolded.add(insertPos, entry)
                if (folded.length in 1..MAX_BUCKET_LEN) {
                    byLength[folded.length].add(entry)
                }
            }
        }

        // Persist asynchronously on background daemon thread with coalescing
        if (context != null) {
            pendingPersistContext = context.applicationContext
            pendingPersistDictKey = dictKey
            if (persistQueued.compareAndSet(false, true)) {
                ioExecutor.execute { flushUserDictAsync() }
            }
        }
    }

    private fun flushUserDictAsync() {
        while (true) {
            persistQueued.set(false)
            val ctx = pendingPersistContext ?: return
            val key = pendingPersistDictKey ?: return

            val snapshot: List<Pair<String, Int>>
            synchronized(userWordsLock) {
                if (loadedDictKey != key) return
                snapshot = userWords.entries
                    .sortedByDescending { it.value }
                    .take(500)
                    .map { it.key to it.value }
            }

            try {
                val userFile = File(ctx.filesDir, "user_dict_$key.txt")
                val sb = StringBuilder(snapshot.size * 12)
                for ((k, v) in snapshot) {
                    sb.append(k).append('\t').append(v).append('\n')
                }
                userFile.writeText(sb.toString(), Charsets.UTF_8)
            } catch (_: Exception) {
            }

            if (!persistQueued.get()) return
        }
    }

    fun containsExact(word: String): Boolean {
        return exactWordSet.contains(word.lowercase(Locale.ROOT))
    }

    /**
     * Find top prefix completions (accent-aware for Vietnamese, exact-prefix preferred).
     */
    fun getPrefixSuggestions(prefix: String, maxResults: Int = 3): List<String> {
        if (prefix.isEmpty() || entriesByFolded.isEmpty()) return emptyList()

        val lowerPrefix = prefix.lowercase(Locale.ROOT)
        val foldedPrefix = foldAccents(lowerPrefix)
        val startIdx = lowerBound(foldedPrefix)
        if (startIdx >= entriesByFolded.size) return emptyList()

        val candidates = ArrayList<Pair<Entry, Int>>(16)
        var i = startIdx
        var scanned = 0
        while (i < entriesByFolded.size && scanned < 250) {
            val e = entriesByFolded[i]
            if (!e.folded.startsWith(foldedPrefix)) break
            if (e.lower != lowerPrefix) {
                var score = e.freq
                // Strongly prefer entries that match already-typed diacritics/accents
                if (e.lower.startsWith(lowerPrefix)) {
                    score += 50000
                }
                // Slightly prefer shorter completions without overpowering frequency rank
                score -= (e.lower.length - lowerPrefix.length) * 15
                candidates.add(e to score)
            }
            i++
            scanned++
        }

        if (candidates.isEmpty()) return emptyList()
        candidates.sortByDescending { it.second }
        val result = ArrayList<String>(maxResults)
        for (c in candidates) {
            if (result.size >= maxResults) break
            val w = applyCasing(prefix, c.first.word)
            if (w !in result) result.add(w)
        }
        return result
    }

    /**
     * Find best 1-edit-distance (Damerau-Levenshtein <= 1) correction for a misspelled word.
     * Uses length-bucketed lookup (only scans lengths [len-1..len+1]) for 3-5x speedup.
     */
    fun findBestCorrection(word: String): String? {
        if (word.length < 2 || entriesByFolded.isEmpty()) return null
        val lower = word.lowercase(Locale.ROOT)
        if (exactWordSet.contains(lower)) return null

        val folded = foldAccents(lower)
        val targetLen = folded.length
        if (targetLen > MAX_BUCKET_LEN) return null

        val minLen = (targetLen - 1).coerceAtLeast(1)
        val maxLen = (targetLen + 1).coerceAtMost(MAX_BUCKET_LEN)

        var bestEntry: Entry? = null
        var bestScore = Int.MIN_VALUE

        for (len in minLen..maxLen) {
            val bucket = byLength[len]
            for (i in 0 until bucket.size) {
                val e = bucket[i]
                val dist = damerauLevenshtein1(folded, e.folded)
                if (dist == 0) {
                    // Exact accent-folded match (e.g. missing Vietnamese diacritic) - high score
                    val score = e.freq + 40000
                    if (score > bestScore) {
                        bestScore = score
                        bestEntry = e
                    }
                } else if (dist == 1) {
                    val score = e.freq
                    if (score > bestScore) {
                        bestScore = score
                        bestEntry = e
                    }
                }
            }
        }

        return bestEntry?.let { applyCasing(word, it.word) }
    }

    private fun parseWordList(reader: BufferedReader, dest: HashMap<String, Int>, baseFreq: Int) {
        var rank = baseFreq
        reader.forEachLine { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
            val tabIdx = trimmed.indexOf('\t')
            val word: String
            val freq: Int
            if (tabIdx > 0) {
                word = trimmed.substring(0, tabIdx).trim().lowercase(Locale.ROOT)
                freq = trimmed.substring(tabIdx + 1).trim().toIntOrNull() ?: rank
            } else {
                word = trimmed.lowercase(Locale.ROOT)
                freq = rank
            }
            if (word.isNotEmpty()) {
                val prev = dest[word] ?: 0
                if (freq > prev) dest[word] = freq
                if (rank > 50) rank -= 10
            }
        }
    }

    private fun rebuildIndex(merged: HashMap<String, Int>) {
        val list = ArrayList<Entry>(merged.size)
        val exact = HashSet<String>(merged.size * 2)
        for (bucket in byLength) {
            bucket.clear()
        }
        for ((w, f) in merged) {
            val lower = w.lowercase(Locale.ROOT)
            val folded = foldAccents(lower)
            val entry = Entry(lower, lower, folded, f)
            exact.add(lower)
            list.add(entry)
            if (folded.length in 1..MAX_BUCKET_LEN) {
                byLength[folded.length].add(entry)
            }
        }
        list.sortBy { it.folded }
        entriesByFolded = list
        exactWordSet = exact
    }

    private fun lowerBound(target: String): Int {
        var lo = 0
        var hi = entriesByFolded.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entriesByFolded[mid].folded < target) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    private fun findExactIndex(lower: String, folded: String): Int {
        var i = lowerBound(folded)
        while (i < entriesByFolded.size && entriesByFolded[i].folded == folded) {
            if (entriesByFolded[i].lower == lower) return i
            i++
        }
        return -1
    }

    companion object {
        private const val MAX_BUCKET_LEN = 32

        private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "truelight-dict-io").apply { isDaemon = true }
        }

        fun foldAccents(s: String): String {
            // Fast path for plain ASCII
            var allAscii = true
            for (i in s.indices) {
                if (s[i].code >= 128) {
                    allAscii = false
                    break
                }
            }
            if (allAscii) return s

            val replaced = s.replace('đ', 'd').replace('Đ', 'D')
            val norm = Normalizer.normalize(replaced, Normalizer.Form.NFD)
            val sb = StringBuilder(norm.length)
            for (i in norm.indices) {
                val c = norm[i]
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        /**
         * Returns 0 if equal, 1 if Damerau-Levenshtein distance is 1, or 2 if > 1.
         * Zero-allocation O(N) check!
         */
        fun damerauLevenshtein1(a: String, b: String): Int {
            if (a == b) return 0
            val la = a.length
            val lb = b.length
            if (kotlin.math.abs(la - lb) > 1) return 2

            if (la == lb) {
                var diff1 = -1
                var diff2 = -1
                for (i in 0 until la) {
                    if (a[i] != b[i]) {
                        if (diff1 < 0) diff1 = i
                        else if (diff2 < 0) diff2 = i
                        else return 2
                    }
                }
                // 1 substitution
                if (diff2 < 0) return 1
                // Adjacent transposition (e.g. teh <-> the)
                if (diff2 == diff1 + 1 && a[diff1] == b[diff2] && a[diff2] == b[diff1]) return 1
                return 2
            }

            // Length differs by 1 (insertion/deletion)
            val shorter = if (la < lb) a else b
            val longer = if (la < lb) b else a
            var i = 0
            var j = 0
            var foundDiff = false
            while (i < shorter.length && j < longer.length) {
                if (shorter[i] != longer[j]) {
                    if (foundDiff) return 2
                    foundDiff = true
                    j++
                } else {
                    i++
                    j++
                }
            }
            return 1
        }

        fun applyCasing(typed: String, suggestion: String): String {
            if (typed.isEmpty() || suggestion.isEmpty()) return suggestion
            if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }) {
                return suggestion.uppercase(Locale.ROOT)
            }
            if (typed[0].isUpperCase()) {
                return suggestion.replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
            return suggestion
        }
    }
}
