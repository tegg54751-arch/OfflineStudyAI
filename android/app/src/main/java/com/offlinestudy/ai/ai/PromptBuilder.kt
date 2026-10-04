package com.offlinestudy.ai.ai

import com.offlinestudy.ai.data.AnswerStyle
import com.offlinestudy.ai.data.AppSettings
import com.offlinestudy.ai.data.ChatMessage
import com.offlinestudy.ai.data.ChatMode
import com.offlinestudy.ai.data.Conversation
import com.offlinestudy.ai.data.MessageKind
import com.offlinestudy.ai.data.MessageRole
import com.offlinestudy.ai.data.Subject

/**
 * Системный промпт + история. Маленькие модели лучше выполняют короткое указание
 * в КОНЦЕ последнего вопроса, поэтому ключевые требования дублируются там
 * (в историю чата это не попадает). Логика совпадает с iOS-версией.
 */
object PromptBuilder {
    private const val MAX_HISTORY = 10

    /** Базовые правила (сформулированы автором приложения). */
    val corePrompt = """
        Ты — Index AI, офлайн-помощник для школьного обучения. Отвечай только на русском языке.
        Не выдумывай факты. Если не уверен в ответе, прямо скажи «Я не уверен» вместо того, чтобы придумывать ответ.
        Для математических и физических задач сначала выполни вычисления, затем проверь результат.
        Для исторических вопросов внимательно проверяй даты, имена и события.
        Для биологии, химии и географии используй общеизвестные школьные факты.
        Если вопрос содержит недостаточно информации, сообщи об этом.
        Ответ должен быть кратким, понятным ученику и соответствовать школьной программе.
    """.trimIndent()

    fun systemPrompt(mode: ChatMode, subject: Subject, settings: AppSettings): String {
        val lines = mutableListOf(corePrompt, "Ученик учится в ${settings.grade.promptDescription}.")
        lines += when (settings.answerStyle) {
            AnswerStyle.ANSWER_ONLY -> "Давай только итоговый ответ, без пересказа условия и без объяснений."
            AnswerStyle.SHORT -> "Сначала сам ответ, потом 1–3 предложения пояснения."
            AnswerStyle.DETAILED -> "Сначала ответ, потом объяснение по шагам и пример."
        }
        if (subject != Subject.GENERAL) lines += "Предмет: ${subject.title}."
        lines += "Не переписывай условие задания и варианты ответа — сразу отвечай."
        lines += "Используй Markdown: **жирный** для главного, списки для шагов. Формулы — в LaTeX внутри \$...\$."
        if (mode == ChatMode.SOLVE) {
            lines += "Режим «Решить задание». Формат:\n**Ответ:** итоговый ответ\n**Решение:** 2–5 коротких шагов с проверкой"
        }
        return lines.joinToString("\n")
    }

    private fun needsNoThink(modelFileName: String?): Boolean {
        val name = modelFileName?.lowercase() ?: return false
        return name.contains("qwen3") && !name.contains("2507") && !name.contains("instruct")
    }

    /** Один вопрос из пачки: без истории чата, с кратким форматом. */
    fun batchTurns(item: String, mode: ChatMode, subject: Subject, settings: AppSettings, modelFileName: String?): List<ChatTurn> {
        val style = settings.answerStyle
        val system = systemPrompt(ChatMode.ASK, subject, settings) + "\nТебе дают один вопрос из списка. Ответь только на него."
        var instruction = finalInstruction(item, MessageKind.NORMAL, mode, style, null)
        instruction += if (style == AnswerStyle.ANSWER_ONLY) " Выведи только ответ одной строкой. Если не уверен — напиши «Я не уверен»."
        else " Формат: «**Ответ:** …», затем не больше двух коротких предложений пояснения. Если не уверен — напиши «Я не уверен»."
        var content = "$item\n\n($instruction)"
        if (needsNoThink(modelFileName)) content += " /no_think"
        return listOf(ChatTurn(ChatTurn.Role.SYSTEM, system), ChatTurn(ChatTurn.Role.USER, content))
    }

    fun turns(conversation: Conversation, settings: AppSettings, modelFileName: String?): List<ChatTurn> {
        val turns = mutableListOf(ChatTurn(ChatTurn.Role.SYSTEM, systemPrompt(conversation.mode, conversation.subject, settings)))
        val usable = conversation.messages.filter { m ->
            if (m.kind == MessageKind.ERROR) return@filter false
            val text = m.text.trim()
            if (text.isEmpty()) return@filter false
            !(m.role == MessageRole.ASSISTANT && containsCjk(text))
        }
        for (m in usable.takeLast(MAX_HISTORY)) {
            turns += when (m.role) {
                MessageRole.USER -> ChatTurn(ChatTurn.Role.USER, m.text)
                MessageRole.ASSISTANT -> ChatTurn(ChatTurn.Role.ASSISTANT, cleanAnswer(m.text))
            }
        }
        val lastUserIndex = turns.indexOfLast { it.role == ChatTurn.Role.USER }
        if (lastUserIndex >= 0) {
            val lastMessage: ChatMessage? = usable.lastOrNull { it.role == MessageRole.USER }
            val previousTask = usable.lastOrNull {
                it.role == MessageRole.USER && it.kind !in setOf(MessageKind.ANSWER_ONLY, MessageKind.SIMPLER, MessageKind.EXAMPLE)
            }?.text
            val instruction = finalInstruction(
                turns[lastUserIndex].content, lastMessage?.kind ?: MessageKind.NORMAL,
                conversation.mode, settings.answerStyle, previousTask
            )
            var content = turns[lastUserIndex].content
            if (lastMessage?.kind == MessageKind.SOLVE) content = "Задание:\n$content"
            content += "\n\n($instruction)"
            if (needsNoThink(modelFileName)) content += " /no_think"
            turns[lastUserIndex] = turns[lastUserIndex].copy(content = content)
        }
        return turns
    }

