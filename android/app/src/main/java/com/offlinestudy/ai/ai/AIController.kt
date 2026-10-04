package com.offlinestudy.ai.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.offlinestudy.ai.data.AppSettings
import com.offlinestudy.ai.util.DeviceInfo
import com.offlinestudy.ai.util.Format
import com.offlinestudy.ai.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class ModelState(val title: String, val color: Color) {
    data object NotInstalled : ModelState("Модель не установлена", Color(0xFFFF3B30))
    data object NotLoaded : ModelState("Не загружена в память", Color(0xFFFF9500))
    data object Loading : ModelState("Загрузка…", Color(0xFF007AFF))
    data object Ready : ModelState("Готова к работе", Color(0xFF34C759))
    data object Generating : ModelState("Генерирует ответ", Color(0xFF007AFF))
    data class Failed(val message: String) : ModelState("Ошибка", Color(0xFFFF3B30))
}

data class OfflineTestResult(
    val success: Boolean,
    val answer: String,
    val networkAvailable: Boolean,
    val networkDescription: String,
    val loadSeconds: Double?,
    val stats: GenerationStats?,
    val error: String?,
    val date: Long = System.currentTimeMillis()
)

/** Состояние модели, автозагрузка, освобождение памяти, офлайн-тест. */
class AIController(
    private val context: Context,
    private val service: AIService,
    private val models: ModelManager,
    private val settings: AppSettings,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf<ModelState>(ModelState.NotLoaded)
        private set
    var loadedInfo by mutableStateOf<LoadedModelInfo?>(null)
        private set
    var lastStats by mutableStateOf<GenerationStats?>(null)
        private set
    var lastMemoryWarning by mutableStateOf<Long?>(null)
        private set

    private var loadedPath: String? = null
    private var loadedContext = 0
    private val loadMutex = Mutex()
    private var activeGenerations = 0

    val backendName: String get() = service.backendName
    val isGenerating: Boolean get() = state == ModelState.Generating

    init { refreshState() }

    fun refreshState() {
        if (state == ModelState.Loading || state == ModelState.Generating) return
        state = when {
            !models.hasModel -> ModelState.NotInstalled
            loadedInfo == null -> if (state is ModelState.Failed) state else ModelState.NotLoaded
            else -> ModelState.Ready
        }
    }

    fun preloadIfNeeded() {
        if (!settings.preloadOnLaunch || !models.hasModel || loadedInfo != null) { refreshState(); return }
        scope.launch { runCatching { ensureLoaded() } }
    }

    suspend fun ensureLoaded(): LoadedModelInfo = loadMutex.withLock {
        models.refresh()
        val model = models.activeModel ?: run {
            state = ModelState.NotInstalled
            throw AIException.noModel()
        }
        loadedInfo?.let { info ->
            if (loadedPath == model.file.absolutePath && loadedContext == settings.contextSize) return@withLock info
        }

        val needed = DeviceInfo.estimatedMemory(model.sizeBytes, settings.contextSize)
        if (loadedInfo != null) unloadInternal()
        val available = DeviceInfo.availableRam(context)
        val total = DeviceInfo.totalRam(context)
        // Веса модели читаются через mmap (файловые страницы), поэтому строгая проверка не нужна:
        // отказываем, только если модель явно не помещается в телефон.
        if (needed > total * 3 / 4 || available < needed / 2) {
            val error = AIException.insufficientMemory(Format.bytes(needed), Format.bytes(available))
            state = ModelState.Failed(error.message ?: "")
            throw error
        }

        state = ModelState.Loading
        try {
            val info = service.load(model.file, settings.contextSize)
            loadedInfo = info
            loadedPath = model.file.absolutePath
            loadedContext = settings.contextSize
            state = ModelState.Ready
            warmUp()
            info
        } catch (e: Exception) {
            loadedInfo = null
            loadedPath = null
            state = ModelState.Failed(e.message ?: "Ошибка загрузки")
            throw e
        }
    }

    /** Сразу после загрузки обрабатываем системные правила, чтобы первый ответ начался быстрее. */
    private fun warmUp() {
        scope.launch {
            runCatching {
                val turns = listOf(
                    ChatTurn(ChatTurn.Role.SYSTEM, PromptBuilder.systemPrompt(com.offlinestudy.ai.data.ChatMode.ASK, com.offlinestudy.ai.data.Subject.GENERAL, settings)),
                    ChatTurn(ChatTurn.Role.USER, "Привет")
                )
                service.generate(turns, GenerationParams(maxTokens = 0)).collect { }
            }
        }
    }

    suspend fun unload() {
        if (activeGenerations > 0) return
        loadMutex.withLock { unloadInternal() }
    }

    private suspend fun unloadInternal() {
        service.unload()
        loadedInfo = null
        loadedPath = null
        loadedContext = 0
        state = if (models.hasModel) ModelState.NotLoaded else ModelState.NotInstalled
    }

    fun generate(turns: List<ChatTurn>, params: GenerationParams): Flow<AIEvent> = flow {
        activeGenerations++
        try {
            ensureLoaded()
            state = ModelState.Generating
            service.generate(turns, params).collect { event ->
                if (event is AIEvent.Finished) lastStats = event.stats
                emit(event)
            }
        } finally {
            activeGenerations--
            if (state == ModelState.Generating) state = ModelState.Ready
        }
    }

    suspend fun runOfflineTest(network: NetworkMonitor): OfflineTestResult {
        val networkAvailable = network.isConnected
        val description = network.statusDescription
        val wasLoaded = loadedInfo != null
        return try {
            val info = ensureLoaded()
            val turns = listOf(
                ChatTurn(ChatTurn.Role.SYSTEM, "Ты помощник. Отвечай очень коротко, на русском языке."),
                ChatTurn(ChatTurn.Role.USER, "Сколько будет 2 + 2? Ответь одним числом." +
                    if (info.fileName.lowercase().contains("qwen3")) " /no_think" else "")
            )
            val answer = StringBuilder()
            var stats: GenerationStats? = null
            generate(turns, GenerationParams(maxTokens = 24, temperature = 0f)).collect { event ->
                when (event) {
                    is AIEvent.Token -> answer.append(event.text)
                    is AIEvent.Finished -> stats = event.stats
                }
            }
            val clean = PromptBuilder.cleanAnswer(answer.toString())
            OfflineTestResult(clean.isNotEmpty(), clean, networkAvailable, description,
                if (wasLoaded) null else info.loadSeconds, stats,
                if (clean.isEmpty()) "Модель вернула пустой ответ." else null)
        } catch (e: Exception) {
            OfflineTestResult(false, "", networkAvailable, description, null, null, e.message)
        }
    }

    /** Система просит освободить память — выгружаем модель, если она не занята. */
    fun handleMemoryPressure() {
        lastMemoryWarning = System.currentTimeMillis()
        if (activeGenerations == 0 && loadedInfo != null) scope.launch { unload() }
    }

    fun onAppBackground() {
        if (settings.unloadInBackground && activeGenerations == 0) scope.launch { unload() }
    }
}
