package com.offlinestudy.ai.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.ai.ModelState
import com.offlinestudy.ai.data.ChatMessage
import com.offlinestudy.ai.data.ChatMode
import com.offlinestudy.ai.data.MessageKind
import com.offlinestudy.ai.data.MessageRole
import com.offlinestudy.ai.ocr.OcrLine
import com.offlinestudy.ai.ocr.OcrService
import com.offlinestudy.ai.ui.Overlay
import com.offlinestudy.ai.ui.PhotoSource
import com.offlinestudy.ai.ui.components.AppBackground
import com.offlinestudy.ai.ui.components.FlowRowSimple
import com.offlinestudy.ai.ui.components.GlassButton
import com.offlinestudy.ai.ui.components.GlassCard
import com.offlinestudy.ai.ui.components.MarkdownText
import com.offlinestudy.ai.ui.components.PrimaryButton
import com.offlinestudy.ai.ui.components.SubjectBadge
import com.offlinestudy.ai.ui.components.TypingIndicator
import com.offlinestudy.ai.ui.theme.LocalIsDark
import com.offlinestudy.ai.ui.theme.LocalStyle
import com.offlinestudy.ai.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** ЕДИНЫЙ раздел для всех предметов: вопросы, решение заданий, фото. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val app = LocalApp.current
    val session = app.session
    val ai = app.ai
    val router = app.router
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var recognizing by remember { mutableStateOf(false) }
    var ocrLines by remember { mutableStateOf<List<OcrLine>>(emptyList()) }
    var ocrText by remember { mutableStateOf("") }
    var showOcrSheet by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun recognize(uri: Uri) {
        scope.launch {
            recognizing = true
            try {
                val lines = app.ocr.recognize(uri)
                ocrLines = lines
                ocrText = OcrService.text(lines)
                showOcrSheet = true
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Не удалось распознать текст", Toast.LENGTH_LONG).show()
            } finally {
                recognizing = false
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) recognize(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) recognize(uri)
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "task_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraUri = uri
        runCatching { takePicture.launch(uri) }.onFailure {
            Toast.makeText(context, "Камера недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    fun openGallery() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    LaunchedEffect(router.pendingPhoto) {
        when (router.pendingPhoto) {
            PhotoSource.CAMERA -> { router.pendingPhoto = null; openCamera() }
            PhotoSource.GALLERY -> { router.pendingPhoto = null; openGallery() }
            null -> Unit
        }
    }

    val messages = session.conversation.messages
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }

    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // Верхняя панель
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (session.conversation.isEmpty) "Спросить" else "Чат",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
                if (!session.conversation.isEmpty) {
                    SubjectBadge(session.conversation.subject)
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Palette.Indigo.copy(alpha = 0.15f))
                        .clickable { session.newChat(session.mode) },
                    contentAlignment = Alignment.Center
                ) { Text("✎", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
            }

            ModeSwitch(session.mode, enabled = !session.isGenerating) { session.mode = it }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (val state = ai.state) {
                    ModelState.NotInstalled -> item { NoModelBanner { router.overlay = Overlay.MODEL } }
                    is ModelState.Failed -> item { ErrorBanner(state.message) { router.overlay = Overlay.MODEL } }
                    else -> Unit
                }
                if (session.conversation.isEmpty) {
                    item { EmptyState(session.mode) { session.input = it } }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isStreaming = message.id == session.streamingMessageId,
                        showFollowUps = message.id == session.lastAssistantMessageId && !session.isGenerating,
                        onFavorite = { session.toggleFavorite(message.id) },
                        onSimpler = { session.explainSimpler() },
                        onExample = { session.giveExample() },
                        onAnswerOnly = { session.answerOnly() }
                    )
                }
                if (ai.state == ModelState.Loading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Загружаю модель в память…", fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }

            InputBar(
                text = session.input,
                onTextChange = { session.input = it },
                mode = session.mode,
                isGenerating = session.isGenerating,
                onSend = { session.send() },
                onStop = { session.stop() },
                onCamera = { openCamera() },
                onGallery = { openGallery() }
            )
        }

        if (recognizing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                GlassCard(Modifier.padding(40.dp), corner = 28.dp, padding = 28.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text("Распознаю текст на фото…", fontWeight = FontWeight.SemiBold)
                        Text("На устройстве, без интернета", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }
    }

    if (showOcrSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showOcrSheet = false }, sheetState = sheetState) {
            OcrSheet(
                lines = ocrLines,
                text = ocrText,
                onToggle = { line ->
                    ocrLines = ocrLines.map { if (it.id == line.id) it.copy(isIncluded = !it.isIncluded) else it }
                    ocrText = OcrService.text(ocrLines)
                },
                onTextChange = { ocrText = it },
                onSolve = {
                    showOcrSheet = false
                    if (ocrText.isNotBlank()) session.solve(ocrText.trim())
                },
                onAsk = {
                    showOcrSheet = false
                    if (ocrText.isNotBlank()) session.ask(ocrText.trim())
                }
            )
        }
    }
}

