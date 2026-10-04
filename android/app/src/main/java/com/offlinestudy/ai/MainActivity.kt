package com.offlinestudy.ai

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.offlinestudy.ai.ui.screens.RootScreen
import com.offlinestudy.ai.ui.theme.LocalIsDark
import com.offlinestudy.ai.ui.theme.OfflineStudyTheme

val LocalApp = staticCompositionLocalOf<OfflineStudyApp> { error("App not provided") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OfflineStudyApp
        setContent {
            OfflineStudyTheme(app.settings.theme, app.settings.textSize.scale) {
                SystemBarsColors()
                CompositionLocalProvider(LocalApp provides app) {
                    RootScreen()
                }
            }
        }
    }
}

/** Значки статус-бара светлые на тёмной теме и тёмные на светлой. */
@Composable
private fun SystemBarsColors() {
    val view = LocalView.current
    val dark = LocalIsDark.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}