    fun finalInstruction(text: String, kind: MessageKind, mode: ChatMode, style: AnswerStyle, previousTask: String?): String {
        val parts = mutableListOf<String>()
        val answerOnly = kind == MessageKind.ANSWER_ONLY || wantsAnswerOnly(text)
        val taskText = if (answerOnly) previousTask ?: text else text
        val multipleChoice = looksLikeMultipleChoice(taskText)
        val manyTasks = countNumberedTasks(taskText) >= 2

        if (answerOnly || style == AnswerStyle.ANSWER_ONLY) {
            parts += when {
                manyTasks -> "Выведи ТОЛЬКО ответы списком: «номер задания — ответ», каждый с новой строки. Не переписывай условия и не объясняй"
                multipleChoice -> "Выведи ТОЛЬКО правильный вариант: букву или номер и его текст. Не переписывай условие и не объясняй"
                else -> "Выведи ТОЛЬКО ответ одной-двумя строками. Не переписывай условие и не объясняй"
            }
        } else {
            val lower = taskText.lowercase()
            val matching = lower.contains("соответств")
            val blank = isFillInTheBlank(taskText)
            if (manyTasks) parts += "В тексте несколько заданий — ответь на каждое по порядку, начиная с его номера. Не переписывай условия"
            if (matching) parts += "В задании на соответствие слева — пронумерованные элементы (1, 2, 3…), справа — буквы (А, Б, В…). Порядок строк НЕ означает соответствие: для каждого номера выбери букву по смыслу. Ответ в виде «1 — Б, 2 — А, 3 — В» и по одному предложению, почему"
            if (blank) parts += "В задании с пропуском («…», «___») вставь подходящее по смыслу школьное слово или термин и напиши предложение целиком. Не придумывай названия, которых нет в учебнике"
            if (multipleChoice && !(matching && !manyTasks)) parts += "В тесте с вариантами сначала напиши «**Ответ:** буква/номер — текст правильного варианта», затем одно-два предложения, почему. Не переписывай вопрос и варианты"
            if (parts.isEmpty() && (mode == ChatMode.SOLVE || kind == MessageKind.SOLVE)) parts += "Начни со строки «**Ответ:** …», потом коротко реши по шагам. Не переписывай условие"
        }
        parts += "Отвечай на русском языке"
        return parts.joinToString(". ") + "."
    }

    fun wantsAnswerOnly(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("только ответ", "одни ответы", "без решения", "без объяснен", "просто ответ", "только правильн",
            "скинь ответ", "дай ответ", "напиши ответ", "только буквы", "без пояснен", "кратко ответ", "только вариант")
            .any { lower.contains(it) }
    }

    fun isFillInTheBlank(text: String): Boolean {
        val lower = text.lowercase()
        if (listOf("пропущ", "вставьте", "вставь ", "впишите", "заполните пропуск", "закончите предложение", "допишите").any { lower.contains(it) }) return true
        return text.contains("___") || text.contains("…") || Regex("""\.{3,}|_{2,}""").containsMatchIn(text)
    }

    private val optionRegex = Regex("""^\s*([А-Еа-еA-Ea-e]|[1-6])\s*[).]\s*\S""", RegexOption.MULTILINE)

    fun looksLikeMultipleChoice(text: String): Boolean {
        val matches = optionRegex.findAll(text).toList()
        if (matches.count { it.groupValues[1].first().isLetter() } >= 2) return true
        val lower = text.lowercase()
        val hints = listOf("выбер", "вариант", "укажите", "какой из", "какая из", "какое из", "верн", "правильн")
        return matches.size >= 3 && (text.contains('?') || hints.any { lower.contains(it) })
    }

    fun countNumberedTasks(text: String): Int {
        val explicit = Regex("""^\s*(задание|задача|вопрос|№)\s*\d{1,2}""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val explicitCount = explicit.findAll(text).count()
        if (explicitCount > 0) return explicitCount
        val count = Regex("""^\s*\d{1,2}\s*[.)]\s+\S""", RegexOption.MULTILINE).findAll(text).count()
        if (looksLikeMultipleChoice(text) || text.lowercase().contains("соответств")) return 0
        return count
    }

    fun containsCjk(text: String): Boolean = text.codePoints().anyMatch { cp ->
        cp in 0x3000..0x9FFF || cp in 0xAC00..0xD7AF || cp in 0xFF00..0xFFEF
    }

    fun cleanAnswer(raw: String): String {
        var text = raw
        while (true) {
            val start = text.indexOf("<think>")
            if (start < 0) break
            val end = text.indexOf("</think>", start)
            text = if (end >= 0) text.removeRange(start, end + "</think>".length) else text.substring(0, start)
        }
        for (marker in listOf("<|im_end|>", "<|im_start|>", "<|eot_id|>", "<end_of_turn>", "</s>")) {
            text = text.replace(marker, "")
        }
        return text.trim()
    }
}
