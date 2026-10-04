package com.offlinestudy.ai.data

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Предмет вопроса. Все предметы обслуживает ОДИН универсальный чат —
 * предмет определяется автоматически и нужен для метки в истории и подсказки модели.
 */
@Serializable
enum class Subject(val title: String, val emoji: String, val tintArgb: Long, val sampleQuestion: String) {
    BIOLOGY("Биология", "🧬", 0xFF34C759, "Что такое фотосинтез?"),
    GEOGRAPHY("География", "🌍", 0xFF30B0C7, "Почему на Земле меняются времена года?"),
    CHEMISTRY("Химия", "⚗️", 0xFFAF52DE, "Чем отличается кислота от щёлочи?"),
    MATH("Математика", "📐", 0xFF007AFF, "Как решать квадратные уравнения?"),
    PHYSICS("Физика", "🔭", 0xFF5856D6, "Что такое сила трения?"),
    HISTORY("История", "📚", 0xFFA2845E, "Почему началась Первая мировая война?"),
    RUSSIAN("Русский язык", "🇷🇺", 0xFFFF3B30, "Чем причастие отличается от деепричастия?"),
    LITERATURE("Литература", "📖", 0xFFFF9500, "О чём роман «Капитанская дочка»?"),
    ENGLISH("Английский", "🇬🇧", 0xFF32ADE6, "Когда используется Present Perfect?"),
    INFORMATICS("Информатика", "💻", 0xFF00C7BE, "Что такое алгоритм?"),
    SOCIAL("Обществознание", "⚖️", 0xFFFF2D55, "Что такое Конституция?"),
    GENERAL("Общие знания", "🧠", 0xFF8E8E93, "Почему небо голубое?");

    val tint: Color get() = Color(tintArgb)
}

@Serializable
enum class ChatMode(val title: String) {
    ASK("Вопрос"),
    SOLVE("Решить задание")
}

@Serializable
enum class MessageRole { USER, ASSISTANT }

@Serializable
enum class MessageKind { NORMAL, SOLVE, SIMPLER, EXAMPLE, ANSWER_ONLY, ERROR }

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val kind: MessageKind = MessageKind.NORMAL,
    val date: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val tokensPerSecond: Double? = null
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый вопрос",
    val subject: Subject = Subject.GENERAL,
    val mode: ChatMode = ChatMode.ASK,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
) {
    val firstQuestion: String
        get() = messages.firstOrNull { it.role == MessageRole.USER }?.text ?: title

    val firstAnswer: String
        get() = messages.firstOrNull {
            it.role == MessageRole.ASSISTANT && it.kind != MessageKind.ERROR && it.text.isNotEmpty()
        }?.text ?: ""

    val isEmpty: Boolean get() = messages.isEmpty()
}

data class FavoriteItem(
    val conversationId: String,
    val question: String,
    val answer: ChatMessage,
    val subject: Subject
)
