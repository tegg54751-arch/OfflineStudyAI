package com.offlinestudy.ai.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** История и избранное: один JSON-файл во внутренней памяти приложения. */
class HistoryStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var saveJob: Job? = null

    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set

    init {
        runCatching {
            if (file.exists()) {
                conversations = json.decodeFromString<List<Conversation>>(file.readText())
                    .sortedByDescending { it.updatedAt }
            }
        }
    }

    val favorites: List<FavoriteItem>
        get() = conversations.flatMap { conversation ->
            conversation.messages.mapIndexedNotNull { index, message ->
                if (!message.isFavorite || message.role != MessageRole.ASSISTANT) return@mapIndexedNotNull null
                val question = conversation.messages.subList(0, index).lastOrNull { it.role == MessageRole.USER }?.text
                    ?: conversation.firstQuestion
                FavoriteItem(conversation.id, question, message, conversation.subject)
            }
        }.sortedByDescending { it.answer.date }

    fun conversation(id: String): Conversation? = conversations.firstOrNull { it.id == id }

    fun upsert(conversation: Conversation) {
        if (conversation.isEmpty) return
        val others = conversations.filterNot { it.id == conversation.id }
        conversations = (others + conversation).sortedByDescending { it.updatedAt }
        scheduleSave()
    }

    fun delete(id: String) {
        conversations = conversations.filterNot { it.id == id }
        scheduleSave()
    }

    fun setFavorite(isFavorite: Boolean, messageId: String, conversationId: String) {
        conversations = conversations.map { c ->
            if (c.id != conversationId) c else c.copy(messages = c.messages.map { m ->
                if (m.id == messageId) m.copy(isFavorite = isFavorite) else m
            })
        }
        scheduleSave()
    }

    fun clearAll() {
        conversations = emptyList()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = conversations
        saveJob = scope.launch(Dispatchers.IO) {
            delay(400)
            write(snapshot)
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        val snapshot = conversations
        scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    private fun write(snapshot: List<Conversation>) {
        runCatching {
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            tmp.renameTo(file)
        }
    }
}
