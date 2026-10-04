package com.offlinestudy.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.LocalApp
import com.offlinestudy.ai.data.Conversation
import com.offlinestudy.ai.data.FavoriteItem
import com.offlinestudy.ai.data.Subject
import com.offlinestudy.ai.ui.AppTab
import com.offlinestudy.ai.ui.components.AppBackground
import com.offlinestudy.ai.ui.components.GlassCard
import com.offlinestudy.ai.ui.components.MarkdownParser
import com.offlinestudy.ai.ui.components.MarkdownText
import com.offlinestudy.ai.ui.components.SubjectBadge
import com.offlinestudy.ai.ui.theme.LocalIsDark
import com.offlinestudy.ai.ui.theme.LocalStyle
import com.offlinestudy.ai.ui.theme.Palette
import com.offlinestudy.ai.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val app = LocalApp.current
    val history = app.history
    val session = app.session
    val router = app.router
    val dark = LocalIsDark.current

    var favoritesTab by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var subjectFilter by remember { mutableStateOf<Subject?>(null) }
    var selectedFavorite by remember { mutableStateOf<FavoriteItem?>(null) }
    var toDelete by remember { mutableStateOf<Conversation?>(null) }

    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text("История", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 8.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (dark) Color.White.copy(0.07f) else Color.Black.copy(0.05f)).padding(3.dp)
            ) {
                listOf(false to "Все вопросы", true to "Избранное").forEach { (fav, title) ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (favoritesTab == fav) (if (dark) Color.White.copy(0.16f) else Color.White) else Color.Transparent)
                            .clickable { favoritesTab = fav }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(title, fontWeight = if (favoritesTab == fav) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("Поиск по вопросам и ответам") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp)
            )

            if (!favoritesTab) {
                val used = Subject.entries.filter { s -> history.conversations.any { it.subject == s } }
                if (used.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("✨ Все", subjectFilter == null) { subjectFilter = null }
                        used.forEach { s -> Chip("${s.emoji} ${s.title}", subjectFilter == s) { subjectFilter = if (subjectFilter == s) null else s } }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val list = history.conversations.filter { c ->
                    (subjectFilter == null || c.subject == subjectFilter) &&
                        (search.isBlank() || c.messages.any { it.text.contains(search, ignoreCase = true) })
                }
                if (list.isEmpty()) Empty("🕘", "История пуста", "Здесь появятся ваши вопросы и ответы. Всё хранится только на этом телефоне.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { c ->
                        GlassCard(corner = 22.dp, padding = 14.dp, onClick = { session.open(c); router.tab = AppTab.CHAT }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SubjectBadge(c.subject)
                                Spacer(Modifier.weight(1f))
                                Text(Format.date(c.updatedAt), fontSize = 12.sp, color = Color.Gray)
                                Spacer(Modifier.width(10.dp))
                                Text("🗑", modifier = Modifier.clickable { toDelete = c })
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(c.firstQuestion, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (c.firstAnswer.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(MarkdownParser.convertInlineFormulas(c.firstAnswer.replace("**", "")), fontSize = 14.sp,
                                    color = Color.Gray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                val favorites = history.favorites.filter {
                    search.isBlank() || it.question.contains(search, true) || it.answer.text.contains(search, true)
                }
                if (favorites.isEmpty()) Empty("⭐", "Нет избранного", "Нажмите «В избранное» под ответом, чтобы сохранить его здесь.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favorites, key = { it.answer.id }) { f ->
                        GlassCard(corner = 22.dp, padding = 14.dp, onClick = { selectedFavorite = f }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("★ ", color = Color(0xFFFFC107))
                                SubjectBadge(f.subject)
                                Spacer(Modifier.weight(1f))
                                Text("убрать", fontSize = 12.sp, color = Palette.Orange, modifier = Modifier.clickable {
                                    history.setFavorite(false, f.answer.id, f.conversationId)
                                    session.syncFromHistory()
                                })
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(f.question, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(MarkdownParser.convertInlineFormulas(f.answer.text.replace("**", "")), fontSize = 14.sp,
                                color = Color.Gray, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить вопрос?") },
            text = { Text(c.firstQuestion, maxLines = 3) },
            confirmButton = { TextButton({ history.delete(c.id); session.syncFromHistory(); toDelete = null }) { Text("Удалить", color = Palette.Red) } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Отмена") } }
        )
    }

    selectedFavorite?.let { f ->
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { selectedFavorite = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SubjectBadge(f.subject)
                Text(f.question, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                GlassCard { MarkdownText(f.answer.text) }
                TextButton({ clipboard.setText(AnnotatedString(f.answer.text)) }) { Text("⧉ Скопировать ответ") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun Chip(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (selected) LocalStyle.current.brand else androidx.compose.ui.graphics.SolidColor(Color.Gray.copy(alpha = 0.15f)))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (selected) LocalStyle.current.onBrand else Color.Unspecified) }
}

@Composable
private fun Empty(icon: String, title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(icon, fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text, fontSize = 14.sp, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
