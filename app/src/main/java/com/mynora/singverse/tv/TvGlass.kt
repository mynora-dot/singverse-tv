package com.mynora.singverse.tv

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * OS-tiered surface strategy (single minSdk 21 APK):
 * - LIQUID  (Android 13+ / API 33+): Restrained hardware backdrop frost on top/bottom chrome
 * - FROSTED (Android 12  / API 31–32): Lightweight RenderEffect backdrop frost
 * - LEGACY  (Android 5–11 / API 21–30): Zero-blur matte graphite surface for 60fps stability
 */
internal enum class GlassTier {
    LIQUID,
    FROSTED,
    LEGACY;

    val supportsBackdropBlur: Boolean get() = this != LEGACY

    companion object {
        val current: GlassTier = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> LIQUID
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> FROSTED
            else -> LEGACY
        }
    }
}

internal val LocalTvHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Registers a background layer as a Haze backdrop source on Android 12+.
 * Never attach to Media3 PlayerView so video surfaces remain completely independent.
 */
internal fun Modifier.tvHazeSource(state: HazeState?): Modifier =
    if (GlassTier.current.supportsBackdropBlur && state != null) this.hazeSource(state) else this

/**
 * SingVerse TV · Luxury Hi-Fi Obsidian & Champagne Gold Design System.
 *
 * Inspired by Bang & Olufsen anodized hardware and Apple Music's quiet content-first
 * showcase: obsidian black & deep graphite space, restrained champagne gold accents,
 * and unified matte-graphite surfaces. Less effects, more quality.
 */
internal object Glass {
    // Anodized Platinum & Frost Silver accent (crisp Apple TV / Hi-Fi monochrome — zero yellow/brown cast)
    val champagneGold = Color(0xFFDCE4F0)
    val champagneBright = Color(0xFFF4F7FC)
    val champagneSoft = Color(0x18FFFFFF)
    val champagneRim = Color(0xB8FFFFFF)

    // Apple TV Illuminated Focus Surface (luminous platinum-white pill + deep obsidian ink)
    val focusFill = Color(0xFFF3F6FA)
    val focusFillBottom = Color(0xFFE4E9F2)
    val focusInk = Color(0xFF0C0F14)
    val focusInkMuted = Color(0xFF4B5260)

    // Refined crimson-coral for playback progress & primary play CTA
    val brand = Color(0xFFE85668)
    val brandVivid = Color(0xFFE85668)
    val brandCoral = Color(0xFFF27382)
    val brandSoft = Color(0x24E85668)
    val brandFocus = Color(0xFFD94456)
    val onBrand = Color(0xFFFFFFFF)

    val brandBlue = Color(0xFF6BA6E8)
    val cyan = Color(0xFF6BA6E8)
    val amber = Color(0xFFDCE4F0)
    val violet = Color(0xFFA08BE0)
    val champagne = Color(0xFF8C919C)

    // Obsidian black & deep graphite gray spatial palette (zero brown/olive cast)
    val canvas = Color(0xFF0A0B0E)
    val canvasCenter = Color(0xFF13151B)
    val canvasWarm = Color(0xFF0C0D11)
    val canvasGlacier = Color(0xFF090A0D)
    val canvasDeep = Color(0xFF050608)
    val canvasElevated = Color(0xFF161920)
    val inkPrimary = Color(0xFFF3F4F6)
    val inkSecondary = Color(0xFF989DA8)
    val inkTertiary = Color(0xFF6B707B)

    // Unified matte-graphite & subtle smoked surfaces
    val surface = Color(0xCC14171D)
    val surfaceStrong = Color(0xDC1A1E26)
    val surfaceHover = Color(0xE6212630)
    val surfaceFloat = Color(0xCC15181F)    // Top nav, bottom bar, QR panel, search pill
    val surfaceCard = Color(0xC413161C)     // Matte graphite cards & list rows
    val cardSurface = Color(0xCC15181F)
    val surfaceFocus = Color(0xEB222731)    // Elevated focused graphite
    val surfaceSelected = Color(0x1CFFFFFF) // Crisp frosted platinum active tint
    val surfaceSheet = Color(0xEB15181F)    // Toast / sheet
    val surfaceDialog = Color(0xF514171E)   // Solid-feeling dark graphite modal
    val mediaSafe = Color(0xD40B0D12)       // Independent dark scrim over MV video
    val mediaScrim = Color(0xDC08090C)      // Subtitle pill over MV video

