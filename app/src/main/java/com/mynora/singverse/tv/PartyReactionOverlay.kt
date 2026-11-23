package com.mynora.singverse.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.cos
import kotlin.math.sin

/** Transparent, silent and unfocusable: celebration never intercepts remote buttons. */
@Composable internal fun PartyReactionOverlay(app: TvApp) {
    if (!app.partyEffectsEnabled) return
    Box(Modifier.fillMaxSize()) {
        app.partyReactions.forEach { event ->
            key(event.cursor) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(event.cursor) {
                    progress.animateTo(1f, tween(2100, easing = FastOutSlowInEasing))
                    app.removeReaction(event.cursor)
                }
                val p = progress.value
                val tone = when (event.kind) {
                    "LIKE" -> Glass.brand
                    "CLAP" -> Glass.amber
                    else -> Glass.cyan
                }
                val secondaryTone = when (event.kind) {
                    "LIKE" -> Glass.brandCoral
                    "CLAP" -> Color(0xFFFF9F0A)
                    else -> Color(0xFF5E5CE6)
                }
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - p).coerceIn(0f, 1f) }) {
                    val center = Offset(size.width * (.30f + (event.cursor % 3) * .20f), size.height * .52f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(tone.copy(alpha = 0.24f), Color.Transparent),
                            center = center,
                            radius = size.height * (0.16f + p * 0.26f),
                        ),
                        radius = size.height * (0.16f + p * 0.26f),
                        center = center,
                    )
                    repeat(18) { i ->
                        val angle = i * (Math.PI * 2 / 18)
                        val radius = size.height * (.07f + p * .46f)
                        val point = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius - p * 68f)
                        if (event.kind == "LIKE") {
                            val r = 6.5f + (i % 3) * 2.8f
                            val heart = Path().apply {
                                moveTo(point.x, point.y + r)
                                cubicTo(point.x - r * 2, point.y - r, point.x - r, point.y - r * 2, point.x, point.y - r)
                                cubicTo(point.x + r, point.y - r * 2, point.x + r * 2, point.y - r, point.x, point.y + r)
                                close()
                            }
                            drawPath(heart, tone)
                        } else {
                            drawCircle(if (i % 2 == 0) tone else secondaryTone, (4 + i % 5).toFloat(), point)
                        }
                    }
                }
                val scale = if (p < 0.18f) 0.78f + (p / 0.18f) * 0.26f else 1.04f - ((p - 0.18f) * 0.08f)
                val badgeAlpha = when {
                    p < 0.14f -> (p / 0.14f).coerceIn(0f, 1f)
                    p > 0.74f -> ((1f - p) / 0.26f).coerceIn(0f, 1f)
                    else -> 1f
                }
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            alpha = badgeAlpha
                            scaleX = scale
                            scaleY = scale
                            translationY = -p * 74f
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .background(Brush.linearGradient(listOf(tone.copy(alpha = 0.86f), secondaryTone.copy(alpha = 0.84f))), GlassShape.pill)
                            .border(1.25.dp, Color(0x88FFFFFF), GlassShape.pill)
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                    ) {
                        Text(
                            when (event.kind) {
                                "LIKE" -> "好听！"
                                "CLAP" -> "掌声送给你"
                                else -> "唱得漂亮！"
                            },
                            color = OnBrand,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "来自 ${event.ownerName}",
                        color = Paper.copy(alpha = 0.92f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .glassSurface(GlassShape.pill, base = Glass.surfaceSheet, elevation = 8.dp)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
