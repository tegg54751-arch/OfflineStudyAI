package com.offlinestudy.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.ui.AppTab
import com.offlinestudy.ai.ui.Overlay
import com.offlinestudy.ai.ui.theme.LocalIsDark

/** Нижняя навигация: Главная · Спросить (единый чат) · История · Настройки. */
@Composable
fun RootScreen() {
    val app = LocalApp.current
    val router = app.router
    val dark = LocalIsDark.current

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                NavigationBar(containerColor = if (com.offlinestudy.ai.ui.theme.LocalStyle.current.minimal) Color.Black else if (dark) Color(0xEE111124) else Color(0xEEFFFFFF)) {
                    val items = listOf(
                        Triple(AppTab.HOME, "Главная", Icons.Filled.Home),
                        Triple(AppTab.CHAT, "Спросить", Icons.Filled.Create),
                        Triple(AppTab.HISTORY, "История", Icons.Filled.List),
                        Triple(AppTab.SETTINGS, "Настройки", Icons.Filled.Settings)
                    )
                    items.forEach { (tab, label, icon) ->
                        NavigationBarItem(
                            selected = router.tab == tab,
                            onClick = { router.tab = tab },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(targetState = router.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { tab ->
                    when (tab) {
                        AppTab.HOME -> HomeScreen()
                        AppTab.CHAT -> ChatScreen()
                        AppTab.HISTORY -> HistoryScreen()
                        AppTab.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = router.overlay != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            BackHandler { router.overlay = null }
            when (router.overlay) {
                Overlay.MODEL -> ModelScreen(onBack = { router.overlay = null })
                Overlay.OFFLINE -> OfflineScreen(onBack = { router.overlay = null })
                null -> Box(Modifier.fillMaxSize())
            }
        }
    }
}
