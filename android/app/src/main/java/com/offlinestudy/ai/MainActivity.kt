package com.offlinestudy.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.offlinestudy.ai.ui.screens.RootScreen
import com.offlinestudy.ai.ui.theme.OfflineStudyTheme

val LocalApp = staticCompositionLocalOf<OfflineStudyApp> { error("App not provided") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OfflineStudyApp
        setContent {
            OfflineStudyTheme(app.settings.theme, app.settings.textSize.scale) {
                CompositionLocalProvider(LocalApp provides app) {
                    RootScreen()
                }
            }
        }
    }
}
