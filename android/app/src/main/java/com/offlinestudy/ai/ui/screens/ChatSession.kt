package com.offlinestudy.ai.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.offlinestudy.ai.ai.AIController
import com.offlinestudy.ai.ai.AIEvent
import com.offlinestudy.ai.ai.GenerationParams
import com.offlinestudy.ai.ai.GenerationStats
import com.offlinestudy.ai.ai.ModelManager
import com.offlinestudy.ai.ai.PromptBuilder
import com.offlinestudy.ai.data.AppSettings
import com.offlinestudy.ai.data.ChatMessage
import com.offlinestudy.ai.data.ChatMode
import com.offlinestudy.ai.data.Conversation
import com.offlinestudy.ai.data.HistoryStore
import com.offlinestudy.ai.data.MessageKind
import com.offlinestudy.ai.data.MessageRole
import com.offlinestudy.ai.data.Subject
import com.offlinestudy.ai.util.QuestionSplitter
import com.offlinestudy.ai.util.SubjectDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ЕДИНСТВЕННЫЙ универсальный чат для всех предметов.
 * Живёт на уровне приложения — генерация не прерывается при смене вкладок.
 */
class ChatSession(
    private val ai: AIController,
    private val history: HistoryStore,
    private val settings: AppSettings,
    private val models: ModelManager,
    private val scope: CoroutineScope
) {
    var conversation by mutableStateOf(Conversation())
        private set
    var input by mutableStateOf("")
    var isGenerating by mutableStateOf(false)
        private set
    var streamingMessageId by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var token = UUID.randomUUID().toString()
    private var raw = StringBuilder()

    var mode: ChatMode
        get() = conversation.mode
        set(value) { conversation = conversation.copy(mode = value) }

    val lastAssistantMessageId: String?
        get() = conversation.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id

    fun newChat(mode: ChatMode = ChatMode.ASK, prefill: String = "") {
        finalizeIfNeeded()
        conversation = Conversation(mode = mode)
        input = prefill
        errorMessage = null
    }

    fun open(c: Conversation) {
        finalizeIfNeeded()
        conversation = c
        input = ""
        errorMessage = null
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isGenerating) return
        input = ""
        submit(text, if (conversation.mode == ChatMode.SOLVE) MessageKind.SOLVE else MessageKind.NORMAL)
    }

    fun solve(task: String) {
        if (isGenerating) return
        if (!conversation.isEmpty) newChat(ChatMode.SOLVE)
        conversation = conversation.copy(mode = ChatMode.SOLVE)
        submit(task, MessageKind.SOLVE)
    }

    fun ask(question: String) {
        if (isGenerating) return
        if (!conversation.isEmpty) newChat(ChatMode.ASK)
        submit(question, MessageKind.NORMAL)
    }

    fun explainSimpler() = submit("Объясни проще, как будто мне 10 лет: простыми словами и со сравнением из жизни.", MessageKind.SIMPLER)
    fun giveExample() = submit("Дай конкретный пример по этой теме — из жизни или задачу с решением.", MessageKind.EXAMPLE)
    fun answerOnly() = submit("Напиши только ответ, без условия и объяснений.", MessageKind.ANSWER_ONLY)

    /** «Стоп» срабатывает сразу: ответ фиксируется, а вычисление прерывается в фоне. */
    fun stop() = finalizeIfNeeded()

    fun toggleFavorite(messageId: String) {
        conversation = conversation.copy(messages = conversation.messages.map {
            if (it.id == messageId) it.copy(isFavorite = !it.isFavorite) else it
        })
        history.upsert(conversation)
    }

    fun syncFromHistory() {
        if (isGenerating) return
        val stored = history.conversation(conversation.id)
        conversation = stored ?: if (conversation.isEmpty) conversation else Conversation(mode = conversation.mode)
    }

    private fun shouldDiscourageLatin(subject: Subject, text: String): Boolean {
        val friendly = setOf(Subject.ENGLISH, Subject.INFORMATICS, Subject.MATH, Subject.PHYSICS, Subject.CHEMISTRY)
        if (subject in friendly) return false
        return text.count { it in 'A'..'Z' || it in 'a'..'z' } < 4
    }

    private fun submit(text: String, kind: MessageKind) {
        if (isGenerating) return
        errorMessage = null
        var c = conversation
        if (c.isEmpty) {
            c = c.copy(subject = SubjectDetector.detect(text), title = text.take(90), createdAt = System.currentTimeMillis())
        } else if (c.subject == Subject.GENERAL && (kind == MessageKind.NORMAL || kind == MessageKind.SOLVE)) {
            c = c.copy(subject = SubjectDetector.detect(text))
        }
        c = c.copy(messages = c.messages + ChatMessage(role = MessageRole.USER, text = text, kind = kind))
        val turns = PromptBuilder.turns(c, settings, models.activeModel?.fileName)
        val reply = ChatMessage(role = MessageRole.ASSISTANT, text = "", kind = if (kind == MessageKind.ERROR) MessageKind.NORMAL else kind)
        c = c.copy(messages = c.messages + reply, updatedAt = System.currentTimeMillis())
        conversation = c
        history.upsert(c)

        streamingMessageId = reply.id
        isGenerating = true
        raw = StringBuilder()
        val myToken = UUID.randomUUID().toString()
        token = myToken
        val params = GenerationParams(
            maxTokens = settings.maxAnswerTokens,
            temperature = settings.temperature,
            discourageLatin = shouldDiscourageLatin(c.subject, text)
        )

        // Много вопросов в одном сообщении — решаем каждый отдельно, по очереди.
        if (kind == MessageKind.NORMAL || kind == MessageKind.SOLVE) {
            val items = QuestionSplitter.split(text)
            if (items.size >= 2) {
                runBatch(items, reply.id, myToken, params, c.mode, c.subject)
                return
            }
        }

        job = scope.launch {
            var failure: Throwable? = null
            var stats: GenerationStats? = null
            try {
                ai.generate(turns, params).collect { event ->
                    if (token != myToken) return@collect
                    when (event) {
                        is AIEvent.Token -> {
                            raw.append(event.text)
                            updateMessage(reply.id, PromptBuilder.cleanAnswer(raw.toString()))
                        }
                        is AIEvent.Finished -> stats = event.stats
                    }
                }
            } catch (e: CancellationException) {
                // остановлено пользователем
            } catch (e: Throwable) {
                failure = e
            }
            if (token == myToken) finish(reply.id, stats, failure)
        }
    }

    private fun runBatch(
        items: List<QuestionSplitter.Item>, replyId: String, myToken: String,
        baseParams: GenerationParams, mode: ChatMode, subject: Subject
    ) {
        val modelName = models.activeModel?.fileName
        job = scope.launch {
            val sections = mutableListOf<String>()
            var failure: Throwable? = null
            var lastStats: GenerationStats? = null
            var totalTokens = 0
            var totalSeconds = 0.0
            try {
                for ((index, item) in items.withIndex()) {
                    if (token != myToken) return@launch
                    val header = "**${item.label}.** _${item.preview}_"
                    val itemSubject = if (subject == Subject.GENERAL) SubjectDetector.detect(item.text) else subject
                    val turns = PromptBuilder.batchTurns(item.text, mode, itemSubject, settings, modelName)
                    val params = baseParams.copy(
                        maxTokens = minOf(baseParams.maxTokens, if (settings.answerStyle == com.offlinestudy.ai.data.AnswerStyle.ANSWER_ONLY) 120 else 350),
                        discourageLatin = shouldDiscourageLatin(itemSubject, item.text)
                    )
                    val partial = StringBuilder()
                    val progress = "\n\n_Решаю ${index + 1} из ${items.size}…_"
                    ai.generate(turns, params).collect { event ->
                        if (token != myToken) return@collect
                        when (event) {
                            is AIEvent.Token -> {
                                partial.append(event.text)
                                val current = header + "\n" + PromptBuilder.cleanAnswer(partial.toString())
                                updateMessage(replyId, (sections + current).joinToString("\n\n") + progress)
                            }
                            is AIEvent.Finished -> {
                                lastStats = event.stats
                                totalTokens += event.stats.generatedTokens
                                totalSeconds += event.stats.generationSeconds
                            }
                        }
                    }
                    val answer = PromptBuilder.cleanAnswer(partial.toString())
                    sections += header + "\n" + answer.ifEmpty { "_Нет ответа._" }
                    raw = StringBuilder(sections.joinToString("\n\n"))
                    updateMessage(replyId, raw.toString())
                }
            } catch (e: CancellationException) {
                // остановлено пользователем
            } catch (e: Throwable) {
                failure = e
            }
            if (token != myToken) return@launch
            if (sections.size < items.size && failure == null && sections.isNotEmpty()) {
                raw.append("\n\n_Остановлено: решено ${sections.size} из ${items.size}._")
            }
            val stats = lastStats?.let { if (totalSeconds > 0) it.copy(generatedTokens = totalTokens, generationSeconds = totalSeconds, stoppedByLimit = false) else it }
            finish(replyId, stats, failure)
        }
    }

    private fun updateMessage(id: String, text: String) {
        conversation = conversation.copy(messages = conversation.messages.map { if (it.id == id) it.copy(text = text) else it })
    }

    private fun finish(id: String, stats: GenerationStats?, error: Throwable?) {
        conversation = conversation.copy(
            updatedAt = System.currentTimeMillis(),
            messages = conversation.messages.map { m ->
                if (m.id != id) return@map m
                var text = PromptBuilder.cleanAnswer(raw.toString())
                var kind = m.kind
                when {
                    error != null -> {
                        errorMessage = error.message
                        if (text.isEmpty()) { text = error.message ?: "Ошибка"; kind = MessageKind.ERROR }
                    }
                    text.isEmpty() -> {
                        text = if (stats == null || stats.cancelled) "Генерация остановлена." else "Модель не дала ответа. Попробуйте переформулировать вопрос."
                        kind = MessageKind.ERROR
                    }
                    stats?.stoppedByLimit == true ->
                        text += "\n\n_…ответ обрезан по лимиту длины. Напишите «продолжи», чтобы получить окончание._"
                }
                m.copy(text = text, kind = kind, tokensPerSecond = stats?.tokensPerSecond)
            }
        )
        history.upsert(conversation)
        isGenerating = false
        streamingMessageId = null
        job = null
    }

    private fun finalizeIfNeeded() {
        val id = streamingMessageId ?: return
        if (!isGenerating) return
        token = UUID.randomUUID().toString()
        job?.cancel()
        finish(id, null, null)
    }
}
