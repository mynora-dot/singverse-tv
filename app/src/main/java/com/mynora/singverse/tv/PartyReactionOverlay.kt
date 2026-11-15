package com.mynora.singverse.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
        app.partyReactions.forEach { event -> key(event.cursor) {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(event.cursor) { progress.animateTo(1f, tween(2000)); app.removeReaction(event.cursor) }
            val p = progress.value
            val tone = when(event.kind) { "LIKE" -> Color(0xFFFF5577); "CLAP" -> Color(0xFFFFD171); else -> Color(0xFF66D8FF) }
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (1 - p).coerceIn(0f, 1f) }) {
                val center = Offset(size.width * (.28f + (event.cursor % 3) * .22f), size.height * .52f)
                repeat(18) { i ->
                    val angle = i * (Math.PI * 2 / 18)
                    val radius = size.height * (.06f + p * .5f)
                    val point = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius - p * 65)
                    if (event.kind == "LIKE") {
                        val r = 7f + (i % 3) * 3
                        val heart = Path().apply { moveTo(point.x,point.y+r); cubicTo(point.x-r*2,point.y-r,point.x-r,point.y-r*2,point.x,point.y-r); cubicTo(point.x+r,point.y-r*2,point.x+r*2,point.y-r,point.x,point.y+r); close() }
                        drawPath(heart,tone)
                    } else drawCircle(tone, (4 + i % 5).toFloat(), point)
                }
            }
            Column(Modifier.align(Alignment.Center).graphicsLayer { alpha = if(p < .75f) 1f else (1-p)*4; translationY = -p*70 }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when(event.kind) { "LIKE" -> "好听！"; "CLAP" -> "掌声送给你"; else -> "唱得漂亮！" }, color = tone, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("来自 ${event.ownerName}", color = Color.White, fontSize = 20.sp)
            }
        } }
    }
}
