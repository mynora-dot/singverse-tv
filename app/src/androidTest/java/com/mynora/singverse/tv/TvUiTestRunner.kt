@file:Suppress("DEPRECATION")

package com.mynora.singverse.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.test.InstrumentationTestRunner
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking

/** Replaces only the instrumented launch; this class is absent from production APKs. */
class TvUiTestRunner : InstrumentationTestRunner() {
    internal var launchArguments = Bundle()
    override fun onCreate(arguments: Bundle?) { launchArguments = arguments ?: Bundle(); super.onCreate(arguments) }
    override fun newActivity(loader: ClassLoader, className: String, intent: Intent): Activity =
        if (className == MainActivity::class.java.name && intent.hasExtra("party-go-origin")) TvPartyFixtureActivity()
        else if (className == MainActivity::class.java.name && intent.getBooleanExtra("tv-login-fixture", false)) TvLoginFixtureActivity()
        else if (className == MainActivity::class.java.name && intent.hasExtra("tv-ui-fixture")) TvUiFixtureActivity()
        else super.newActivity(loader, className, intent)
}

/** Isolated preferences prevent login tests from touching the user's saved television connection. */
class TvLoginFixtureActivity : ComponentActivity() {
    lateinit var app: TvApp
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isolated = object : ContextWrapper(this) {
            private val prefix = "tv-login-test-${java.util.UUID.randomUUID()}-"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        intent.getStringExtra("tv-login-saved-base")?.let { base ->
            val token = requireNotNull(intent.getStringExtra("tv-login-saved-token"))
            runBlocking { TvClient(isolated).save(base, token) }
        }
        app = TvApp(isolated, pairingLifetimeMillis = intent.getLongExtra("tv-login-lifetime", 300_000))
        setContent { SingVerseTvTheme { if (app.connected || app.restoringConnection) TvScreen(app) else TvLoginScreen(app) } }
    }
    override fun onDestroy() { if (::app.isInitialized) app.close(); super.onDestroy() }
}

/** Real production Compose screens, isolated in-memory data and loopback HTTP only. */
class TvUiFixtureActivity : ComponentActivity() {
    internal val focusMemory = TvFocusMemory()
    lateinit var app: TvApp
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isolated = object : ContextWrapper(this) {
            private val prefix = "tv-ui-test-${java.util.UUID.randomUUID()}-"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val playbackFixture = intent.getBooleanExtra("tv-ui-playback", false)
        if (playbackFixture) {
            isolated.getSharedPreferences("playback-options", Context.MODE_PRIVATE).edit()
                .putInt("volume", 0)
                .putInt("audio-delay", 500)
                .putInt("lyric-offset", 5000)
                .apply()
        }
        app = TvApp(isolated).apply {
            client.base = intent.getStringExtra("tv-ui-fixture")!!
            client.token = "test-fixture-device-only"
            address = client.base
            connected = true
            online = true
            serverName = "UI 验收测试服务"
            snapshot = TvUiFixture.snapshot()
            homeSongs = if (playbackFixture) TvPlaybackFixture.songs() else TvUiFixture.songs()
            if (playbackFixture) tags = TvPlaybackFixture.classificationTags()
            songs = homeSongs
            homeTotal = homeSongs.size.toLong()
            total = homeTotal
            ensureInvitation()
            // Exercise the same HTTP readers as a connected TV instead of filling home state directly.
            refreshFavorites()
            refreshHomeActivity()
        }
        setContent { SingVerseTvTheme { TvScreen(app, focusMemory) } }
    }

    override fun onDestroy() {
        if (::app.isInitialized) app.close()
        super.onDestroy()
    }
}

/** Disposable real-Go integration: only included in the instrumentation APK. */
class TvPartyFixtureActivity : ComponentActivity() {
    lateinit var app: TvApp
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isolated = object : ContextWrapper(this) {
            private val prefix = "tv-party-go-${java.util.UUID.randomUUID()}-"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        app = TvApp(isolated).apply {
            client.base = intent.getStringExtra("party-go-origin")!!
            client.token = intent.getStringExtra("party-go-token")!!
            address = client.base
            connect()
        }
        setContent { SingVerseTvTheme { if (app.connected) TvScreen(app) else TvLoginScreen(app) } }
    }
    override fun onDestroy() { if (::app.isInitialized) app.close(); super.onDestroy() }
}