    // Subtle metallic hairlines & crisp pure-white specular focus ring
    val rimLight = Color(0x1EFFFFFF)
    val rimCool = Color(0x10FFFFFF)
    val focusRing = Color(0xF5FFFFFF)       // Crisp frosted-white specular edge
    val edgeBottom = Color(0x12FFFFFF)
    val shadowAmbient = Color(0x52000000)
    val shadowSpot = Color(0x78000000)
    val shadowFocus = Color(0x88000000)

    val ambientTop = Color(0xFF14161C)
    val ambientBottom = Color(0xFF08090C)
}

internal object GlassShape {
    val pill = RoundedCornerShape(percent = 50)
    val control = RoundedCornerShape(12.dp)
    val row = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(20.dp)
    val dialog = RoundedCornerShape(24.dp)
}

internal object TvMotion {
    val appleEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val focusSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 460f)
    val subtleSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 460f)
    val dialogSpring = spring<Float>(dampingRatio = 0.84f, stiffness = 380f)
    const val pageTransitionMs = 240
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
 * Unified Hi-Fi Matte-Graphite & Restrained Frosted Surface:
 * - Clean deep-graphite body with subtle top-edge anodized catchlight and 0.75dp low-opacity rim.
 * - Optional restrained Haze frost (`backdropBlur = true`) on floating top/bottom bars on Android 12+.
 * - Zero heavy blur, zero loud borders, zero plastic glare.
 */
internal fun Modifier.glassSurface(
    shape: Shape,
    highlighted: Boolean = false,
    intensity: Float = 1f,
    base: Color? = null,
    borderWidth: Dp = 0.75.dp,
    tintAccent: Color? = null,
    elevation: Dp = 0.dp,
    backdropBlur: Boolean = false,
): Modifier = composed {
    val tier = GlassTier.current
    val hazeState = if (backdropBlur && tier.supportsBackdropBlur) LocalTvHazeState.current else null
    val useRealBlur = hazeState != null
    val c = intensity.coerceIn(0f, 1f)
    val clampedElevation = elevation.coerceAtMost(10.dp)

    val rawBase = base ?: Glass.surfaceFloat
    val elevated = if (clampedElevation > 0.dp) {
        this.shadow(
            elevation = clampedElevation,
            shape = shape,
            ambientColor = Glass.shadowAmbient,
            spotColor = Glass.shadowSpot,
            clip = false,
        )
    } else this

    val withBackdrop = if (useRealBlur) {
        val blurRadius = if (tier == GlassTier.LIQUID) 16.dp else 12.dp
        val style = remember(tier, rawBase, highlighted) {
            HazeStyle(
                backgroundColor = Glass.canvas,
                tint = HazeTint(rawBase.copy(alpha = (rawBase.alpha * 0.88f).coerceIn(0.68f, 0.90f))),
                blurRadius = blurRadius,
                noiseFactor = 0.03f,
            )
        }
        elevated.clip(shape).hazeEffect(state = hazeState, style = style)
    } else elevated

    withBackdrop.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = outline.toPath()

        val bodyAlpha = if (tier == GlassTier.LEGACY) (rawBase.alpha + 0.08f).coerceAtMost(0.96f) else rawBase.alpha
        val body = rawBase.copy(alpha = (bodyAlpha * (0.78f + 0.22f * c)).coerceIn(0f, 1f))

        // Subtle vertical matte-graphite sheen (anodized aluminum feel, never glossy plastic)
        val topSheen = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = (if (highlighted) 0.075f else 0.045f) * c),
                0.22f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.08f * c),
            ),
            endY = size.height.coerceAtLeast(1f),
        )

        // Delicate low-opacity hairline rim (crisp silver sheen when highlighted)
        val rimTopColor = if (highlighted) Color.White.copy(alpha = 0.28f * c) else Color.White.copy(alpha = 0.14f * c)
        val rimBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to rimTopColor,
                0.5f to Color.White.copy(alpha = 0.05f * c),
                1f to Color.White.copy(alpha = (if (highlighted) 0.14f else 0.08f) * c),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )

        val accentBrush = tintAccent?.let { accent ->
            Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = (accent.alpha * c).coerceIn(0f, 1f)),
                    accent.copy(alpha = (accent.alpha * 0.35f * c).coerceIn(0f, 1f)),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        }
        val strokePx = borderWidth.toPx()

        onDrawBehind {
            clipPath(clip) {
                if (!useRealBlur) drawOutline(outline, body)
                if (accentBrush != null) drawOutline(outline, accentBrush)
                drawOutline(outline, topSheen)
            }
            if (strokePx > 0f) {
                drawOutline(outline, rimBrush, style = Stroke(width = strokePx))
            }
        }
    }
}

