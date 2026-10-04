package com.offlinestudy.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.ai.OfflineTestResult
import com.offlinestudy.ai.ui.components.AppBackground
import com.offlinestudy.ai.ui.components.GlassCard
import com.offlinestudy.ai.ui.components.InfoRow
import com.offlinestudy.ai.ui.components.PrimaryButton
import com.offlinestudy.ai.ui.components.StatusRow
import com.offlinestudy.ai.ui.theme.Palette
import com.offlinestudy.ai.util.Format
import kotlinx.coroutines.launch
import java.util.Locale

/** Страница «Офлайн-режим»: статус и реальный локальный тест генерации. */
@Composable
fun OfflineScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val network = app.network
    val models = app.models
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OfflineTestResult?>(null) }

    AppBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Назад", color = Palette.Indigo, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp, top = 6.dp, bottom = 6.dp))
                Text("Офлайн-режим", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            GlassCard {
                val active = models.activeModel
                StatusRow(active != null, if (active != null) "AI-модель установлена" else "AI-модель не установлена",
                    active?.let { "${it.fileName} · ${Format.bytes(it.sizeBytes)}" } ?: "Установите модель в разделе «AI-модель»")
                HorizontalDivider()
                StatusRow(true, "Интернет не требуется", "У приложения нет разрешения на интернет")
                HorizontalDivider()
                StatusRow(true, "Wi-Fi не требуется")
                HorizontalDivider()
                StatusRow(true, "Мобильная сеть не требуется")
                HorizontalDivider()
                StatusRow(true, "Распознавание фото — на устройстве", "Tesseract, без отправки изображений")
            }

            GlassCard(corner = 22.dp, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (network.isConnected) "📶" else "✈️", fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Сеть сейчас", fontSize = 12.sp, color = Color.Gray)
                        Text(network.statusDescription, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            PrimaryButton(if (testing) "Проверяю…" else "Проверить офлайн-работу", enabled = !testing && models.hasModel && !app.ai.isGenerating) {
                scope.launch {
                    testing = true
                    result = app.ai.runOfflineTest(network)
                    testing = false
                }
            }
            if (network.isConnected) {
                Text("✈️ Для честной проверки включите режим полёта, затем нажмите кнопку.", fontSize = 13.sp, color = Color.Gray)
            }

            result?.let { r ->
                GlassCard {
                    Text(if (r.success) "✅ Локальный AI работает" else "❌ Тест не пройден", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            !r.success -> "Проверьте, что модель установлена и хватает памяти."
                            r.networkAvailable -> "Ответ получен локально. Сеть была включена, но не использовалась — повторите в режиме полёта."
                            else -> "Ответ получен при выключенной сети — генерация точно идёт на телефоне."
                        }, fontSize = 13.sp, color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    if (r.success) {
                        InfoRow("Вопрос", "Сколько будет 2 + 2?")
                        InfoRow("Ответ модели", r.answer)
                    }
                    r.error?.let { Text(it, color = Palette.Red, fontSize = 13.sp) }
                    InfoRow("Сеть во время теста", r.networkDescription)
                    r.loadSeconds?.let { InfoRow("Загрузка модели", String.format(Locale.US, "%.1f с", it)) }
                    r.stats?.let {
                        InfoRow("Обработка вопроса", String.format(Locale.US, "%.2f с", it.promptSeconds))
                        InfoRow("Скорость генерации", String.format(Locale.US, "%.1f токенов/с", it.tokensPerSecond))
                    }
                    InfoRow("Время теста", Format.date(r.date))
                }
            }

            GlassCard {
                Text("Как это устроено", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("• Модель (.gguf) хранится в памяти телефона и запускается движком llama.cpp на процессоре.", fontSize = 13.sp)
                Text("• У приложения нет разрешения INTERNET — Android не даст ему выйти в сеть.", fontSize = 13.sp)
                Text("• История и избранное хранятся во внутренней памяти приложения.", fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
