package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SingVerse TV · Apple Liquid Glass (Light Edition · V2) Design System.
 *
 * Airy, translucent optical glass with crisp hairline highlights, calm warm-white to
 * glacier-blue backdrop, rationalized corner radii, and unmistakable TV focus separation.
 * Compatible from minSdk 21 to API 36 at 60fps without sampling Media3 video surfaces.
 */
internal object Glass {
    // Primary coral accent & Apple brand semantics (V2 tokens)
    val brand = Color(0xFFFF566B)
    val brandVivid = Color(0xFFFF566B)
    val brandCoral = Color(0xFFFF7A8A)
    val brandSoft = Color(0x18FF566B)
    val brandFocus = Color(0xFFF04359)
    val onBrand = Color(0xFFFFFFFF)

    val brandBlue = Color(0xFF1688F8)   // Apple focus ring & brand blue
    val cyan = Color(0xFF1688F8)        // Secondary blue accent
    val amber = Color(0xFFE68A42)       // Warm apricot accent (rank 02)
    val violet = Color(0xFF8B72D6)      // Soft lavender accent
    val champagne = Color(0xFF858891)   // Auxiliary silver (rank 04)

    // Calm pearl-white to glacier-blue canvas & crisp charcoal ink
    val canvas = Color(0xFFF5F6F9)
    val canvasWarm = Color(0xFFF8F5F7)
    val canvasGlacier = Color(0xFFEDF2FA)
    val inkPrimary = Color(0xFF1D1D1F)
    val inkSecondary = Color(0xFF686A73)
    val inkTertiary = Color(0xFF858891)

    // Layered translucent Liquid Glass hierarchy (lighter, crisper, never plastic)
    val surface = Color(0x8CFFFFFF)         // ~55% standard translucent glass
    val surfaceStrong = Color(0xB3FFFFFF)   // ~70% elevated glass
    val surfaceHover = Color(0xCCFFFFFF)    // ~80% hover/active crystal
    val surfaceFloat = Color(0xA6FFFFFF)    // ~65% floating nav / dock / QR pod
    val surfaceCard = Color(0x8FFFFFFF)     // ~56% lightweight card / row glass
    val cardSurface = Color(0x99FFFFFF)     // ~60% entry card base
    val surfaceFocus = Color(0xE8FFFFFF)    // ~91% crisp focused crystal lens
    val surfaceSelected = Color(0x18FF566B) // Soft coral selection tint
    val surfaceSheet = Color(0xE6F8F9FC)    // Toast / sheet high-legibility glass
    val surfaceDialog = Color(0xF0FAFBFD)   // Modal dialog frosted crystal
    val mediaSafe = Color(0xD9F5F7FB)       // Light crystal overlay over MV video
    val mediaScrim = Color(0xB814161F)      // High-contrast subtitle pill over MV video

    // Crisp hairline borders & ultra-soft ambient shadows (no muddy bleed-through)
    val rimLight = Color(0xCCFFFFFF)
    val rimCool = Color(0x1A1D2438)
    val focusRing = Color(0xD91688F8)       // Clear Apple blue TV remote focus ring
    val edgeBottom = Color(0x121D2438)
    val shadowAmbient = Color(0x0E1B2438)
    val shadowSpot = Color(0x161B2438)
    val shadowFocus = Color(0x261688F8)

    // Calm atmospheric stage stops
    val ambientTop = Color(0xFFF8F5F7)
    val ambientBottom = Color(0xFFEDF2FA)
}

internal object GlassShape {
    val pill = RoundedCornerShape(percent = 50)
    val control = RoundedCornerShape(12.dp)
    val row = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(18.dp)
    val panel = RoundedCornerShape(22.dp)
    val dialog = RoundedCornerShape(26.dp)
}

internal object TvMotion {
    val focusSpring = spring<Float>(dampingRatio = 0.80f, stiffness = 440f)
    val subtleSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 480f)
    val dialogSpring = spring<Float>(dampingRatio = 0.84f, stiffness = 380f)
    const val pageTransitionMs = 200
    const val ambientCrossfadeMs = 1100
}

private fun Outline.toPath(): Path = Path().apply {
    when (this@toPath) {
        is Outline.Rectangle -> addRect(rect)
        is Outline.Rounded -> addRoundRect(roundRect)
        is Outline.Generic -> addPath(path)
    }
}

/**
 * Apple Liquid Glass (Light Edition · V2) optical surface:
 * - Translucent white-crystal fill (55%–75%) with subtle top-only natural light reflection
 * - Hairline specular rim (soft white top-left + whisper cool contour)
 * - Restraint on shadows: ultra-soft low-elevation drop shadow only on floating chrome/focus
 *   so cards stay thin, airy, and glassy rather than opaque neumorphic plastic.
 */