/** Refined crimson-coral primary CTA fill. */
internal fun Modifier.brandGlassSurface(
    shape: Shape,
    focused: Boolean = false,
): Modifier = shadow(
    elevation = if (focused) 8.dp else 3.dp,
    shape = shape,
    ambientColor = Glass.brand.copy(alpha = 0.24f),
    spotColor = Glass.brand.copy(alpha = 0.34f),
    clip = false,
).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val start = if (focused) Glass.brandFocus else Glass.brandVivid
    val end = if (focused) Color(0xFFF06A7A) else Glass.brandCoral
    val fillBrush = Brush.linearGradient(
        colors = listOf(start, end),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val topSheen = Brush.verticalGradient(
        colorStops = arrayOf(0f to Color(0x22FFFFFF), 0.28f to Color.Transparent),
        endY = size.height.coerceAtLeast(1f),
    )
    val rimBrush = Brush.linearGradient(
        colorStops = arrayOf(0f to Color(0x42FFFFFF), 0.5f to Color(0x10FFFFFF), 1f to Color(0x28FFFFFF)),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val strokePx = 0.75.dp.toPx()
    onDrawBehind {
        drawOutline(outline, fillBrush)
        drawOutline(outline, topSheen)
        drawOutline(outline, rimBrush, style = Stroke(width = strokePx))
    }
}

/** Smooth Apple TV / Hi-Fi focus scale + lift on GPU graphicsLayer (zero layout jitter). */
internal fun Modifier.tvSpringFocus(
    focused: Boolean,
    scaleTarget: Float = 1.04f,
    liftDp: Dp = 1.5.dp,
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

/** Single-composable entrance (avoids dual-composition focus races). */
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

// One-time cached 64x64 fine monochrome dither grain to eliminate 8-bit gradient banding on TVs
private val subtleGrainTile: ImageBitmap by lazy {
    val size = 64
    val pixels = IntArray(size * size)
    var seed = 0x51A7F31D
    for (i in pixels.indices) {
        seed = seed * 1664525 + 1013904223
        val v = (seed ushr 24) and 0xFF
        // Ultra-subtle ~2.5% alpha monochrome micro-grain
        val alpha = if (v > 128) 6 else 3
        val gray = if (v > 128) 255 else 0
        pixels[i] = (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// Low-saturation dark ambient tint helpers (used for AudioPlayerScene cover mood only)
private fun darkAmbient(hue: Float, sat: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0.20f, 0.38f), value.coerceIn(0.20f, 0.34f))))

private val ambientCache = object : LinkedHashMap<String, Pair<Color, Color>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Color, Color>>?) = size > 48
}

/** Cached cover color extraction: sampling runs once per cover key. */
internal fun cachedAmbientColors(key: String, bitmap: Bitmap?): Pair<Color, Color>? {
    if (key.isNotBlank()) synchronized(ambientCache) { ambientCache[key]?.let { return it } }
    val extracted = extractAmbientColors(bitmap) ?: return null
    if (key.isNotBlank()) synchronized(ambientCache) { ambientCache[key] = extracted }
    return extracted
}

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
            if (hsv[2] > 0.22f && score > maxSat) { maxSat = score; vibrant = pixel }
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return null
    val avg = android.graphics.Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    val primaryInt = if (maxSat > 0.18f) vibrant else avg
    android.graphics.Color.colorToHSV(primaryInt, hsv)
    return darkAmbient(hsv[0], hsv[1], 0.32f) to darkAmbient((hsv[0] + 36f) % 360f, hsv[1] * 0.75f, 0.26f)
}

