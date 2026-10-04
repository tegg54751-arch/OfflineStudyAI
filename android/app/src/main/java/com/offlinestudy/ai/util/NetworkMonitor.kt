package com.offlinestudy.ai.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Только ПОКАЗЫВАЕТ состояние сети на экране «Офлайн-режим».
 * У приложения нет разрешения INTERNET — оно физически не может выйти в сеть.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())

    var isConnected by mutableStateOf(false)
        private set
    var usesWifi by mutableStateOf(false)
        private set
    var usesCellular by mutableStateOf(false)
        private set

    init {
        update()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = post()
                override fun onLost(network: Network) = post()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
            })
        }
    }

    private fun post() { main.post { update() } }

    private fun update() {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        usesWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        usesCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    val statusDescription: String
        get() = when {
            !isConnected -> "Сети нет (режим полёта или нет сигнала)"
            usesWifi -> "Подключён Wi-Fi"
            usesCellular -> "Подключена мобильная сеть"
            else -> "Есть подключение"
        }
}
