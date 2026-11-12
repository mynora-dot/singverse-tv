@file:Suppress("DEPRECATION")
package com.mynora.singverse.tv

import android.content.Context
import android.os.Build
import android.test.InstrumentationTestCase
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.util.UUID

/** Run on API 21 and 22 as well as modern devices; aliases/preferences are test-only. */
class TvAndroid5CompatibilityTest : InstrumentationTestCase() {
    private val alias = "singverse-test-${UUID.randomUUID()}"
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences(alias, Context.MODE_PRIVATE)

    override fun tearDown() {
        try {
            preferences.edit().clear().commit()
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias)
            store.deleteEntry("$alias-legacy-rsa")
        } finally { super.tearDown() }
    }

    fun testLegacyCredentialsSurviveRecreationAndUseFreshNonces() {
        val first = TvCredentialCipher(context, preferences, legacy = true, keyAlias = alias)
        val one = first.encrypt("test-device-token")
        val two = first.encrypt("test-device-token")
        assertFalse(one.contains("test-device-token"))
        assertFalse(one == two)
        val recreated = TvCredentialCipher(context, preferences, legacy = true, keyAlias = alias)
        assertEquals("test-device-token", recreated.decrypt(one))
        assertEquals("test-device-token", recreated.decrypt(two))
        assertTrue(preferences.contains("wrapped-device-key"))
        // A later OS upgrade must still be able to read the RSA-wrapped token.
        if (Build.VERSION.SDK_INT >= 23) {
            assertEquals("test-device-token", TvCredentialCipher(context, preferences, legacy = false, keyAlias = alias).decrypt(one))
        }
    }

    fun testLostLegacyKeyRequiresAuthorizationAndCanRecoverOnExplicitSave() {
        val cipher = TvCredentialCipher(context, preferences, legacy = true, keyAlias = alias)
        val old = cipher.encrypt("old-token")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$alias-legacy-rsa") }
        assertTrue(runCatching { cipher.decrypt(old) }.isFailure)
        val replacement = cipher.encrypt("new-token")
        assertEquals("new-token", cipher.decrypt(replacement))
        assertTrue(runCatching { cipher.decrypt(old) }.isFailure)
    }

    fun testModernCredentialFormatRemainsReadable() {
        if (Build.VERSION.SDK_INT < 23) return
        val cipher = TvCredentialCipher(context, preferences, legacy = false, keyAlias = alias)
        val encrypted = cipher.encrypt("modern-token")
        assertFalse(encrypted.contains(':'))
        assertEquals("modern-token", TvCredentialCipher(context, preferences, legacy = false, keyAlias = alias).decrypt(encrypted))
    }

    fun testCompatibilityReportUsesConservativeMediaAndTransportLimits() = runBlocking {
        val report = PlaybackCapabilities.report(compatible = true)
        assertTrue(report.getInt("max_width") in 1..1280)
        assertTrue(report.getInt("max_height") in 1..720)
        assertTrue(report.getInt("max_fps") in 1..30)
        assertTrue(report.getInt("max_bitrate") in 1..4_000_000)
        assertEquals("h264", report.getJSONArray("video").getString(0))
        assertEquals(1, report.getJSONArray("video").length())
        assertFalse(report.getBoolean("fmp4"))
        assertFalse(report.getBoolean("hdr"))
        assertEquals(2, report.getInt("max_channels"))
        assertEquals(8, report.getInt("max_bit_depth"))
    }
}
