package com.offlinestudy.ai.ai

import com.offlinestudy.ai.util.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.concurrent.Executors

/** Реализация AIService на llama.cpp (GGUF, CPU с оптимизациями под ARM). */
class LlamaAIService(nativeLibDir: String) : AIService {
    /** Все обращения к модели — в одном потоке, по очереди. */
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()

    @Volatile
    private var handle: Long = 0

    override val backendName = "llama.cpp · GGUF · CPU (ARM NEON)"

    init {
        LlamaBridge.nativeInit(nativeLibDir)
    }

    override suspend fun load(model: File, contextSize: Int): LoadedModelInfo = withContext(dispatcher) {
        freeLocked()
        val started = System.nanoTime()
        val h = LlamaBridge.nativeLoad(model.absolutePath, contextSize, DeviceInfo.recommendedThreads())
        if (h == 0L) {
            throw when (LlamaBridge.nativeLastError()) {
                "context_init_failed" -> AIException.contextFailed()
                else -> AIException.loadFailed(model.name)
            }
        }
        handle = h
        val info = Json.parseToJsonElement(LlamaBridge.nativeInfo(h)).jsonObject
        LoadedModelInfo(
            fileName = model.name,
            description = info["description"]?.jsonPrimitive?.content ?: "",
            fileSizeBytes = model.length(),
            weightsBytes = info["weightsBytes"]?.jsonPrimitive?.long ?: 0,
            parameterCount = info["parameterCount"]?.jsonPrimitive?.long ?: 0,
            contextSize = info["contextSize"]?.jsonPrimitive?.int ?: contextSize,
            trainContextSize = info["trainContextSize"]?.jsonPrimitive?.int ?: 0,
            hasChatTemplate = info["hasChatTemplate"]?.jsonPrimitive?.boolean ?: false,
            loadSeconds = (System.nanoTime() - started) / 1e9
        )
    }

    override suspend fun unload() {
        val h = handle
        if (h != 0L) LlamaBridge.nativeCancel(h)
        withContext(dispatcher) { freeLocked() }
    }

    private fun freeLocked() {
        val h = handle
        handle = 0
        if (h != 0L) LlamaBridge.nativeFree(h)
    }

    override fun generate(turns: List<ChatTurn>, params: GenerationParams): Flow<AIEvent> = callbackFlow {
        val h = handle
        if (h == 0L) throw AIException.notLoaded()

        val job = launch(dispatcher) {
            val result = LlamaBridge.nativeGenerate(
                h,
                turns.map { it.role.wire.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                turns.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                params.maxTokens,
                params.temperature,
                params.topK,
                params.topP,
                params.minP,
                params.repeatPenalty,
                params.discourageLatin
            ) { bytes ->
                trySend(AIEvent.Token(String(bytes, Charsets.UTF_8)))
            }
            if (result.startsWith("ERR:")) {
                val code = result.removePrefix("ERR:")
                when {
                    code == "cancelled" -> close()
                    code == "prompt_too_long" -> close(AIException.promptTooLong())
                    code == "model_not_loaded" -> close(AIException.notLoaded())
                    else -> close(AIException.decodeFailed(code))
                }
                return@launch
            }
            val json = Json.parseToJsonElement(result).jsonObject
            fun d(key: String) = json[key]?.jsonPrimitive?.double ?: 0.0
            fun i(key: String) = json[key]?.jsonPrimitive?.int ?: 0
            fun b(key: String) = json[key]?.jsonPrimitive?.boolean ?: false
            trySend(
                AIEvent.Finished(
                    GenerationStats(
                        promptTokens = i("promptTokens"),
                        reusedPromptTokens = i("reusedPromptTokens"),
                        generatedTokens = i("generatedTokens"),
                        promptSeconds = d("promptSeconds"),
                        generationSeconds = d("generationSeconds"),
                        stoppedByLimit = b("stoppedByLimit"),
                        cancelled = b("cancelled")
                    )
                )
            )
            close()
        }
        awaitClose {
            // Потребитель отменил сбор (кнопка «Стоп», уход с экрана) — останавливаем C++-цикл.
            if (job.isActive) LlamaBridge.nativeCancel(h)
        }
    }.flowOn(Dispatchers.Default)
}
