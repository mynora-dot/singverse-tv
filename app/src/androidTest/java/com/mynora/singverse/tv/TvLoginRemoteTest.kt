@file:Suppress("DEPRECATION")

package com.mynora.singverse.tv

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose, real QR decoding and real encrypted local HTTP; no user's NAS or stored login. */
class TvLoginRemoteTest : InstrumentationTestCase() {
    private lateinit var activity: TvLoginFixtureActivity
    private val app get() = activity.app
    override fun setUp() {
        super.setUp()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("tv-login-fixture", true)
        }) as TvLoginFixtureActivity
        waitFor { onMain { app.login.page == LoginPage.METHODS } }
    }
    override fun tearDown() {
        if (::activity.isInitialized) { onMain { activity.finish() }; instrumentation.waitForIdleSync() }
        super.tearDown()
    }

    fun testDirectQrIsScannableWithoutNasOrAccountFields() {
        openQr()
        assertEquals(LoginPage.QR, onMain { app.login.page })
        assertEquals("", onMain { app.login.host })
        val expected = onMain { app.login.qrUrl }
        val bitmap = capture("login-a-waiting")
        val actual = decode(bitmap)
        assertEquals(expected, actual)
        assertTrue(TvPairingProtocol.privateHost(java.net.URI(TvPairingCode.parse(actual).endpoint).host))
        assertFalse(actual.contains("password")); assertFalse(actual.contains("18080"))
    }

    fun testDefaultChooserAndRemoteNavigationPreserveMethodFocus() {
        assertEquals(LoginPage.METHODS, onMain { app.login.page })
        assertEquals("", onMain { app.login.qrUrl })
        assertEquals(LoginPage.MANUAL, onMain { app.login.selectedMethod })
        capture("login-v2-methods")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.MANUAL } }
        capture("login-v2-account")
        key(KeyEvent.KEYCODE_BACK)
        waitFor { onMain { app.login.page == LoginPage.METHODS } }
        assertEquals(LoginPage.MANUAL, onMain { app.login.selectedMethod })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.DISCOVERY } }
        capture("login-v2-discovery-searching")
        key(KeyEvent.KEYCODE_BACK)
        waitFor { onMain { app.login.page == LoginPage.METHODS } }
        assertFalse(onMain { app.discovery.searching })
        assertEquals(LoginPage.DISCOVERY, onMain { app.login.selectedMethod })
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.QR && app.login.qrUrl.isNotBlank() } }
        val original = onMain { app.login.qrUrl }
        key(KeyEvent.KEYCODE_BACK)
        waitFor { onMain { app.login.page == LoginPage.METHODS } }
        assertEquals("", onMain { app.login.qrUrl })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.QR && app.login.qrUrl.isNotBlank() } }
        assertFalse(original == onMain { app.login.qrUrl })
    }

    fun testDiscoveryCanRetryAndOpenManualAddressWithRemote() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.DISCOVERY } }
        waitFor(20_000) { onMain { !app.discovery.searching } }
        capture("login-v2-discovery-empty")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.discovery.searching } }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.MANUAL } }
        assertFalse(onMain { app.discovery.searching })
        assertEquals("", onMain { app.login.qrUrl })
    }

    fun testAccountValidationStaysInlineAndBackReturnsToChooser() {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.MANUAL } }
        // Initial address -> account -> password -> connect: actual D-pad traversal.
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.error.isNotEmpty() } }
        assertEquals(LoginPage.MANUAL, onMain { app.login.page })
        capture("login-v2-account-validation")
        key(KeyEvent.KEYCODE_BACK)
        assertEquals("", onMain { app.login.error })
        key(KeyEvent.KEYCODE_BACK)
        waitFor { onMain { app.login.page == LoginPage.METHODS } }
    }

    fun testAddressEditorSavesAndRestoresRemoteFocus() {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.page == LoginPage.MANUAL } }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        capture("login-v2-address-dialog")
        instrumentation.sendStringSync("192.168.1.2:18080")
        key(KeyEvent.KEYCODE_BACK) // Close the Android TV keyboard before navigating dialog actions.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        capture("login-v2-address-action-focused")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        capture("login-v2-address-saved")
        waitFor { onMain { app.login.host == "192.168.1.2:18080" } }
        // Saving returns focus to the address row; OK reopens its editor, Back cancels it.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        capture("login-v2-address-reopened")
        instrumentation.sendStringSync("9") // Cancel this unsaved edit.
        repeat(3) { if (onMain { app.login.page == LoginPage.MANUAL }) key(KeyEvent.KEYCODE_BACK) }
        waitFor { onMain { app.login.page == LoginPage.METHODS } }
        assertEquals("192.168.1.2:18080", onMain { app.login.host })
    }

    fun testEncryptedPhoneHandoffConnectsCurrentLibrary() {
        openQr()
        val code = TvPairingCode.parse(onMain { app.login.qrUrl })
        val id = TvPairingProtocol.randomHex(16)
        val preview = exchange(code, "inspect", JSONObject().put("requestId", id))
        assertEquals(id, preview.getString("requestId"))
        assertFalse(onMain { app.connected })
        waitFor { onMain { app.login.pairingStage == PairingStage.SCANNED } }
        assertEquals(onMain { app.login.qrUrl }, decode(capture("login-a-scanned")))
        // Cancelling a preview on the phone must leave the TV scannable for a new attempt.
        val nextPreview = TvPairingProtocol.randomHex(16)
        assertEquals(nextPreview, exchange(code, "inspect", JSONObject().put("requestId", nextPreview)).getString("requestId"))
        assertFalse(onMain { app.connected })
        TvUiFixture().use { nas ->
            val result = exchange(code, "connect", JSONObject().put("requestId", id).put("origin", nas.base).put("token", "fixture-" + "a".repeat(48)))
            assertEquals("${onMain { app.login.pairingMessage }}; ${nas.httpEvents}", "connected", result.getString("state"))
            waitFor { onMain { app.connected } }
            assertEquals(nas.base, onMain { app.client.base })
            assertEquals("fixture-" + "a".repeat(48), onMain { app.client.token })
            assertEquals("connected", exchange(code, "inspect", JSONObject().put("requestId", id)).getString("state"))
            capture("login-a-connected")
        }
    }

    fun testAccountMfaAndRemoteSubmitConnectMusicLibrary() {
        TvUiFixture().use { nas ->
            nas.requireLoginMfa = true
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { onMain { app.login.page == LoginPage.MANUAL } }
            onMain { app.login.setAddress(nas.base); app.login.username = "fixture-admin"; app.login.password = "fixture-password" }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { onMain { app.login.mfaRequired && !app.login.busy } }
            assertEquals("", onMain { app.login.password })
            capture("login-v2-account-mfa")
            onMain { app.login.mfaCode = "123456" }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { onMain { app.connected } }
            assertEquals(nas.base, onMain { app.client.base })
            assertTrue(nas.httpEvents.any { it.contains("POST /api/v1/auth/logout") })
            onMain { app.disconnect() }
            waitFor { onMain { app.login.page == LoginPage.METHODS && !app.connected } }
            assertEquals("", onMain { app.login.qrUrl })
            assertEquals(1, onMain { app.login.recent.size })
            capture("login-v2-recent-methods")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor { onMain { app.login.page == LoginPage.HISTORY } }
            key(KeyEvent.KEYCODE_BACK)
            waitFor { onMain { app.login.page == LoginPage.METHODS } }
        }
    }

    fun testLocalNetworkResultSelectsNasWithoutStartingQr() {
        val manager = instrumentation.targetContext.getSystemService(android.content.Context.NSD_SERVICE) as NsdManager
        val registered = CountDownLatch(1)
        var serviceName = "SingVerse UI test NAS"
        var registrationFailure = false
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { serviceName = info.serviceName; registered.countDown() }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { registrationFailure = true; registered.countDown() }
            override fun onServiceUnregistered(info: NsdServiceInfo) { }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { }
        }
        TvUiFixture().use { nas ->
            val info = NsdServiceInfo().apply { setServiceName(serviceName); serviceType = "_singverse._tcp."; port = URL(nas.base).port }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            try {
                assertTrue("测试广播未注册", registered.await(8, TimeUnit.SECONDS))
                assertFalse("测试广播注册失败", registrationFailure)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { onMain { app.discovery.services.any { it.first == serviceName } } }
                capture("login-v2-discovery-result")
                // Use the same D-pad route as a TV viewer, including the newly arrived result.
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { onMain { app.login.page == LoginPage.MANUAL } }
                assertEquals(serviceName, onMain { app.login.serverName })
                assertTrue(onMain { app.login.host.endsWith(":${info.port}") })
                assertEquals("", onMain { app.login.qrUrl })
                assertFalse(onMain { app.discovery.searching })
                capture("login-v2-account-selected-nas")
            } finally { manager.unregisterService(listener) }
        }
    }

    fun testExpiredStoredLoginReturnsToChooserWithoutQr() {
        openQr()
        TvUiFixture().use { nas ->
            nas.rejectTvLogin = true
            onMain {
                app.client.base = nas.base
                app.client.token = "fixture-expired"
                app.connect()
            }
            waitFor { onMain { app.client.token.isEmpty() && app.login.page == LoginPage.METHODS } }
            assertFalse(onMain { app.connected })
            assertEquals("", onMain { app.error })
            assertEquals(LoginPage.METHODS, onMain { app.login.page })
            assertEquals("", onMain { app.login.qrUrl })
            capture("login-v2-expired-login")
        }
    }

    fun testColdRestoreEntersHomeWhileCatalogLoadsWithoutCreatingPlayer() {
        TvUiFixture().use { nas ->
            val recovery = CountDownLatch(1)
            val home = CountDownLatch(1)
            nas.sessionRecoveryGate = recovery
            nas.homeResponseGate = home
            try {
                launchWithSavedLogin(nas)
                waitFor { onMain { app.restoringConnection } && hasText("正在连接上次的 NAS") }
                assertFalse(hasText("账号登录"))
                recovery.countDown()

                waitFor { onMain { app.connected && !app.restoringConnection } && hasText("首页") }
                waitFor { "begin GET /api/v1/tv/home" in nas.httpEvents }
                assertFalse(nas.httpEvents.any { it.startsWith("end GET /api/v1/tv/home ") })
                onMain {
                    app.background()
                    app.foreground()
                    assertNull(app.initializedPlayer)
                }
                home.countDown()
                waitFor { onMain { app.homeTotal == 20L } }
            } finally {
                recovery.countDown()
                home.countDown()
            }
        }
    }

    fun testColdRestoreWithRevokedCredentialShowsLoginChoices() {
        TvUiFixture().use { nas ->
            nas.rejectTvLogin = true
            launchWithSavedLogin(nas)
            waitFor { !onMain { app.restoringConnection } && hasText("账号登录") }
            assertTrue(hasText("上次的登录已失效"))
            onMain {
                assertFalse(app.connected)
                assertFalse(app.client.hasSavedCredentials)
                assertNull(app.initializedPlayer)
            }
        }
    }

    private fun launchWithSavedLogin(nas: TvUiFixture) {
        onMain { activity.finish() }
        instrumentation.waitForIdleSync()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("tv-login-fixture", true)
            putExtra("tv-login-saved-base", nas.base)
            putExtra("tv-login-saved-token", nas.token)
        }) as TvLoginFixtureActivity
    }

    private fun hasText(text: String): Boolean {
        fun contains(node: AccessibilityNodeInfo): Boolean =
            node.text?.contains(text) == true || node.contentDescription?.contains(text) == true ||
                (0 until node.childCount).any { node.getChild(it)?.let(::contains) == true }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::contains) == true
    }

    fun testExpiredQrCanBeRefreshedWithRemote() {
        onMain { activity.finish() }
        instrumentation.waitForIdleSync()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra("tv-login-fixture", true)
            putExtra("tv-login-lifetime", 3000L)
        }) as TvLoginFixtureActivity
        openQr()
        val original = onMain { app.login.qrUrl }
        waitFor { onMain { app.login.pairingStage == PairingStage.EXPIRED } }
        assertEquals("", onMain { app.login.qrUrl })
        capture("login-a-expired-qr")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor { onMain { app.login.qrUrl.isNotBlank() && app.login.qrUrl != original } }
        assertEquals(onMain { app.login.qrUrl }, decode(capture("login-a-refreshed-qr")))
    }

    fun testServerRejectsWrongKeyExpiredAndReplayedConnect() {
        val calls = AtomicInteger()
        TvPairingServer("127.0.0.1", "测试电视", {}, { _, _ -> calls.incrementAndGet() }).use { server ->
            val code = TvPairingCode(server.code.endpoint, server.code.session, server.code.key.copyOf())
            val wrong = TvPairingCode(code.endpoint, code.session, ByteArray(32))
            assertTrue(runCatching { exchange(wrong, "inspect", JSONObject().put("requestId", "a".repeat(32))) }.isFailure)
            val body = JSONObject().put("requestId", "a".repeat(32)).put("origin", "http://192.168.1.2:18080").put("token", "a".repeat(64))
            assertEquals("connected", exchange(code, "connect", body).getString("state"))
            assertEquals("connected", exchange(code, "connect", body).getString("state"))
            assertEquals(1, calls.get())
            assertTrue(runCatching { exchange(code, "connect", JSONObject(body.toString()).put("requestId", "b".repeat(32))) }.isFailure)
        }
        TvPairingServer("127.0.0.1", "测试电视", {}, { _, _ -> calls.incrementAndGet() }, lifetimeMillis = 1).use { server ->
            assertTrue(runCatching { exchange(server.code, "inspect", JSONObject().put("requestId", "c".repeat(32))) }.isFailure)
        }
        assertEquals(1, calls.get())
    }

    private fun openQr() {
        onMain { app.login.open(LoginPage.QR) }
        waitFor { onMain { app.login.qrUrl.isNotBlank() } }
    }

    private fun exchange(code: TvPairingCode, action: String, clear: JSONObject): JSONObject {
        val sealed = TvPairingProtocol.seal(code.key, code.session, "$action-request", clear.toString().toByteArray())
        val connection = URL("${code.endpoint}/pair/v1/${code.session}/$action").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 30000
            connection.instanceFollowRedirects = false; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("nonce", sealed.first).put("ciphertext", sealed.second).toString().toByteArray()
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode == 200)
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return JSONObject(TvPairingProtocol.open(code.key, code.session, "$action-response", response.getString("nonce"), response.getString("ciphertext")).toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    private fun key(value: Int) { instrumentation.sendKeyDownUpSync(value); instrumentation.waitForIdleSync(); SystemClock.sleep(150) }
    private fun waitFor(timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { instrumentation.waitForIdleSync(); if (condition()) return; SystemClock.sleep(100) }
        fail("等待登录状态超时")
    }
    private fun capture(name: String): Bitmap {
        instrumentation.waitForIdleSync(); SystemClock.sleep(200)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "tv-login-checks").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
}
