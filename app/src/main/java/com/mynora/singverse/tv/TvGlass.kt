package com.mynora.singverse.tv

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
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
 * OS-tiered glass capability strategy (single minSdk 21 APK):
 * - LIQUID  (Android 13+ / API 33+): Haze GPU backdrop blur + AGSL RuntimeShader SDF bevel refraction,
 *                                    specular crescents & subtle RGB chromatic edge dispersion
 * - FROSTED (Android 12  / API 31–32): Haze RenderEffect GPU backdrop blur + soft specular rim
 * - LEGACY  (Android 5–11 / API 21–30): Zero-blur dark translucent fallback for guaranteed 60fps
 */
internal enum class GlassTier {
    LIQUID,
    FROSTED,
    LEGACY;

    val supportsBackdropBlur: Boolean get() = this != LEGACY
    val supportsAgslLiquid: Boolean get() = this == LIQUID

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
 * AGSL RuntimeShader (Android 13+ / API 33+):
 * Computes exact rounded-box Signed Distance Field (SDF), surface bevel normal,
 * directional top-left/bottom-right studio specular crescents, subtle RGB chromatic
 * dispersion along the curved edge, and internal Fresnel glass lens luminance.
 */
private const val LIQUID_GLASS_AGSL = """
    uniform float2 uSize;
    uniform float uRadius;
    uniform float uHighlight;
    uniform float uIntensity;

    float sdRoundedBox(float2 p, float2 b, float r) {
        float2 q = abs(p) - b + float2(r);
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    }

    half4 main(float2 fragCoord) {
        float2 halfSize = max(uSize * 0.5, float2(1.0));
        float r = clamp(uRadius, 2.0, min(halfSize.x, halfSize.y));
        float2 p = fragCoord - halfSize;

        float d = sdRoundedBox(p, halfSize, r);
        if (d > 0.5) {
            return half4(0.0);
        }

        // Analytical gradient of the SDF gives the curved glass bevel normal
        float eps = 1.0;
        float gx = sdRoundedBox(p + float2(eps, 0.0), halfSize, r) - sdRoundedBox(p - float2(eps, 0.0), halfSize, r);
        float gy = sdRoundedBox(p + float2(0.0, eps), halfSize, r) - sdRoundedBox(p - float2(0.0, eps), halfSize, r);
        float2 n2 = normalize(float2(gx, gy) + float2(1e-5));

        float distIn = max(-d, 0.0);

        // 1) Curved glass bevel band (inner 0..14px) with 3D Fresnel luminance
        float bevelBand = exp(-distIn * 0.22);
        float rimSharp = exp(-distIn * 1.15);

        // 2) Dual directional studio lights (top-left primary + soft bottom-right bounce)
        float2 light1 = normalize(float2(-0.62, -0.78));
        float2 light2 = normalize(float2(0.58, 0.81));
        float spec1 = pow(clamp(dot(n2, light1) * 0.5 + 0.5, 0.0, 1.0), 3.0);
        float spec2 = pow(clamp(dot(n2, light2) * 0.5 + 0.5, 0.0, 1.0), 3.6);

        // 3) Subtle optical RGB chromatic dispersion along the curved bevel
        float dispBand = exp(-abs(distIn - 2.2) * 0.65);
        float3 dispersion = float3(
            0.09 * spec1 * dispBand,
            0.03 * (spec1 + spec2) * dispBand,
            0.11 * spec2 * dispBand
        );

        // 4) Soft top skylight caustic wash across the upper curved glass
        float topWash = clamp(1.0 - (fragCoord.y / max(uSize.y * 0.36, 1.0)), 0.0, 1.0);
        topWash = topWash * topWash * (0.08 + 0.06 * uHighlight);

        // 5) Combine optical specular rim + inner bevel lens + chromatic edge
        float boost = mix(1.0, 1.45, uHighlight) * uIntensity;
        float rimEnergy = (0.34 * spec1 + 0.22 * spec2 + 0.06) * rimSharp * boost;
        float bevelEnergy = (0.14 * spec1 + 0.09 * spec2) * bevelBand * boost;

        float3 rgb = float3(0.95, 0.97, 1.0) * (rimEnergy + bevelEnergy + topWash) + dispersion * boost;
        float alpha = clamp(rimEnergy * 1.15 + bevelEnergy * 0.95 + topWash + length(dispersion) * 0.6, 0.0, 0.62);
        return half4(rgb * alpha, alpha);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildLiquidGlassShaderBrush(
    width: Float,
    height: Float,
    radiusPx: Float,
    highlighted: Boolean,
    intensity: Float,
): ShaderBrush {
    val shader = RuntimeShader(LIQUID_GLASS_AGSL)
    shader.setFloatUniform("uSize", width.coerceAtLeast(2f), height.coerceAtLeast(2f))
    shader.setFloatUniform("uRadius", radiusPx)
    shader.setFloatUniform("uHighlight", if (highlighted) 1f else 0f)
    shader.setFloatUniform("uIntensity", intensity.coerceIn(0f, 1f))
    return ShaderBrush(shader)
}

/**
 * SingVerse TV · Dark Liquid Glass design system (graphite canvas, tvOS-style focus).
 */
internal object Glass {
    // Coral accent: progress, primary actions, meaningful states
    val brand = Color(0xFFFF566B)
    val brandVivid = Color(0xFFFF566B)
    val brandCoral = Color(0xFFFF7A8A)
    val brandSoft = Color(0x28FF566B)
    val brandFocus = Color(0xFFF04359)
    val onBrand = Color(0xFFFFFFFF)

    val brandBlue = Color(0xFF5EA8FF)
    val cyan = Color(0xFF5EA8FF)
    val amber = Color(0xFFFFA95C)
    val violet = Color(0xFFA78BFA)
    val champagne = Color(0xFF8E95A5)

    // Graphite canvas & ink (readable from sofa distance)
    val canvas = Color(0xFF0B0D11)
    val canvasWarm = Color(0xFF0E1015)
    val canvasGlacier = Color(0xFF0A0C11)
    val canvasDeep = Color(0xFF07080B)
    val canvasElevated = Color(0xFF141820)
    val inkPrimary = Color(0xFFF5F5F7)
    val inkSecondary = Color(0xFFA4AAB8)
    val inkTertiary = Color(0xFF767C8B)

    // Smoke-black Liquid Glass hierarchy (translucent enough to let blurred cover art glow through)
    val surface = Color(0x70161A22)
    val surfaceStrong = Color(0x9E1E232E)
    val surfaceHover = Color(0xB4262C3A)
    val surfaceFloat = Color(0x7A161B24)    // Floating Liquid Glass chrome (real Haze blur underneath)
    val surfaceCard = Color(0x72141820)     // Calm dark card / list row base
    val cardSurface = Color(0x80161A22)
    val surfaceFocus = Color(0xB8262E3D)    // Focused smoked glass
    val surfaceSelected = Color(0x2AFFFFFF) // Active inner lens
    val surfaceSheet = Color(0xDC161A22)    // Toast / sheet
    val surfaceDialog = Color(0xF0151922)   // Thick, readable modal panel
    val mediaSafe = Color(0xC80D1016)       // Independent dark scrim over MV video
    val mediaScrim = Color(0xD40A0C11)      // Subtitle pill over MV video

    // Subtle hairline rims & tvOS silver-white focus ring
    val rimLight = Color(0x2EFFFFFF)
    val rimCool = Color(0x16FFFFFF)
    val focusRing = Color(0xF5F5F7FA)
    val edgeBottom = Color(0x14FFFFFF)
    val shadowAmbient = Color(0x4C000000)
    val shadowSpot = Color(0x6E000000)
    val shadowFocus = Color(0x46FFFFFF)

    val ambientTop = Color(0xFF0E1015)
    val ambientBottom = Color(0xFF0A0C11)
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
    val focusSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 500f)
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

private fun Outline.approxCornerRadiusPx(height: Float): Float = when (this) {
    is Outline.Rounded -> roundRect.topLeftCornerRadius.x.coerceAtLeast(2f)
    is Outline.Rectangle -> 2f
    is Outline.Generic -> (height * 0.5f).coerceAtLeast(2f)
}

/**
 * Tiered Dark Liquid Glass surface:
 * - Android 13+ (`backdropBlur = true`): Haze GPU backdrop blur (26dp) + AGSL RuntimeShader
 *   curved bevel specular highlights & RGB chromatic edge dispersion.
 * - Android 12  (`backdropBlur = true`): Haze RenderEffect GPU backdrop blur (18dp) + crisp rim.
 * - Android 5–11 (or `backdropBlur = false`): Zero-blur dark translucent fallback.
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
    val useAgsl = backdropBlur && tier.supportsAgslLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val c = intensity.coerceIn(0f, 1f)
    val clampedElevation = elevation.coerceAtMost(8.dp)

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
        val blurRadius = if (tier == GlassTier.LIQUID) 26.dp else 18.dp
        val noise = if (tier == GlassTier.LIQUID) 0.06f else 0.04f
        // Keep tint light enough that the blurred cover art & ambient glow visibly refract through
        val blurTintAlpha = (rawBase.alpha * (if (highlighted) 0.62f else 0.50f)).coerceIn(0.22f, 0.64f)
        val style = remember(tier, rawBase, highlighted) {
            HazeStyle(
                backgroundColor = Glass.canvas,
                tint = HazeTint(rawBase.copy(alpha = blurTintAlpha)),
                blurRadius = blurRadius,
                noiseFactor = noise,
            )
        }
        elevated.clip(shape).hazeEffect(state = hazeState, style = style)
    } else elevated

    withBackdrop.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val clip = outline.toPath()

        val fallbackAlpha = if (tier == GlassTier.LEGACY) {
            (rawBase.alpha + 0.16f).coerceAtMost(0.96f)
        } else rawBase.alpha
        val body = rawBase.copy(alpha = (fallbackAlpha * (0.72f + 0.28f * c)).coerceIn(0f, 1f))

        val agslBrush = if (useAgsl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                buildLiquidGlassShaderBrush(
                    width = size.width,
                    height = size.height,
                    radiusPx = outline.approxCornerRadiusPx(size.height),
                    highlighted = highlighted,
                    intensity = c,
                )
            }.getOrNull()
        } else null

        val topReflection = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = (if (highlighted) 0.12f else 0.07f) * c),
                0.26f to Color.Transparent,
            ),
            endY = size.height.coerceAtLeast(1f),
        )

        val rimBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = (if (highlighted) 0.28f else 0.18f) * c),
                0.48f to Color.White.copy(alpha = 0.05f * c),
                1f to Color.White.copy(alpha = (if (highlighted) 0.18f else 0.11f) * c),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )

        val accentBrush = tintAccent?.let { accent ->
            Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = (accent.alpha * c).coerceIn(0f, 1f)),
                    accent.copy(alpha = (accent.alpha * 0.4f * c).coerceIn(0f, 1f)),
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
                if (agslBrush != null) drawOutline(outline, agslBrush)
                else drawOutline(outline, topReflection)
            }
            if (strokePx > 0f && agslBrush == null) {
                drawOutline(outline, rimBrush, style = Stroke(width = strokePx))
            }
        }
    }
}

/** Coral primary action fill. */
internal fun Modifier.brandGlassSurface(
    shape: Shape,
    focused: Boolean = false,
): Modifier = shadow(
    elevation = if (focused) 8.dp else 3.dp,
    shape = shape,
    ambientColor = Glass.brand.copy(alpha = 0.28f),
    spotColor = Glass.brand.copy(alpha = 0.36f),
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
        colorStops = arrayOf(0f to Color(0x28FFFFFF), 0.30f to Color.Transparent),
        endY = size.height.coerceAtLeast(1f),
    )
    val rimBrush = Brush.linearGradient(
        colorStops = arrayOf(0f to Color(0x52FFFFFF), 0.5f to Color(0x14FFFFFF), 1f to Color(0x32FFFFFF)),
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

/** Short, crisp tvOS-style focus scale + lift on the GPU layer. */
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

// Low-saturation dark ambient tint helpers (cover colors tinted down for graphite canvas)
private fun darkAmbient(hue: Float, sat: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0.32f, 0.54f), value.coerceIn(0.28f, 0.48f))))

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
    return darkAmbient(hsv[0], hsv[1], 0.44f) to darkAmbient((hsv[0] + 42f) % 360f, hsv[1] * 0.85f, 0.38f)
}

internal fun fallbackAmbientColors(seed: String): Pair<Color, Color> {
    val hues = listOf(350f to 215f, 215f to 265f, 20f to 335f, 165f to 215f, 270f to 340f, 38f to 215f)
    if (seed.isBlank()) return darkAmbient(350f, 0.38f, 0.40f) to darkAmbient(215f, 0.40f, 0.36f)
    val (a, b) = hues[(seed.hashCode() ushr 1) % hues.size]
    return darkAmbient(a, 0.38f, 0.40f) to darkAmbient(b, 0.40f, 0.36f)
}

/**
 * Graphite stage with live atmospheric cover art backdrop:
 * Gives Haze & AGSL Liquid Glass genuine luminous color depth to refract behind floating bars,
 * while a dark graphite vignette guarantees sofa-distance text contrast.
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
    val targetPrimary = primaryGlow ?: darkAmbient(350f, 0.38f, 0.40f)
    val targetSecondary = secondaryGlow ?: darkAmbient(212f, 0.42f, 0.38f)
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
            animationSpec = infiniteRepeatable(tween(32000, easing = LinearEasing), RepeatMode.Restart),
            label = "ambientPhase",
        ).value
    } else 0f
    val glowAlpha = if (accent) 0.56f else 0.36f
    val tier = GlassTier.current

    Box(modifier.fillMaxSize().background(Glass.canvas)) {
        if (backdropBitmap != null) {
            val imageModifier = if (tier.supportsBackdropBlur) {
                Modifier.fillMaxSize().blur(46.dp)
            } else {
                Modifier.fillMaxSize()
            }
            Image(
                bitmap = backdropBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (accent) 0.44f else 0.32f,
                modifier = imageModifier,
            )
            // Dark graphite veil preserving high text contrast while letting cover color glow through glass
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color(0x9C0B0D11),
                                0.45f to Color(0xB80B0D11),
                                1f to Color(0xD20B0D11),
                            )
                        )
                    )
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dx1 = if (animateDrift) cos(phase) * w * 0.025f else 0f
            val dy1 = if (animateDrift) sin(phase) * h * 0.025f else 0f
            val dx2 = if (animateDrift) sin(phase * 0.8f) * w * 0.025f else 0f
            val dy2 = if (animateDrift) cos(phase * 0.8f) * h * 0.025f else 0f

            val c1 = Offset(w * 0.16f + dx1, h * 0.10f + dy1)
            val r1 = h * 0.90f
            drawCircle(
                brush = Brush.radialGradient(listOf(animatedPrimary.copy(alpha = glowAlpha), Color.Transparent), center = c1, radius = r1),
                radius = r1,
                center = c1,
            )
            val c2 = Offset(w * 0.86f + dx2, h * 0.24f + dy2)
            val r2 = h * 0.92f
            drawCircle(
                brush = Brush.radialGradient(listOf(animatedSecondary.copy(alpha = glowAlpha), Color.Transparent), center = c2, radius = r2),
                radius = r2,
                center = c2,
            )
            val c3 = Offset(w * 0.50f, h * 0.95f)
            val r3 = h * 0.68f
            drawCircle(
                brush = Brush.radialGradient(listOf(animatedSecondary.copy(alpha = glowAlpha * 0.80f), Color.Transparent), center = c3, radius = r3),
                radius = r3,
                center = c3,
            )
        }
    }
}
