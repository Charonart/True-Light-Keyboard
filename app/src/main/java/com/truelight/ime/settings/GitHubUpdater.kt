package com.truelight.ime.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Lightweight, zero-extra-dependency GitHub Releases Auto-Updater.
 * Runs STRICTLY inside the isolated `:settings` process when the user taps "Check for Updates".
 * Never loaded by the main TrueLightIME keyboard process.
 */
object GitHubUpdater {

    const val REPO_API_LATEST = "https://api.github.com/repos/Charonart/True-Light-Keyboard/releases/latest"
    private const val FILE_PROVIDER_AUTHORITY = "com.truelight.ime.fileprovider"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    data class ReleaseInfo(
        val tagName: String,
        val title: String,
        val notes: String,
        val htmlUrl: String,
        val apkAsset: ReleaseAsset?,
        val sha256SumsUrl: String?
    )

    sealed class CheckResult {
        data class UpdateAvailable(val release: ReleaseInfo) : CheckResult()
        data class UpToDate(val currentVersion: String, val latestTag: String, val htmlUrl: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    sealed class DownloadResult {
        data class Ready(val apkFile: File, val shaVerified: Boolean) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /**
     * Queries GitHub Releases API synchronously (call on a background thread).
     */
    fun checkLatestRelease(currentVersion: String): CheckResult {
        return try {
            val jsonText = fetchText(REPO_API_LATEST)
            val info = parseReleaseJson(jsonText)
            if (isNewerVersion(currentVersion, info.tagName)) {
                CheckResult.UpdateAvailable(info)
            } else {
                CheckResult.UpToDate(currentVersion, info.tagName, info.htmlUrl)
            }
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "Unable to reach GitHub")
        }
    }

    /**
     * Downloads the release APK into cacheDir/updates/, verifies SHA-256 if SHA256SUMS.txt is present.
     * Call on a background thread.
     */
    fun downloadAndVerify(
        context: Context,
        release: ReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): DownloadResult {
        val asset = release.apkAsset ?: return DownloadResult.Error("No installable APK found in release ${release.tagName}")
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clean previous cached APKs so cache never grows
            dir.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

            val outFile = File(dir, asset.name)
            val conn = openConnection(asset.downloadUrl)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return DownloadResult.Error("Download failed (HTTP $code)")
                }
                val contentLen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    conn.contentLengthLong
                } else {
                    conn.contentLength.toLong()
                }
                val total = if (contentLen > 0L) contentLen else asset.sizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(outFile).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }

                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                var shaVerified = false
                if (!release.sha256SumsUrl.isNullOrBlank()) {
                    try {
                        val sumsText = fetchText(release.sha256SumsUrl)
                        val expectedSha = extractExpectedSha256(sumsText, asset.name)
                        if (expectedSha != null) {
                            if (!expectedSha.equals(actualSha, ignoreCase = true)) {
                                outFile.delete()
                                return DownloadResult.Error("SHA-256 verification failed! Downloaded file is corrupted.")
                            }
                            shaVerified = true
                        }
                    } catch (_: Exception) {
                        // Proceed if checksum file fetch fails transiently; shaVerified remains false
                    }
                }

                DownloadResult.Ready(outFile, shaVerified)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Download failed")
        }
    }

    /**
     * Checks unknown-source install permission (API 26+) and launches Android's Package Installer.
     * Returns true if installer launched, false if user was redirected to grant install permission first.
     */
    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return false
        }

        val apkUri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    internal fun parseReleaseJson(jsonText: String): ReleaseInfo {
        val root = JSONObject(jsonText)
        val tag = root.optString("tag_name", "")
        val name = root.optString("name", tag).ifBlank { tag }
        val body = root.optString("body", "").trim()
        val htmlUrl = root.optString("html_url", "https://github.com/Charonart/True-Light-Keyboard/releases")
        val assetsArr = root.optJSONArray("assets") ?: JSONArray()

        val allAssets = ArrayList<ReleaseAsset>(assetsArr.length())
        var shaUrl: String? = null
        for (i in 0 until assetsArr.length()) {
            val obj = assetsArr.optJSONObject(i) ?: continue
            val aName = obj.optString("name", "")
            val aUrl = obj.optString("browser_download_url", "")
            val aSize = obj.optLong("size", 0L)
            if (aName.equals("SHA256SUMS.txt", ignoreCase = true) && aUrl.isNotEmpty()) {
                shaUrl = aUrl
            } else if (aName.endsWith(".apk", ignoreCase = true) && aUrl.isNotEmpty()) {
                allAssets.add(ReleaseAsset(aName, aUrl, aSize))
            }
        }

        return ReleaseInfo(
            tagName = tag,
            title = name,
            notes = body,
            htmlUrl = htmlUrl,
            apkAsset = selectBestApkAsset(allAssets),
            sha256SumsUrl = shaUrl
        )
    }

    /**
     * Prefers signed release APK (not containing "-unsigned" or "-debug").
     * Falls back to "-debug.apk" if no signed release exists yet.
     * Never selects an "-unsigned.apk" (Android refuses to install unsigned APKs).
     */
    internal fun selectBestApkAsset(apkAssets: List<ReleaseAsset>): ReleaseAsset? {
        val signedRelease = apkAssets.firstOrNull { a ->
            val lower = a.name.lowercase()
            lower.endsWith(".apk") && !lower.contains("unsigned") && !lower.contains("debug")
        }
        if (signedRelease != null) return signedRelease

        return apkAssets.firstOrNull { a ->
            val lower = a.name.lowercase()
            lower.endsWith(".apk") && lower.contains("debug") && !lower.contains("unsigned")
        }
    }

    /**
     * Semantic version comparison: returns true strictly when remoteTag > currentVersion.
     * Handles optional "v" prefix and suffixes like "-beta".
     */
    internal fun isNewerVersion(currentVersion: String, remoteTag: String): Boolean {
        val cur = parseSemVer(currentVersion)
        val rem = parseSemVer(remoteTag)
        for (i in 0 until 3) {
            if (rem[i] > cur[i]) return true
            if (rem[i] < cur[i]) return false
        }
        return false
    }

    internal fun parseSemVer(version: String): IntArray {
        val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        val parts = clean.split('.')
        return intArrayOf(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }

    internal fun extractExpectedSha256(shaSumsText: String, targetFilename: String): String? {
        for (rawLine in shaSumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val hash = parts[0].trim()
                val file = parts[1].trim().removePrefix("*")
                if (file.equals(targetFilename, ignoreCase = true) && hash.length == 64) {
                    return hash
                }
            }
        }
        return null
    }

    private fun openConnection(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "TrueLightKeyboard-Android")
        return conn
    }

    private fun fetchText(urlStr: String): String {
        val conn = openConnection(urlStr)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP $code")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
