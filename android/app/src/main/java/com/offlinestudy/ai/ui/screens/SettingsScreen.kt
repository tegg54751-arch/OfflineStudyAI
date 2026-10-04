package com.offlinestudy.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.BuildConfigInfo
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.data.AnswerStyle
import com.offlinestudy.ai.data.AppTheme
import com.offlinestudy.ai.data.GradeLevel
import com.offlinestudy.ai.data.TextSize
import com.offlinestudy.ai.ui.Overlay
import com.offlinestudy.ai.ui.components.AppBackground
import com.offlinestudy.ai.ui.components.GlassCard
import com.offlinestudy.ai.ui.components.InfoRow
import com.offlinestudy.ai.ui.theme.LocalIsDark
import com.offlinestudy.ai.ui.theme.Palette
import java.util.Locale

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val s = app.settings
    var confirmClear by remember { mutableStateOf(false) }

    AppBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.Bold)

            Section("Оформление") {
                Label("Тема")
                Segmented(AppTheme.entries, s.theme, { it.title }) { s.updateTheme(it) }
                if (s.theme == AppTheme.MINIMAL) Hint("Чёрный фон, белый текст, без цветов и эффектов. Меньше расход батареи на AMOLED-экранах.")
                Label("Размер текста")
                Segmented(TextSize.entries, s.textSize, { it.title }) { s.updateTextSize(it) }
            }

            Section("Ответы AI") {
                Label("Стиль ответа")
                Segmented(AnswerStyle.entries, s.answerStyle, { it.title }) { s.updateAnswerStyle(it) }
                Label("Уровень")
                Segmented(GradeLevel.entries, s.grade, { it.title }) { s.updateGrade(it) }
                Hint("Уровень влияет на сложность объяснений: от простых слов до терминов уровня ЕГЭ.")
            }

            Section("Производительность") {
                Label("Контекст модели")
                Segmented(listOf(1024, 2048, 4096), s.contextSize, { "$it" }) { s.updateContextSize(it) }
                Label("Длина ответа")
                Segmented(listOf(300, 600, 900, 1500), s.maxAnswerTokens, { "$it" }) { s.updateMaxAnswerTokens(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Креативность", Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.1f", s.temperature), color = Color.Gray)
                }
                Slider(value = s.temperature, onValueChange = { s.updateTemperature((it * 10).toInt() / 10f) }, valueRange = 0f..1f, steps = 9)
                Toggle("Загружать модель при запуске", s.preloadOnLaunch) { s.updatePreloadOnLaunch(it) }
                Toggle("Выгружать модель в фоне", s.unloadInBackground) { s.updateUnloadInBackground(it) }
                Hint("Больший контекст помнит длинный диалог, но требует больше памяти. Для учёбы лучше низкая креативность — ответы точнее.")
            }

            Section("AI-модель") {
                LinkRow("🧠 Информация о модели", app.ai.state.title, app.ai.state.color) { app.router.overlay = Overlay.MODEL }
                LinkRow("✈️ Офлайн-режим и тест", null, Color.Gray) { app.router.overlay = Overlay.OFFLINE }
            }

            Section("Данные") {
                Text(
                    "🗑 Очистить историю", color = if (app.history.conversations.isEmpty()) Color.Gray else Palette.Red,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = app.history.conversations.isNotEmpty()) { confirmClear = true }.padding(vertical = 6.dp)
                )
                Hint("Вопросов в истории: ${app.history.conversations.size}. Избранное тоже будет удалено.")
            }

            Section("Приватность") {
                Text("🔒 Вопросы, ответы и фото обрабатываются только на этом телефоне.", fontSize = 14.sp)
                Text("📵 У приложения нет разрешения на интернет, нет серверов, аккаунтов и аналитики.", fontSize = 14.sp)
            }

            GlassCard {
                InfoRow("Версия", BuildConfigInfo.versionName(app))
                InfoRow("Движок", app.ai.backendName)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить всю историю и избранное?") },
            confirmButton = { TextButton({ app.history.clearAll(); app.session.syncFromHistory(); confirmClear = false }) { Text("Удалить всё", color = Palette.Red) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        GlassCard(corner = 22.dp, padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, fontWeight = FontWeight.Medium)

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = Color.Gray)

@Composable
private fun <T> Segmented(options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    val dark = LocalIsDark.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (dark) Color.White.copy(0.07f) else Color.Black.copy(0.05f)).padding(3.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) (if (dark) Color.White.copy(0.16f) else Color.White) else Color.Transparent)
                    .clickable { onSelect(option) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(title(option), fontSize = 12.sp, maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkRow(title: String, value: String?, valueColor: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        if (value != null) Text(value, fontSize = 13.sp, color = valueColor)
        Text("  ›", color = Color.Gray, fontSize = 18.sp)
    }
}