internal fun fallbackAmbientColors(seed: String): Pair<Color, Color> {
    val hues = listOf(220f to 240f, 210f to 235f, 345f to 220f, 195f to 225f, 255f to 220f, 38f to 220f)
    if (seed.isBlank()) return darkAmbient(220f, 0.22f, 0.26f) to darkAmbient(235f, 0.20f, 0.22f)
    val (a, b) = hues[(seed.hashCode() ushr 1) % hues.size]
    return darkAmbient(a, 0.22f, 0.26f) to darkAmbient(b, 0.20f, 0.22f)
}

/**
 * Luxury Hi-Fi Obsidian & Deep Graphite Spatial Backdrop:
 * - Layered low-contrast obsidian/graphite radial gradients for architectural depth (zero brown/olive wash)
 * - Whisper of champagne-gold studio ambient light near upper-center
 * - Soft perimeter vignette framing the center album artwork
 * - Cached fine micro-grain tile preventing 8-bit gradient banding on 1080p/4K TV panels
 */
@Composable
internal fun AmbientStage(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    primaryGlow: Color? = null,
    secondaryGlow: Color? = null,
    animateDrift: Boolean = false,
    backdropBitmap: Bitmap? = null,
) {
    val targetPrimary = primaryGlow ?: Color(0xFF1E222B)
    val targetSecondary = secondaryGlow ?: Color(0xFF151820)
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
            animationSpec = infiniteRepeatable(tween(36000, easing = LinearEasing), RepeatMode.Restart),
            label = "ambientPhase",
        ).value
    } else 0f
    val tier = GlassTier.current
    val grainBrush = remember { ShaderBrush(ImageShader(subtleGrainTile, TileMode.Repeated, TileMode.Repeated)) }

    Box(modifier.fillMaxSize().background(Glass.canvas)) {
        // Optional subtle cover-art mood ONLY when explicitly provided (e.g. full-screen Audio Player)
        if (backdropBitmap != null) {
            val imageModifier = if (tier.supportsBackdropBlur) {
                Modifier.fillMaxSize().blur(52.dp)
            } else {
                Modifier.fillMaxSize()
            }
            Image(
                bitmap = backdropBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.28f,
                modifier = imageModifier,
            )
            Box(Modifier.fillMaxSize().background(Color(0xC20A0B0E)))
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dx = if (animateDrift) cos(phase) * w * 0.018f else 0f
            val dy = if (animateDrift) sin(phase) * h * 0.018f else 0f

            // 1) Central deep-graphite architectural showcase glow (creates spatial depth on obsidian)
            val centerLight = Offset(w * 0.44f + dx, h * 0.26f + dy)
            val centerRadius = maxOf(w, h) * 0.68f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to (if (accent) animatedPrimary.copy(alpha = 0.48f) else Color(0xFF191D26).copy(alpha = 0.68f)),
                        0.55f to (if (accent) animatedSecondary.copy(alpha = 0.22f) else Color(0xFF111319).copy(alpha = 0.35f)),
                        1f to Color.Transparent,
                    ),
                    center = centerLight,
                    radius = centerRadius,
                ),
                radius = centerRadius,
                center = centerLight,
            )

            // 2) Whisper of cool starlight studio ambient light (crisp neutral depth)
            val lightPos = Offset(w * 0.24f - dx, h * 0.08f)
            val lightRadius = h * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFAEC5E5).copy(alpha = if (accent) 0.055f else 0.036f),
                        0.48f to Color(0xFFAEC5E5).copy(alpha = if (accent) 0.018f else 0.012f),
                        1f to Color.Transparent,
                    ),
                    center = lightPos,
                    radius = lightRadius,
                ),
                radius = lightRadius,
                center = lightPos,
            )

            // 3) Cool slate secondary depth on right (keeps QR & right column crisp, zero brown tint)
            val rightPos = Offset(w * 0.88f + dx, h * 0.32f)
            val rightRadius = h * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1A202C).copy(alpha = 0.42f), Color.Transparent),
                    center = rightPos,
                    radius = rightRadius,
                ),
                radius = rightRadius,
                center = rightPos,
            )

            // 4) Perimeter vignette darkening edges & corners to frame the album covers
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.42f to Color.Transparent,
                        0.82f to Color(0x52050608),
                        1f to Color(0xA6050608),
                    ),
                    center = Offset(w * 0.50f, h * 0.48f),
                    radius = maxOf(w, h) * 0.72f,
                )
            )

            // 5) Ultra-fine cached micro-grain to prevent 8-bit gradient banding on TV screens
            drawRect(brush = grainBrush)
        }
    }
}
