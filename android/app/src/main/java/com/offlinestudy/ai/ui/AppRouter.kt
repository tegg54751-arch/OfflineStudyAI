package com.offlinestudy.ai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppTab { HOME, CHAT, HISTORY, SETTINGS }
enum class Overlay { MODEL, OFFLINE }
enum class PhotoSource { CAMERA, GALLERY }

/** Навигация: вкладки, полноэкранные страницы и «отложенные» действия. */
class AppRouter {
    var tab by mutableStateOf(AppTab.HOME)
    var overlay by mutableStateOf<Overlay?>(null)
    var pendingPhoto by mutableStateOf<PhotoSource?>(null)
}
