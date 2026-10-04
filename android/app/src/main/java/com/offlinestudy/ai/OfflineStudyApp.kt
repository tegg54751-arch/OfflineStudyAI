package com.offlinestudy.ai

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.PowerManager
import com.offlinestudy.ai.ai.LlamaBridge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.offlinestudy.ai.ai.AIController
import com.offlinestudy.ai.ai.LlamaAIService
import com.offlinestudy.ai.ai.ModelManager
import com.offlinestudy.ai.data.AppSettings
import com.offlinestudy.ai.data.HistoryStore
import com.offlinestudy.ai.ocr.OcrService
import com.offlinestudy.ai.ui.AppRouter
import com.offlinestudy.ai.ui.screens.ChatSession
import com.offlinestudy.ai.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Все «сервисы» приложения в одном месте. */
class OfflineStudyApp : Application() {
    lateinit var appScope: CoroutineScope
    lateinit var settings: AppSettings
    lateinit var models: ModelManager
    lateinit var history: HistoryStore
    lateinit var ai: AIController
    lateinit var session: ChatSession
    lateinit var router: AppRouter
    lateinit var network: NetworkMonitor
    lateinit var ocr: OcrService

    override fun onCreate() {
        super.onCreate()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        settings = AppSettings(this)
        models = ModelManager(this)
        history = HistoryStore(this, appScope)
        // ← Здесь выбирается AI-движок. Чтобы заменить llama.cpp, передайте другую реализацию AIService.
        ai = AIController(this, LlamaAIService(applicationInfo.nativeLibraryDir), models, settings, appScope)
        session = ChatSession(ai, history, settings, models, appScope)
        router = AppRouter()
        network = NetworkMonitor(this)
        ocr = OcrService(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Вернулись в приложение — модель загружается сразу, если система её выгрузила.
                ai.preloadIfNeeded()
            }

            override fun onStop(owner: LifecycleOwner) {
                history.saveNow()
                ai.onAppBackground()
            }
        })
        // Перегрев: просим генерацию притормозить, чтобы не «жарить» телефон.
        runCatching {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            power.addThermalStatusListener { status ->
                LlamaBridge.nativeSetThrottle(
                    when {
                        status >= PowerManager.THERMAL_STATUS_CRITICAL -> 80_000
                        status >= PowerManager.THERMAL_STATUS_SEVERE -> 25_000
                        else -> 0
                    }
                )
            }
        }
        ai.preloadIfNeeded()
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) ai.handleMemoryPressure()
    }
}