internal fun Modifier.glassSurface(
    shape: Shape,
    highlighted: Boolean = false,
    intensity: Float = 1f,
    base: Color? = null,
    borderWidth: Dp = 1.dp,
    tintAccent: Color? = null,
    elevation: Dp = 0.dp,
): Modifier {
    val clampedElevation = elevation.coerceAtMost(8.dp)
    val elevated = if (clampedElevation > 0.dp) {
        this.shadow(
            elevation = clampedElevation,
            shape = shape,
            ambientColor = if (highlighted) Glass.shadowFocus else Glass.shadowAmbient,
            spotColor = if (highlighted) Glass.shadowFocus else Glass.shadowSpot,
            clip = false,
        )
    } else this

    return elevated.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = outline.toPath()
        val c = intensity.coerceIn(0f, 1f)
        val body = (base ?: Glass.surfaceFloat).let { color ->
            color.copy(alpha = (color.alpha * (0.65f + 0.35f * c)).coerceIn(0f, 1f))
        }

        // Delicate top-edge natural skylight reflection only (no muddy bottom inner shade)
        val topReflection = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = (if (highlighted) 0.42f else 0.28f) * c),
                0.28f to Color.Transparent,
            ),
            endY = size.height.coerceAtLeast(1f),
        )

        // Subtle crisp glass hairline border: pure white specular + whisper cool edge
        val rimBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = (if (highlighted) 0.92f else 0.72f) * c),
                0.45f to Glass.rimCool.copy(alpha = (if (highlighted) 0.14f else 0.12f) * c),
                1f to Color.White.copy(alpha = (if (highlighted) 0.78f else 0.56f) * c),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )

        val accentBrush = tintAccent?.let { accent ->
            Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = (accent.alpha * c).coerceIn(0f, 1f)),
                    accent.copy(alpha = (accent.alpha * 0.45f * c).coerceIn(0f, 1f)),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        }
        val strokePx = borderWidth.toPx()

        onDrawBehind {
            clipPath(clip) {
                drawOutline(outline, body)
                if (accentBrush != null) drawOutline(outline, accentBrush)
                drawOutline(outline, topReflection)
            }
            if (strokePx > 0f) {
                drawOutline(outline, rimBrush, style = Stroke(width = strokePx))
            }
        }
    }
}

/**
 * Refined coral-red Liquid Glass primary CTA fill (#FF566B).
 */
internal fun Modifier.brandGlassSurface(
    shape: Shape,
    focused: Boolean = false,
): Modifier = shadow(
    elevation = if (focused) 7.dp else 3.dp,
    shape = shape,
    ambientColor = Glass.brand.copy(alpha = 0.22f),
    spotColor = Glass.brand.copy(alpha = 0.30f),
    clip = false,
).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val start = if (focused) Glass.brandFocus else Glass.brandVivid
    val end = if (focused) Color(0xFFFF6E80) else Glass.brandCoral
    val fillBrush = Brush.linearGradient(
        colors = listOf(start, end),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val topSheen = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color(0x36FFFFFF),
            0.30f to Color.Transparent,
        ),
        endY = size.height.coerceAtLeast(1f),
    )
    val rimBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color(0x88FFFFFF),
            0.5f to Color(0x22FFFFFF),
            1f to Color(0x55FFFFFF),
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val strokePx = 0.9.dp.toPx()
    onDrawBehind {
        drawOutline(outline, fillBrush)
        drawOutline(outline, topSheen)
        drawOutline(outline, rimBrush, style = Stroke(width = strokePx))
    }
}

/**
 * Smooth Apple TV spring elevation applied on GPU graphics layer.
 */
internal fun Modifier.tvSpringFocus(
    focused: Boolean,
    scaleTarget: Float = 1.028f,
    liftDp: Dp = 1.dp,
): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (focused) scaleTarget else 1f,
        animationSpec = TvMotion.focusSpring,
        label = "tvFocusScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = TvMotion.subtleSpring,
        label = "tvFocusLift",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationY = -liftDp.toPx() * lift
    }
}

/**
 * Subtle fluid entrance transition without dual-composition focus conflicts.
 */
internal fun Modifier.tvScreenEnter(key: Any?): Modifier = composed {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(TvMotion.pageTransitionMs, easing = FastOutSlowInEasing),
        )
    }
    graphicsLayer {
        val p = progress.value
        alpha = 0.45f + 0.55f * p
        val s = 0.992f + 0.008f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * 4.dp.toPx()
    }
}

/**
 * Smooth spring bloom entrance for light frosted glass dialogs.
 */
internal fun Modifier.tvDialogEnter(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = TvMotion.dialogSpring)
    }
    graphicsLayer {
        val p = progress.value.coerceIn(0f, 1.05f)
        alpha = p.coerceIn(0f, 1f)
        val s = 0.95f + 0.05f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p.coerceIn(0f, 1f)) * 6.dp.toPx()
    }
}

/**
 * Extract soft pastel ambient tints from album artwork for the Light Edition audio stage.
 */