@Composable
private fun ModeSwitch(mode: ChatMode, enabled: Boolean, onChange: (ChatMode) -> Unit) {
    val dark = LocalIsDark.current
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (dark) Color.White.copy(0.07f) else Color.Black.copy(0.05f)).padding(3.dp)
    ) {
        ChatMode.entries.forEach { m ->
            val selected = m == mode
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (selected) (if (dark) Color.White.copy(0.16f) else Color.White) else Color.Transparent)
                    .clickable(enabled = enabled) { onChange(m) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(m.title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    mode: ChatMode,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    val dark = LocalIsDark.current
    var menu by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank()
    Row(
        Modifier.fillMaxWidth().background(if (dark) Color(0xCC0E0E1E) else Color(0xCCFFFFFF)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Palette.Indigo.copy(alpha = 0.14f)).clickable { menu = true },
                contentAlignment = Alignment.Center
            ) { Text("📷", fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Сфотографировать задание") }, onClick = { menu = false; onCamera() })
                DropdownMenuItem(text = { Text("Выбрать из галереи") }, onClick = { menu = false; onGallery() })
            }
        }
        Spacer(Modifier.width(8.dp))
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f).heightIn(min = 46.dp, max = 160.dp),
            placeholder = { Text(if (mode == ChatMode.SOLVE) "Введите условие задания…" else "Спросите что угодно…") },
            shape = RoundedCornerShape(22.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = if (dark) Color.White.copy(0.08f) else Color.Black.copy(0.05f),
                unfocusedContainerColor = if (dark) Color.White.copy(0.08f) else Color.Black.copy(0.05f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(46.dp).clip(CircleShape)
                .background(
                    when {
                        isGenerating -> androidx.compose.ui.graphics.SolidColor(Palette.Red)
                        canSend -> LocalStyle.current.brand
                        else -> androidx.compose.ui.graphics.SolidColor(Color.Gray.copy(alpha = 0.4f))
                    }
                )
                .clickable(enabled = isGenerating || canSend) { if (isGenerating) onStop() else onSend() },
            contentAlignment = Alignment.Center
        ) { Text(if (isGenerating) "■" else "↑", color = if (isGenerating) Color.White else LocalStyle.current.onBrand, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
    showFollowUps: Boolean,
    onFavorite: () -> Unit,
    onSimpler: () -> Unit,
    onExample: () -> Unit,
    onAnswerOnly: () -> Unit
) {
    if (message.role == MessageRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.padding(start = 48.dp).clip(RoundedCornerShape(22.dp)).background(LocalStyle.current.brand)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.End
            ) {
                val label = when (message.kind) {
                    MessageKind.SIMPLER -> "✨ Объясни проще"
                    MessageKind.EXAMPLE -> "💡 Дай пример"
                    MessageKind.ANSWER_ONLY -> "✅ Только ответ"
                    else -> null
                }
                if (label != null) {
                    Text(label, color = LocalStyle.current.onBrand, fontWeight = FontWeight.SemiBold)
                } else {
                    if (message.kind == MessageKind.SOLVE) Text("Задание", color = LocalStyle.current.onBrand.copy(0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(message.text, color = LocalStyle.current.onBrand, fontSize = 16.sp)
                }
            }
        }
        return
    }

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    GlassCard(Modifier.padding(end = 12.dp), corner = 24.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(LocalStyle.current.brand), contentAlignment = Alignment.Center) {
                Text("✦", color = LocalStyle.current.onBrand, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("Index AI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
            val speed = message.tokensPerSecond
            if (speed != null && !isStreaming) {
                Text(String.format(Locale.US, " · %.1f ток/с", speed), fontSize = 11.sp, color = Color.Gray.copy(0.7f))
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            message.kind == MessageKind.ERROR -> Text("⚠️ " + message.text, color = Palette.Orange, fontSize = 15.sp)
            message.text.isEmpty() && isStreaming -> {
                val detail = com.offlinestudy.ai.LocalApp.current.ai.loadingDetail
                if (detail != null) Text(detail, fontSize = 13.sp, color = Color.Gray) else TypingIndicator()
            }
            else -> {
                MarkdownText(message.text)
                if (isStreaming) { Spacer(Modifier.height(8.dp)); TypingIndicator() }
            }
        }
        if (!isStreaming && message.kind != MessageKind.ERROR && message.text.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(if (copied) "✓ Скопировано" else "⧉ Копировать", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { clipboard.setText(AnnotatedString(message.text)); copied = true })
                Text(if (message.isFavorite) "★ В избранном" else "☆ В избранное", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (message.isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onFavorite))
            }
            AnimatedVisibility(showFollowUps) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Проще", Modifier.weight(1f), leading = "✨", onClick = onSimpler)
                    GlassButton("Пример", Modifier.weight(1f), leading = "💡", onClick = onExample)
                    GlassButton("Ответ", Modifier.weight(1f), leading = "✅", onClick = onAnswerOnly)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(mode: ChatMode, onSuggestion: (String) -> Unit) {
    val suggestions = if (mode == ChatMode.ASK) listOf(
        "Что такое фотосинтез?", "Почему меняются времена года?", "Чем причастие отличается от деепричастия?",
        "Что такое закон Ома?", "Кто такой Пётр I и чем он известен?"
    ) else listOf(
        "Реши уравнение: 3x + 7 = 22", "Найди площадь треугольника с основанием 10 см и высотой 6 см",
        "Сколько граммов соли в 200 г 15% раствора?", "Поезд проехал 240 км за 3 часа. Найди скорость."
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(LocalStyle.current.brand), contentAlignment = Alignment.Center) {
            Text(if (mode == ChatMode.SOLVE) "✓" else "✦", color = LocalStyle.current.onBrand, fontSize = 36.sp)
        }
        Text(if (mode == ChatMode.SOLVE) "Решение заданий" else "Один чат — все предметы", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            if (mode == ChatMode.SOLVE) "Введите условие или сфотографируйте задание. Можно сразу 20 вопросов — решу каждый."
            else "Предмет определяется автоматически. Можно отправить сразу список из 20 вопросов.",
            fontSize = 14.sp, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        suggestions.forEach { s ->
            GlassCard(corner = 18.dp, padding = 14.dp, onClick = { onSuggestion(s) }) {
                Row { Text(s, Modifier.weight(1f), fontSize = 15.sp); Text("↖", color = Color.Gray) }
            }
        }
    }
}

@Composable
private fun NoModelBanner(onInstall: () -> Unit) {
    GlassCard {
        Text("🧠 Модель не установлена", fontWeight = FontWeight.Bold, color = Palette.Orange, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text("Для работы AI необходимо установить локальную модель.", fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Установить модель", onClick = onInstall)
    }
}

@Composable
private fun ErrorBanner(message: String, onOpen: () -> Unit) {
    GlassCard {
        Text("⚠️ Проблема с моделью", fontWeight = FontWeight.Bold, color = Palette.Orange)
        Spacer(Modifier.height(6.dp))
        Text(message, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text("Открыть настройки модели", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onOpen))
    }
}

@Composable
private fun OcrSheet(
    lines: List<OcrLine>,
    text: String,
    onToggle: (OcrLine) -> Unit,
    onTextChange: (String) -> Unit,
    onSolve: () -> Unit,
    onAsk: () -> Unit
) {
    val hasHandwriting = lines.any { it.isLikelyHandwritten }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Распознанный текст", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (hasHandwriting) "Записи ручкой (✍️) отключены, чтобы не мешать AI. Нажмите на строку, чтобы включить или выключить её."
            else "Нажмите на строку, чтобы исключить её из задания.",
            fontSize = 13.sp, color = Color.Gray
        )
        lines.filter { it.text.isNotBlank() }.forEach { line ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = 0.1f))
                    .clickable { onToggle(line) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (line.isIncluded) "☑" else "☐", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    line.text, Modifier.weight(1f), fontSize = 14.sp,
                    textDecoration = if (line.isIncluded) null else TextDecoration.LineThrough,
                    color = if (line.isIncluded) MaterialTheme.colorScheme.onSurface else Color.Gray,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                if (line.isLikelyHandwritten) Text("✍️")
            }
        }
        Text("Текст для AI (можно исправить):", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = text, onValueChange = onTextChange, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
        Text("ℹ️ Формулы с дробями и корнями распознаются как обычный текст — проверьте их.", fontSize = 12.sp, color = Color.Gray)
        PrimaryButton("Решить задание", leading = "✅", onClick = onSolve)
        GlassButton("Задать как вопрос", Modifier.fillMaxWidth(), leading = "💬", onClick = onAsk)
        Spacer(Modifier.height(24.dp))
    }
}

@Suppress("unused")
@Composable
private fun Unused() = FlowRowSimple { }
