package com.offlinestudy.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinestudy.ai.data.Subject
import com.offlinestudy.ai.ui.theme.LocalIsDark
import com.offlinestudy.ai.ui.theme.LocalStyle
import com.offlinestudy.ai.ui.theme.Palette
import kotlin.math.sin

/** Фон с мягкими «живыми» цветными пятнами. */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = LocalIsDark.current
    val transition = rememberInfiniteTransition(label = "bg")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    if (LocalStyle.current.minimal) {
        // «Минимал»: чистый чёрный фон без цветных пятен.
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
        return
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.offset(x = (60 + 60 * t).dp, y = (-40 + 50 * t).dp).size(320.dp).blur(90.dp)
                .background(Palette.Violet.copy(alpha = if (dark) 0.35f else 0.22f), CircleShape)
        )
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = (-80 + 40 * t).dp, y = (60 - 70 * t).dp).size(300.dp).blur(90.dp)
                .background(Palette.Teal.copy(alpha = if (dark) 0.28f else 0.18f), CircleShape)
        )
        content()
    }
}

/** «Стеклянная» карточка. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = LocalIsDark.current
    val minimal = LocalStyle.current.minimal
    val shape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var m = modifier.fillMaxWidth().scale(if (pressed) 0.97f else 1f).clip(shape)
        .background(if (minimal) Color(0xFF0E0E0E) else if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.72f))
        .border(
            1.dp,
            if (minimal) SolidColor(Color.White.copy(alpha = 0.12f))
            else Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.25f else 0.9f), Color.White.copy(alpha = 0.04f))),
            shape
        )
    if (onClick != null) m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, leading: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().scale(if (pressed) 0.97f else 1f).clip(RoundedCornerShape(20.dp))
            .background(if (enabled) LocalStyle.current.brand else Brush.linearGradient(listOf(Color.Gray, Color.Gray)))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) { Text(leading); Spacer(Modifier.width(8.dp)) }
        Text(text, color = LocalStyle.current.onBrand, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, leading: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = LocalIsDark.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier.scale(if (pressed) 0.97f else 1f).clip(RoundedCornerShape(18.dp))
            .background(if (LocalStyle.current.minimal) Color(0xFF151515) else if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = if (LocalStyle.current.minimal) 0.14f else if (dark) 0.18f else 0.9f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) { Text(leading, fontSize = 15.sp); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
            color = if (enabled) MaterialTheme.colorScheme.onBackground else Color.Gray)
    }
}

@Composable
fun SubjectBadge(subject: Subject) {
    Row(
        Modifier.clip(CircleShape).background(subject.tint.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(subject.emoji, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text(subject.title, color = subject.tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(1f, 2.2f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "p")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(9.dp).scale(pulse).clip(CircleShape).background(color.copy(alpha = (2.2f - pulse) / 2.4f)))
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "ph")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val v = ((sin(phase - i * 0.9) + 1) / 2).toFloat()
            Box(Modifier.padding(end = 6.dp).size(8.dp).scale(0.7f + 0.4f * v).clip(CircleShape).background(LocalStyle.current.brand))
        }
        Text("Думаю…", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(title, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun StatusRow(ok: Boolean, title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (ok) Palette.Green else Palette.Red),
            contentAlignment = Alignment.Center
        ) { Text(if (ok) "✓" else "✕", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(horizontal = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

/** Простая раскладка «в строку с переносом». */
@Composable
fun FlowRowSimple(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val maxWidth = constraints.maxWidth
        var x = 0; var y = 0; var rowHeight = 0
        val positions = placeables.map { p ->
            if (x > 0 && x + p.width > maxWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            val pos = x to y
            x += p.width + gap
            rowHeight = maxOf(rowHeight, p.height)
            pos
        }
        layout(maxWidth, y + rowHeight) {
            placeables.forEachIndexed { i, p -> p.place(positions[i].first, positions[i].second) }
        }
    }
}

@Suppress("unused")
@Composable
fun RowScope.Weighted(content: @Composable () -> Unit) = Box(Modifier.weight(1f)) { content() }
