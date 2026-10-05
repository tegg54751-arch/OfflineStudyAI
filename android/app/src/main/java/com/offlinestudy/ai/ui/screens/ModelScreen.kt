package com.offlinestudy.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.ai.LocalModel
import com.offlinestudy.ai.ai.ModelState
import com.offlinestudy.ai.ui.components.AppBackground
import com.offlinestudy.ai.ui.components.GlassButton
import com.offlinestudy.ai.ui.components.GlassCard
import com.offlinestudy.ai.ui.components.InfoRow
import com.offlinestudy.ai.ui.components.PrimaryButton
import com.offlinestudy.ai.ui.theme.Palette
import com.offlinestudy.ai.util.DeviceInfo
import com.offlinestudy.ai.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Раздел «AI-модель»: установка, выбор, статус, память. */
@Composable
fun ModelScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val models = app.models
    val ai = app.ai
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableLongStateOf(0L) }
    var toDelete by remember { mutableStateOf<LocalModel?>(null) }

    LaunchedEffect(Unit) {
        models.refresh(); ai.refreshState()
        while (true) { delay(2000); tick++ }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { models.importModel(uri); ai.refreshState() }
    }

    AppBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Назад", color = Palette.Indigo, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp, top = 6.dp, bottom = 6.dp))
                Text("AI-модель", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            val active = models.activeModel
            if (active == null) {
                GlassCard {
                    Text("🧠", fontSize = 34.sp)
                    Text("Модель не установлена", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Для работы AI необходимо установить локальную модель.", fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Приложение не скачивает модель само и не требует интернета. Скачайте файл .gguf (например, в браузере) и импортируйте его кнопкой ниже.",
                        fontSize = 13.sp, color = Color.Gray)
                }
            } else {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Активная модель", fontSize = 12.sp, color = Color.Gray)
                            Text(active.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                        Text(ai.loadingDetail ?: ai.state.title, color = ai.state.color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    InfoRow("Файл", active.fileName)
                    InfoRow("Размер модели", Format.bytes(active.sizeBytes))
                    val info = ai.loadedInfo
                    if (info != null) {
                        InfoRow("Веса в памяти", Format.bytes(info.weightsBytes))
                        InfoRow("Параметров", Format.params(info.parameterCount))
                        InfoRow("Архитектура", info.description)
                        InfoRow("Контекст", "${info.contextSize} из ${info.trainContextSize}")
                        InfoRow("Загрузка заняла", String.format(Locale.US, "%.1f с", info.loadSeconds))
                    } else {
                        InfoRow("Статус загрузки", ai.loadingDetail ?: ai.state.title)
                    }
                    ai.lastStats?.let { InfoRow("Скорость", String.format(Locale.US, "%.1f токенов/с", it.tokensPerSecond)) }
                    InfoRow("Движок", ai.backendName)
                    if (ai.compatibilityLevel > 0) InfoRow("Режим совместимости", "уровень ${ai.compatibilityLevel}")
                    (ai.state as? ModelState.Failed)?.let { Text(it.message, color = Palette.Red, fontSize = 13.sp) }
                    Spacer(Modifier.height(8.dp))
                    if (ai.loadedInfo == null) {
                        GlassButton(if (ai.state == ModelState.Loading) "Загрузка…" else "Загрузить в память", Modifier.fillMaxWidth(),
                            leading = "▶", enabled = ai.state != ModelState.Loading) {
                            scope.launch { runCatching { ai.ensureLoaded() } }
                        }
                    } else {
                        GlassButton("Выгрузить из памяти", Modifier.fillMaxWidth(), leading = "⏏", enabled = !ai.isGenerating) {
                            scope.launch { ai.unload() }
                        }
                    }
                }
            }

            models.importProgress?.let { progress ->
                GlassCard {
                    Text("Импорт: ${models.importFileName ?: ""}", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                }
            }
            models.lastError?.let { Text("⚠️ $it", color = Palette.Orange, fontSize = 13.sp) }

            // Журнал движка: точная причина ошибок — можно скопировать и отправить разработчику.
            var showLog by remember { mutableStateOf(false) }
            GlassButton(if (showLog) "Скрыть журнал движка" else "Журнал движка (для диагностики)", Modifier.fillMaxWidth(), leading = "🧾") { showLog = !showLog }
            if (showLog) {
                val log = remember(tick) {
                    val probes = ai.probeLog.toList().joinToString("\n")
                    ((if (probes.isNotEmpty()) "Проверка при загрузке:\n$probes\n\n" else "") + com.offlinestudy.ai.ai.LlamaBridge.nativeGetLog()).ifBlank { "Журнал пуст." }
                }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                GlassCard {
                    Text("Нажмите, чтобы скопировать", fontSize = 12.sp, color = Palette.Indigo,
                        modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(log)) }.padding(bottom = 6.dp))
                    SelectionContainer { Text(log.takeLast(6000), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            }

            // Память
            androidx.compose.runtime.key(tick) { GlassCard {
                Text("Память устройства", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                InfoRow("Всего RAM", Format.bytes(DeviceInfo.totalRam(context)))
                InfoRow("Свободно RAM", Format.bytes(DeviceInfo.availableRam(context)))
                InfoRow("Использует приложение", Format.bytes(DeviceInfo.appMemory()))
                InfoRow("Свободно на диске", Format.bytes(DeviceInfo.freeDisk(context)))
                active?.let { InfoRow("Нужно для модели (оценка)", Format.bytes(DeviceInfo.estimatedMemory(it.sizeBytes, app.settings.contextSize))) }
                ai.lastMemoryWarning?.let { Text("Система просила освободить память: ${Format.date(it)}", fontSize = 12.sp, color = Palette.Orange) }
            } }

            if (models.models.isNotEmpty()) {
                GlassCard {
                    Text("Установленные модели", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    models.models.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !ai.isGenerating && m.id != active?.id) {
                                models.select(m)
                                scope.launch { ai.unload(); runCatching { ai.ensureLoaded() } }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (m.id == active?.id) "✅" else "⚪", fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(Format.bytes(m.sizeBytes), fontSize = 12.sp, color = Color.Gray)
                            }
                            Text("🗑", modifier = Modifier.clickable(enabled = !ai.isGenerating) { toDelete = m }.padding(8.dp))
                        }
                    }
                }
            }

            PrimaryButton("Импортировать модель (.gguf)", leading = "⬇", enabled = models.importProgress == null) {
                importer.launch(arrayOf("*/*"))
            }

            GlassCard {
                Text("Рекомендуемые модели", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Recommended("Qwen3 1.7B · Q4_0", "≈1.0 ГБ",
                    "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_0.gguf",
                    "Для большинства телефонов (6 ГБ RAM и больше). Формат Q4_0 на Android работает быстрее всего.")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Recommended("Qwen3 4B Instruct 2507 · Q4_0", "≈2.4 ГБ",
                    "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_0.gguf",
                    "Самая точная. Для телефонов с 8 ГБ RAM и больше; отвечает медленнее.")
                Spacer(Modifier.height(6.dp))
                Text("Откройте ссылку в браузере телефона, скачайте файл, затем нажмите «Импортировать модель» и выберите его в «Загрузках».",
                    fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить модель?") },
            text = { Text("${m.fileName} — ${Format.bytes(m.sizeBytes)}") },
            confirmButton = {
                TextButton({
                    scope.launch {
                        if (ai.loadedInfo?.fileName == m.fileName) ai.unload()
                        models.delete(m); ai.refreshState()
                    }
                    toDelete = null
                }) { Text("Удалить", color = Palette.Red) }
            },
            dismissButton = { TextButton({ toDelete = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun Recommended(name: String, size: String, url: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row { Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(size, fontSize = 12.sp, color = Color.Gray) }
        SelectionContainer { Text(url, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.Violet) }
        Text(note, fontSize = 12.sp, color = Color.Gray)
    }
}
