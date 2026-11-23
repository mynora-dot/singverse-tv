package com.mynora.singverse.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp

internal object Glass {
    val surface = Color(0x12FFFFFF)
    val surfaceStrong = Color(0x1EFFFFFF)
    val surfaceHover = Color(0x26FFFFFF)
    val cardSurface = Color(0xFF202026)
    val surfaceFocus = Color(0xFF2C2C2E)
    val surfaceSelected = Color(0xFF263346)
    val surfaceSheet = Color(0xF2121214)
    val surfaceDialog = Color(0xF018181C)
    val edgeBottom = Color(0x08FFFFFF)
    val specular = Color(0x40FFFFFF)
    val specularStrong = Color(0x80FFFFFF)

    val ambientTop = Color(0xFF1B1B22)
    val ambientBottom = Color(0xFF050506)
    val ambientAccentTop = Color(0xFF1F2740)
    val ambientAccentBottom = Color(0xFF09090C)
}

internal object GlassShape {
    val pill = RoundedCornerShape(percent = 50)
    val control = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(22.dp)
    val panel = RoundedCornerShape(28.dp)
    val dialog = RoundedCornerShape(36.dp)
}

/** Paint the tint and top highlight together in a single shaped background. */
internal fun Modifier.glassSurface(
    shape: Shape,
    highlighted: Boolean = false,
    intensity: Float = 1f,
    base: Color? = null,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val tint = if (highlighted) Glass.surfaceHover else Glass.surface
    val highlight = if (highlighted) Glass.specularStrong else Glass.specular
    val fill = tint.copy(alpha = tint.alpha * intensity).compositeOver(base ?: Color.Transparent)
    val top = highlight.copy(alpha = highlight.alpha * intensity).compositeOver(fill)
    val brush = Brush.verticalGradient(
        colors = listOf(top, fill),
        endY = (size.height * .35f).coerceAtLeast(1f),
    )
    onDrawBehind { drawOutline(outline, brush) }
}

@Composable
internal fun AmbientStage(modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (accent) listOf(Glass.ambientAccentTop, Glass.ambientAccentBottom)
                    else listOf(Glass.ambientTop, Glass.ambientBottom),
                    startY = 0f,
                )
            )
    )
}
