package com.offlinestudy.ai.util

/**
 * Делит сообщение с несколькими вопросами на отдельные вопросы (как в iOS-версии):
 * заголовки «Задание 1», нумерация «1. …», или строки, заканчивающиеся «?».
 * Варианты ответа остаются внутри своего вопроса.
 */
object QuestionSplitter {
    data class Item(val label: String, val text: String) {
        val preview: String
            get() {
                val first = text.lineSequence().firstOrNull() ?: text
                val cleaned = first.replace(Regex("""^\s*(задание|вопрос|задача|№)?\s*\d{1,2}\s*[.)]?\s*""", RegexOption.IGNORE_CASE), "").trim()
                return if (cleaned.length > 70) cleaned.take(70) + "…" else cleaned
            }
    }

    const val MAX_ITEMS = 30

    fun split(text: String): List<Item> {
        val lines = text.split("\n")

        splitBy(lines, Regex("""^\s*(задание|вопрос|задача|№)\s*(\d{1,2})""", RegexOption.IGNORE_CASE), 2)
            ?.takeIf { it.size >= 2 }?.let { return it.take(MAX_ITEMS) }

        splitBy(lines, Regex("""^\s*(\d{1,2})\s*[.)]\s+\S"""), 1)?.takeIf { it.size >= 2 }?.let { items ->
            val questionLike = items.count { it.text.contains('?') || it.text.length >= 35 || it.text.contains('\n') }
            val allShortOptions = items.all { it.text.length < 35 && !it.text.contains('?') }
            if (questionLike * 2 >= items.size && !allShortOptions) return items.take(MAX_ITEMS)
        }

        val items = mutableListOf<Item>()
        var current = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.endsWith("?")) {
                if (current.isNotEmpty()) items += Item("${items.size + 1}", current.joinToString("\n"))
                current = mutableListOf(trimmed)
            } else if (current.isNotEmpty()) {
                current += trimmed
            }
        }
        if (current.isNotEmpty()) items += Item("${items.size + 1}", current.joinToString("\n"))
        val questionCount = lines.count { it.trim().endsWith("?") }
        return if (questionCount >= 3 && items.size >= 3) items.take(MAX_ITEMS) else emptyList()
    }

    private fun splitBy(lines: List<String>, regex: Regex, group: Int): List<Item>? {
        val items = mutableListOf<Item>()
        var label: String? = null
        var current = mutableListOf<String>()
        for (line in lines) {
            val match = regex.find(line)
            if (match != null) {
                label?.let { items += Item(it, current.joinToString("\n").trim()) }
                label = match.groupValues[group]
                current = mutableListOf(line)
            } else if (label != null) {
                current += line
            }
        }
        label?.let { items += Item(it, current.joinToString("\n").trim()) }
        return items.ifEmpty { null }
    }
}
