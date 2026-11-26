package com.mynora.singverse.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private var app: TvApp? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = TvApp(this)
        app = state
        setContent {
            SingVerseTvTheme {
                TvScreen(state)
                if (!state.restoringConnection) ReportDrawn()
            }
        }
    }
    override fun onStart() { super.onStart(); app?.foreground() }
    override fun onResume() { super.onResume(); app?.resumeInstallation() }
    override fun onStop() { app?.background(); super.onStop() }
    override fun onDestroy() { app?.close(); super.onDestroy() }
}
