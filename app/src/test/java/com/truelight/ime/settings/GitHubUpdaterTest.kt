package com.truelight.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdaterTest {

    @Test
    fun testSemVerComparison() {
        assertTrue(GitHubUpdater.isNewerVersion("1.2.0", "v1.2.1"))
        assertTrue(GitHubUpdater.isNewerVersion("1.2.0", "v1.3.0"))
        assertTrue(GitHubUpdater.isNewerVersion("1.2.0", "v2.0.0"))
        assertFalse(GitHubUpdater.isNewerVersion("1.2.0", "v1.2.0"))
        assertFalse(GitHubUpdater.isNewerVersion("1.2.0", "v1.1.9"))
        assertFalse(GitHubUpdater.isNewerVersion("1.2.0", "1.0.0"))
    }

    @Test
    fun testSelectBestApkAssetPrefersSignedOverDebugAndSkipsUnsigned() {
        val unsigned = GitHubUpdater.ReleaseAsset("TrueLight-v1.2.1-unsigned.apk", "https://example/u.apk", 100)
        val debug = GitHubUpdater.ReleaseAsset("TrueLight-v1.2.1-debug.apk", "https://example/d.apk", 200)
        val signed = GitHubUpdater.ReleaseAsset("TrueLight-v1.2.1.apk", "https://example/s.apk", 150)

        assertEquals(signed, GitHubUpdater.selectBestApkAsset(listOf(unsigned, debug, signed)))
        assertEquals(debug, GitHubUpdater.selectBestApkAsset(listOf(unsigned, debug)))
        assertNull(GitHubUpdater.selectBestApkAsset(listOf(unsigned)))
    }

    @Test
    fun testExtractExpectedSha256() {
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val text = """
            $hashA  TrueLight-v1.2.1.apk
            $hashB *TrueLight-v1.2.1-debug.apk
        """.trimIndent()

        assertEquals(hashA, GitHubUpdater.extractExpectedSha256(text, "TrueLight-v1.2.1.apk"))
        assertEquals(hashB, GitHubUpdater.extractExpectedSha256(text, "TrueLight-v1.2.1-debug.apk"))
        assertNull(GitHubUpdater.extractExpectedSha256(text, "other.apk"))
    }
}
