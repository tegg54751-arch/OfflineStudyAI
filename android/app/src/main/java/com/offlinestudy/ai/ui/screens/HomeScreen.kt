package com.offlinestudy.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.ai.ModelState
import com.offlinestudy.ai.data.ChatMode
import com.offlinestudy.ai.data.Subject
import com.offlinestudy.ai.ui.AppTab
import com.offlinestudy.ai.ui.Overlay
import com.offlinestudy.ai.ui.PhotoSource
import com.offlinestudy.ai.ui.components.AppBackground
import com.offlinestudy.ai.ui.components.FlowRowSimple
import com.offlinestudy.ai.ui.components.GlassButton
import com.offlinestudy.ai.ui.components.GlassCard
import com.offlinestudy.ai.ui.components.PrimaryButton
import com.offlinestudy.ai.ui.components.SectionTitle
import com.offlinestudy.ai.ui.components.StatusDot
import com.offlinestudy.ai.ui.theme.LocalIsDark
import com.offlinestudy.ai.ui.theme.LocalStyle
import com.offlinestudy.ai.ui.theme.Palette
import com.offlinestudy.ai.util.Format

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val session = app.session
    val router = app.router
    val ai = app.ai
    val models = app.models

    fun openChat(mode: ChatMode, prefill: String = "") {
        if (!session.isGenerating) session.newChat(mode, prefill)
        router.tab = AppTab.CHAT
    }

    AppBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // Заголовок
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Index AI",
                    style = TextStyle(brush = LocalStyle.current.brand, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                )
                Row(
                    Modifier.clip(CircleShape).background(if (LocalIsDark.current) Color.White.copy(0.08f) else Color.White.copy(0.8f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(Palette.Green)
                    Spacer(Modifier.width(8.dp))
                    Text("Работает полностью офлайн", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }

            // Статус модели
            GlassCard(corner = 24.dp, padding = 14.dp, onClick = { router.overlay = Overlay.MODEL }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(ai.state.color),
                        contentAlignment = Alignment.Center
                    ) { Text("🧠", fontSize = 22.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(models.activeModel?.displayName ?: "AI-модель не установлена", fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(ai.state.color))
                            Spacer(Modifier.width(6.dp))
                            Text(ai.loadingDetail ?: ai.state.title, fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                    if (ai.state == ModelState.Loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("›", fontSize = 24.sp, color = Color.Gray)
                }
            }

            // Единый раздел для всех предметов
            GlassCard(corner = 30.dp, padding = 20.dp) {
                Text("Один помощник — все предметы", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Спросите что угодно: предмет определится автоматически.", fontSize = 14.sp, color = Color.Gray)
                Spacer(Modifier.height(14.dp))
                FlowRowSimple(spacing = 8.dp) {
                    Subject.entries.forEach { subject ->
                        Row(
                            Modifier.clip(CircleShape).background(subject.tint.copy(alpha = 0.14f))
                                .clickable { openChat(ChatMode.ASK, subject.sampleQuestion) }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(subject.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(subject.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Задать вопрос", leading = "💬") { openChat(ChatMode.ASK) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Решить задание", Modifier.weight(1f), leading = "✅") { openChat(ChatMode.SOLVE) }
                    GlassButton("Фото задания", Modifier.weight(1f), leading = "📷") {
                        openChat(ChatMode.SOLVE)
                        router.pendingPhoto = PhotoSource.CAMERA
                    }
                }
            }

            TelegramBanner()

            // Недавние
            val recent = app.history.conversations.take(3)
            if (recent.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Недавние", Modifier.weight(1f))
                    Text("Все", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { router.tab = AppTab.HISTORY }.padding(8.dp))
                }
                recent.forEach { c ->
                    GlassCard(corner = 20.dp, padding = 12.dp, onClick = { session.open(c); router.tab = AppTab.CHAT }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.subject.tint.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) { Text(c.subject.emoji, fontSize = 20.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.firstQuestion, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Format.date(c.updatedAt), fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val TELEGRAM_URL = "https://t.me/IndexAIChannel"

/** Плашка Telegram-канала: открывает канал в Telegram (или в браузере, если Telegram не установлен). */
@Composable
private fun TelegramBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "tgScale")
    val tgDark = Color(0xFF1E88D1)
    val tgLight = Color(0xFF2AABEE)
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(14.dp, RoundedCornerShape(24.dp), ambientColor = tgLight, spotColor = tgLight)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(tgLight, tgDark)))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TELEGRAM_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    ) {
        // Большой полупрозрачный самолётик на фоне
        Icon(
            Icons.AutoMirrored.Filled.Send, contentDescription = null,
            tint = Color.White.copy(alpha = 0.10f),
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 18.dp, y = 10.dp).size(96.dp).rotate(-25f)
        )
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = tgDark,
                    modifier = Modifier.size(24.dp).offset(x = 1.dp, y = (-1).dp).rotate(-25f)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Наш Telegram-канал", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Новости, обновления и новые модели", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) { Text("↗", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}
