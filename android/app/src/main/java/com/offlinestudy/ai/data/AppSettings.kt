package com.offlinestudy.ai.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppTheme(val title: String) { SYSTEM("Системная"), LIGHT("Светлая"), DARK("Тёмная"), MINIMAL("Минимал") }

enum class TextSize(val title: String, val scale: Float) {
    SMALL("Мелкий", 0.9f), NORMAL("Обычный", 1.0f), LARGE("Крупный", 1.15f), EXTRA_LARGE("Очень крупный", 1.3f)
}

enum class AnswerStyle(val title: String) { ANSWER_ONLY("Только ответ"), SHORT("Кратко"), DETAILED("Подробно") }

enum class GradeLevel(val title: String, val promptDescription: String) {
    G5_7("5–7 класс", "5–7 классе (10–13 лет). Объясняй очень просто, с примерами из жизни, без сложных терминов"),
    G8_9("8–9 класс", "8–9 классе (14–15 лет). Объясняй понятно, вводи термины с пояснениями, уровень подготовки к ОГЭ"),
    G10_11("10–11 класс", "10–11 классе (16–17 лет). Можно использовать научные термины, уровень подготовки к ЕГЭ")
}

/** Все настройки — локально в SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme by mutableStateOf(enumOr(prefs.getString("theme", null), AppTheme.SYSTEM))
        private set
    var textSize by mutableStateOf(enumOr(prefs.getString("textSize", null), TextSize.NORMAL))
        private set
    var answerStyle by mutableStateOf(enumOr(prefs.getString("answerStyle", null), AnswerStyle.SHORT))
        private set
    var grade by mutableStateOf(enumOr(prefs.getString("grade", null), GradeLevel.G8_9))
        private set
    var contextSize by mutableStateOf(prefs.getInt("contextSize", 2048))
        private set
    var maxAnswerTokens by mutableStateOf(prefs.getInt("maxAnswerTokens", 900))
        private set
    var temperature by mutableStateOf(prefs.getFloat("temperature", 0.0f))
        private set
    var preloadOnLaunch by mutableStateOf(prefs.getBoolean("preloadOnLaunch", true))
        private set
    var unloadInBackground by mutableStateOf(prefs.getBoolean("unloadInBackground", false))
        private set

    fun updateTheme(v: AppTheme) { theme = v; prefs.edit().putString("theme", v.name).apply() }
    fun updateTextSize(v: TextSize) { textSize = v; prefs.edit().putString("textSize", v.name).apply() }
    fun updateAnswerStyle(v: AnswerStyle) { answerStyle = v; prefs.edit().putString("answerStyle", v.name).apply() }
    fun updateGrade(v: GradeLevel) { grade = v; prefs.edit().putString("grade", v.name).apply() }
    fun updateContextSize(v: Int) { contextSize = v; prefs.edit().putInt("contextSize", v).apply() }
    fun updateMaxAnswerTokens(v: Int) { maxAnswerTokens = v; prefs.edit().putInt("maxAnswerTokens", v).apply() }
    fun updateTemperature(v: Float) { temperature = v; prefs.edit().putFloat("temperature", v).apply() }
    fun updatePreloadOnLaunch(v: Boolean) { preloadOnLaunch = v; prefs.edit().putBoolean("preloadOnLaunch", v).apply() }
    fun updateUnloadInBackground(v: Boolean) { unloadInBackground = v; prefs.edit().putBoolean("unloadInBackground", v).apply() }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
