package com.offlinestudy.ai.ai

import kotlinx.coroutines.flow.Flow
import java.io.File

// Всё приложение общается с моделью только через интерфейс AIService.
// Чтобы заменить llama.cpp на другой движок, достаточно новой реализации
// и одной строки в OfflineStudyApp.kt.

data class ChatTurn(val role: Role, var content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class GenerationParams(
    val maxTokens: Int = 900,
    val temperature: Float = 0.0f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    /** Штрафовать латинские слова в ответах на русском. */
    val discourageLatin: Boolean = false
)

data class GenerationStats(
    val promptTokens: Int,
    val reusedPromptTokens: Int,
    val generatedTokens: Int,
    val promptSeconds: Double,
    val generationSeconds: Double,
    val stoppedByLimit: Boolean,
    val cancelled: Boolean
) {
    val tokensPerSecond: Double
        get() = if (generationSeconds > 0) generatedTokens / generationSeconds else 0.0
}

data class LoadedModelInfo(
    val fileName: String,
    val description: String,
    val fileSizeBytes: Long,
    val weightsBytes: Long,
    val parameterCount: Long,
    val contextSize: Int,
    val trainContextSize: Int,
    val hasChatTemplate: Boolean,
    val loadSeconds: Double
)

sealed interface AIEvent {
    data class Token(val text: String) : AIEvent
    data class Finished(val stats: GenerationStats) : AIEvent
}

class AIException(message: String) : Exception(message) {
    companion object {
        fun noModel() = AIException("Для работы AI необходимо установить локальную модель.")
        fun notLoaded() = AIException("Модель не загружена в память.")
        fun invalidFile() = AIException("Файл не похож на модель в формате GGUF.")
        fun loadFailed(name: String) = AIException("Не удалось загрузить модель «$name». Возможно, файл повреждён или не поддерживается.")
        fun contextFailed() = AIException("Не удалось создать контекст модели. Уменьшите размер контекста в настройках.")
        fun insufficientMemory(needed: String, available: String) = AIException(
            "Недостаточно оперативной памяти: нужно примерно $needed, свободно $available. " +
                "Закройте другие приложения, уменьшите контекст или выберите модель поменьше."
        )
        fun promptTooLong() = AIException("Вопрос слишком длинный для контекста модели. Сократите текст или увеличьте контекст.")
        fun decodeFailed(code: String) = AIException("Ошибка вычисления модели ($code). Возможно, не хватает памяти — начните новый чат.")
    }
}

interface AIService {
    val backendName: String
    /** safeLevel: 0 — обычный режим, 1–2 — режимы совместимости для проблемных процессоров. */
    suspend fun load(model: File, contextSize: Int, safeLevel: Int = 0): LoadedModelInfo
    suspend fun unload()
    fun generate(turns: List<ChatTurn>, params: GenerationParams): Flow<AIEvent>
}
