package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class TvUpdatePolicyTest {
    private fun release(url: String = TV_APK_PATH, abis: List<String> = emptyList(), minSdk: Int = 21) =
        TvRelease(1001, "0.1.1", "com.mynora.singverse.tv", minSdk, abis, 100, "a".repeat(64), "b".repeat(64), "修复播放", url)

    @Test fun onlyHigherVersionCodesAreOfferedAndManualChecksBypassOneSkippedVersion() {
        assertFalse(shouldOfferUpdate(1000, 1000, -1, false))
        assertFalse(shouldOfferUpdate(999, 1000, -1, true))
        assertFalse(shouldOfferUpdate(1001, 1000, 1001, false))
        assertTrue(shouldOfferUpdate(1001, 1000, 1001, true))
        assertTrue(shouldOfferUpdate(1002, 1000, 1001, false))
    }
    @Test fun skipAndCheckPreferencesAreScopedToTheCanonicalNasOrigin() {
        assertEquals(updatePreferenceKey("http://NAS:80/"), updatePreferenceKey("http://nas"))
        assertNotEquals(updatePreferenceKey("http://nas:18080"), updatePreferenceKey("http://nas:18081"))
        assertNotEquals(updatePreferenceKey("http://nas"), updatePreferenceKey("https://nas"))
        assertThrows(IllegalArgumentException::class.java) { updateOrigin("https://user:secret@nas") }
    }
    @Test fun foregroundCheckIsDueAfter24HoursAndRecoversFromClockChanges() {
        assertTrue(updateCheckDue(10, 0))
        assertFalse(updateCheckDue(10 + UPDATE_CHECK_INTERVAL - 1, 10))
        assertTrue(updateCheckDue(10 + UPDATE_CHECK_INTERVAL, 10))
        assertTrue(updateCheckDue(5, 10))
    }
    @Test fun currentPerformanceIncludingPauseDefersAutomaticPrompts() {
        listOf("PREPARING", "BUFFERING", "PLAYING", "PAUSED").forEach { assertTrue(deferUpdatePrompt(it)) }
        listOf("IDLE", "ENDED", "ERROR").forEach { assertFalse(deferUpdatePrompt(it)) }
    }
    @Test fun compatibilityAndFixedNasDownloadPathAreValidated() {
        assertTrue(release().compatible(21, listOf("armeabi-v7a")))
        assertTrue(release().compatible(22, listOf("armeabi-v7a")))
        assertFalse(release(minSdk = 23).compatible(22, listOf("armeabi-v7a")))
        assertThrows(IllegalArgumentException::class.java) { release(minSdk = 20) }
        assertTrue(release().compatible(23, listOf("armeabi-v7a")))
        assertFalse(release(minSdk = 24).compatible(23, listOf("armeabi-v7a")))
        assertTrue(release(abis = listOf("arm64-v8a")).compatible(35, listOf("arm64-v8a")))
        assertFalse(release(abis = listOf("arm64-v8a")).compatible(35, listOf("x86_64")))
        assertThrows(IllegalArgumentException::class.java) { release(url = "https://other/apk") }
        assertThrows(IllegalArgumentException::class.java) { release(url = "$TV_APK_PATH?redirect=1") }
    }
}