internal fun extractAmbientColors(bitmap: Bitmap?): Pair<Color, Color>? {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null
    val stepX = (bitmap.width / 8).coerceAtLeast(1)
    val stepY = (bitmap.height / 8).coerceAtLeast(1)
    var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
    var vibrant = 0; var maxSat = -1f
    val hsv = FloatArray(3)
    var y = stepY / 2
    while (y < bitmap.height) {
        var x = stepX / 2
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            rSum += r; gSum += g; bSum += b; count++
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val score = hsv[1] * (0.4f + hsv[2] * 0.6f)
            if (hsv[2] > 0.22f && score > maxSat) {
                maxSat = score; vibrant = pixel
            }
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return null
    val avg = android.graphics.Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    val primaryInt = if (maxSat > 0.18f) vibrant else avg
    android.graphics.Color.colorToHSV(primaryInt, hsv)
    val pastel1 = floatArrayOf(hsv[0], hsv[1].coerceIn(0.12f, 0.26f), 0.96f)
    val pastel2 = floatArrayOf((hsv[0] + 38f) % 360f, (hsv[1] * 0.65f).coerceIn(0.08f, 0.20f), 0.96f)
    return Color(android.graphics.Color.HSVToColor(pastel1)) to Color(android.graphics.Color.HSVToColor(pastel2))
}

internal fun fallbackAmbientColors(seed: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFFFAD8DF) to Color(0xFFDCE8FA),
        Color(0xFFD9E7FB) to Color(0xFFE7E1F9),
        Color(0xFFFCE3D6) to Color(0xFFF9DCE5),
        Color(0xFFD7F0E9) to Color(0xFFDCE8FA),
        Color(0xFFEBDDFB) to Color(0xFFFADCE7),
        Color(0xFFF8E9D5) to Color(0xFFE4ECF9),
    )
    if (seed.isBlank()) return Color(0xFFFAD8DF) to Color(0xFFDCE8FA)
    return palettes[(seed.hashCode() ushr 1) % palettes.size]
}

/**
 * Calm, serene Apple Liquid Glass atmospheric backdrop:
 * Gentle warm-white on the left transitioning to subtle glacier-blue on the right,
 * with ultra-soft ambient light blooms and zero distracting curves crossing text.
 */
@Composable
internal fun AmbientStage(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    primaryGlow: Color? = null,
    secondaryGlow: Color? = null,
    animateDrift: Boolean = false,
) {
    val targetPrimary = primaryGlow ?: if (accent) Color(0xFFF9D8E0) else Color(0xFFFBE7EC)
    val targetSecondary = secondaryGlow ?: if (accent) Color(0xFFD7E6FB) else Color(0xFFDCE8FA)
    val animatedPrimary by animateColorAsState(
        targetValue = targetPrimary,
        animationSpec = tween(TvMotion.ambientCrossfadeMs, easing = FastOutSlowInEasing),
        label = "ambientPrimary",
    )
    val animatedSecondary by animateColorAsState(
        targetValue = targetSecondary,
        animationSpec = tween(TvMotion.ambientCrossfadeMs, easing = FastOutSlowInEasing),
        label = "ambientSecondary",
    )
    val phase = if (animateDrift) {
        val transition = rememberInfiniteTransition(label = "ambientDrift")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(28000, easing = LinearEasing), RepeatMode.Restart),
            label = "ambientPhase",
        ).value
    } else 0f

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Glass.canvasWarm, Glass.canvas, Glass.canvasGlacier),
                    start = Offset.Zero,
                    end = Offset(1600f, 900f),
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dx1 = if (animateDrift) cos(phase) * w * 0.025f else 0f
            val dy1 = if (animateDrift) sin(phase) * h * 0.025f else 0f
            val dx2 = if (animateDrift) sin(phase * 0.8f) * w * 0.02f else 0f
            val dy2 = if (animateDrift) cos(phase * 0.8f) * h * 0.02f else 0f

            // Quiet warm-blush light diffusion on the upper-left
            val c1 = Offset(w * 0.14f + dx1, h * 0.18f + dy1)
            val r1 = h * 0.95f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedPrimary.copy(alpha = if (accent) 0.44f else 0.28f), Color.Transparent),
                    center = c1,
                    radius = r1,
                ),
                radius = r1,
                center = c1,
            )

            // Quiet glacier-blue light diffusion on the right
            val c2 = Offset(w * 0.88f + dx2, h * 0.28f + dy2)
            val r2 = h * 1.0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedSecondary.copy(alpha = if (accent) 0.48f else 0.34f), Color.Transparent),
                    center = c2,
                    radius = r2,
                ),
                radius = r2,
                center = c2,
            )

            // Subtle white daylight bloom near center-bottom to give floating glass natural depth
            val c3 = Offset(w * 0.50f, h * 0.92f)
            val r3 = h * 0.60f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x52FFFFFF), Color.Transparent),
                    center = c3,
                    radius = r3,
                ),
                radius = r3,
                center = c3,
            )
        }
    }
}
