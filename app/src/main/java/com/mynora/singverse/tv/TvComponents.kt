package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Ink = Glass.canvas
internal val Panel = Glass.surface
internal val Elevated = Glass.surfaceStrong
internal val Paper = Glass.inkPrimary
internal val Muted = Glass.inkSecondary
internal val Primary = Glass.inkPrimary
internal val OnBrand = Glass.onBrand
internal val Brand = Glass.brand
internal val Accent = Glass.cyan
internal val Focus = Color(0xFFFFFFFF)
internal val Success = Color(0xFF34C759)
internal val Danger = Color(0xFFFF453A)
internal val Hairline = Glass.edgeBottom

internal object TvShape {
    val control = GlassShape.control
    val row = GlassShape.row
    val card = GlassShape.card
    val panel = GlassShape.panel
    val dialog = GlassShape.dialog
}

/** A memory of Compose focus targets, without intercepting directional keys. */
internal class TvFocusMemory {
    var restoreVersion by mutableIntStateOf(0)
    val dialogs = mutableStateListOf<String>()
    val activeDialogs get() = dialogs.size
    var view: String? = null
    var restoring by mutableStateOf(false)
    var directionalInput = false
    val last = mutableMapOf<String, String>()
    val focusedTargets = mutableSetOf<String>()
    val targets = mutableMapOf<String, FocusRequester>()
    val home = TvHomeFocusMemory()

    fun rememberFocus(page: String, key: String) {
        if (page == "首页") home.focused(key, last[page])
        last[page] = key
    }

    fun restore(page: String): Boolean {
        val key = last[page] ?: return false
        return targets["$page:$key"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
    }
}
internal val LocalTvFocusMemory = staticCompositionLocalOf { TvFocusMemory() }
internal val LocalTvFocusPage = staticCompositionLocalOf { "default" }

@Composable internal fun Modifier.tvFocusTarget(key: String): Modifier {
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val topDialog = memory.dialogs.lastOrNull()
    val canReceiveFocus = topDialog == null || page == topDialog
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(memory, page, key) {
        val address = "$page:$key"
        memory.targets[address] = requester
        if (focused) { if (!memory.restoring) memory.rememberFocus(page, key); memory.focusedTargets.add(address) }
        onDispose {
            if (memory.targets[address] === requester) {
                memory.targets.remove(address)
                if (page == "首页") memory.home.forgetPosition(key)
            }
            memory.focusedTargets.remove(address)
        }
    }
    val position = if (page == "首页") Modifier.onGloballyPositioned {
        memory.home.position(key, it.positionInRoot().x + it.size.width / 2f)
    } else Modifier
    return this.focusProperties { canFocus = canReceiveFocus }
        .focusRequester(requester)
        .onFocusChanged {
            focused = it.isFocused
            val address = "$page:$key"
            if (it.isFocused) {
                if (!memory.restoring) memory.rememberFocus(page, key)
                memory.focusedTargets.add(address)
            } else memory.focusedTargets.remove(address)
        }
        .then(position)
}

/**
 * Apple TV Specular Rim Focus:
 * Crisp pure-white top-to-bottom specular gradient rim + subtle diffused white aura.
 * Used on album covers, colorful primary CTAs, and rich cards (zero heavy colored outline).
 */
internal fun Modifier.tvFocus(focused: Boolean, shape: Shape = TvShape.control): Modifier {
    if (!focused) return this
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val ringPx = 1.5.dp.toPx()
        val haloPx = 3.dp.toPx()
        val rimBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.96f),
                0.5f to Color.White.copy(alpha = 0.80f),
                1f to Color.White.copy(alpha = 0.62f),
            ),
            endY = size.height.coerceAtLeast(1f),
        )
        onDrawWithContent {
            drawContent()
            drawOutline(outline, Color.White.copy(alpha = 0.14f), style = Stroke(width = haloPx))
            drawOutline(outline, rimBrush, style = Stroke(width = ringPx))
        }
    }
}

/**
 * Apple TV Illuminated Platinum Focus:
 * Neutral controls (nav pills, sidebar rows, option rows, buttons) illuminate into a
 * luminous platinum-white pill with soft elevation shadow and deep obsidian ink — zero outline border.
 */
internal fun Modifier.tvPlatinumFocus(focused: Boolean, shape: Shape = TvShape.control): Modifier {
    if (!focused) return this
    return this
        .shadow(
            elevation = 10.dp,
            shape = shape,
            ambientColor = Glass.shadowFocus,
            spotColor = Glass.shadowFocus,
            clip = false,
        )
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val fillBrush = Brush.verticalGradient(
                colors = listOf(Glass.focusFill, Glass.focusFillBottom),
                endY = size.height.coerceAtLeast(1f),
            )
            val innerRim = Stroke(width = 0.75.dp.toPx())
            onDrawBehind {
                drawOutline(outline, fillBrush)
                drawOutline(outline, Color.White.copy(alpha = 0.9f), style = innerRim)
            }
        }
}

@Composable internal fun SingVerseTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Brand,
            onPrimary = OnBrand,
            background = Ink,
            onBackground = Paper,
            surface = Glass.surfaceFloat,
            onSurface = Paper,
            surfaceVariant = Elevated,
            onSurfaceVariant = Muted,
            error = Danger,
            onError = OnBrand,
        ),
        content = content,
    )
}

@Composable internal fun Action(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusKey: String = label,
    textSize: TextUnit = 14.sp,
    enabled: Boolean = true,
    danger: Boolean = false,
    quiet: Boolean = false,
    shape: Shape = TvShape.control,
    fillWidth: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val content = when {
        !enabled -> Muted.copy(alpha = .48f)
        selected -> OnBrand
        quiet -> Muted
        danger -> Danger
        else -> Paper
    }
    val usePlatinumFocus = enabled && !selected && !danger
    val surfaceModifier = when {
        !enabled -> Modifier.glassSurface(shape, intensity = 0.45f, base = Color(0x45181C24))
        selected -> Modifier.brandGlassSurface(shape, focused = focused).tvFocus(focused, shape)
        danger -> Modifier.glassSurface(
            shape,
            highlighted = focused,
            base = if (focused) Color(0x38FF453A) else Color(0x20FF453A),
            elevation = if (focused) 4.dp else 0.dp,
        ).tvFocus(focused, shape)
        focused && usePlatinumFocus -> Modifier.tvPlatinumFocus(true, shape)
        quiet -> Modifier
        else -> Modifier.glassSurface(
            shape,
            highlighted = false,
            base = Glass.surfaceFloat,
            elevation = 0.dp,
        )
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .heightIn(min = 34.dp)
            .tvFocusTarget("action:$focusKey")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused && enabled, scaleTarget = if (pressed) 0.97f else 1.045f, liftDp = 1.dp)
            .then(surfaceModifier),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = content,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = when {
                selected -> OnBrand
                danger -> Danger
                else -> Glass.focusInk
            },
            disabledContainerColor = Color.Transparent,
            disabledContentColor = content,
        ),
    ) {
        Text(
            label,
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
            textAlign = if (fillWidth) androidx.compose.ui.text.style.TextAlign.Center else null,
            fontSize = textSize,
            lineHeight = textSize * 1.32f,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable internal fun Heading(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Paper, fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun SectionTitle(title: String, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Paper, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotEmpty()) Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable internal fun StatusPill(label: String, tone: Color = Muted, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val dotAlpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else 1f
    Row(
        modifier
            .glassSurface(GlassShape.pill, intensity = .85f, base = Glass.surfaceFloat, elevation = 2.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(tone.copy(alpha = dotAlpha), CircleShape))
        Text(label, color = if (tone == Muted) Paper else tone, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun LiveEqualizer(playing: Boolean, color: Color = Brand, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val b1 by transition.animateFloat(0.32f, 1f, infiniteRepeatable(tween(460, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b1")
    val b2 by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(580, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b2")
    val b3 by transition.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b3")
    Canvas(modifier.size(width = 12.dp, height = 11.dp)) {
        val levels = if (playing) floatArrayOf(b1, b2, b3) else floatArrayOf(0.28f, 0.28f, 0.28f)
        val barWidth = size.width / 5f
        val gap = barWidth
        levels.forEachIndexed { index, level ->
            val h = (size.height * level).coerceAtLeast(2f)
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = if (playing) color else color.copy(alpha = 0.45f),
                topLeft = Offset(x, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable internal fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(56.dp).glassSurface(GlassShape.card, base = Glass.surfaceFloat, elevation = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            MusicMark(Modifier.size(26.dp), tint = Muted)
        }
        Text(title, color = Paper, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = Muted, fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun MusicMark(modifier: Modifier = Modifier, tint: Color = Muted) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawLine(tint, Offset(w * .42f, h * .72f), Offset(w * .42f, h * .2f), w * .065f, StrokeCap.Round)
        drawLine(tint, Offset(w * .42f, h * .2f), Offset(w * .8f, h * .12f), w * .065f, StrokeCap.Round)
        drawLine(tint, Offset(w * .8f, h * .12f), Offset(w * .8f, h * .62f), w * .065f, StrokeCap.Round)
        drawOval(tint, Offset(w * .19f, h * .64f), Size(w * .26f, h * .2f))
        drawOval(tint, Offset(w * .57f, h * .54f), Size(w * .26f, h * .2f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Input(value: String, hint: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var buttonFocused by remember { mutableStateOf(false) }
    var editorFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val buttonFocus = remember { FocusRequester() }
    val editorFocus = remember { FocusRequester() }
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember(editing) { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            editorFocus.requestFocus()
            keyboard?.show()
        } else if (restoreFocus) {
            buttonFocus.requestFocus()
            restoreFocus = false
        }
    }
    fun finishEditing() { restoreFocus = true; editing = false; keyboard?.hide() }
    LaunchedEffect(editing, imeVisible) {
        if (editing) {
            if (imeVisible) imeShown = true
            else if (imeShown) finishEditing()
        }
    }
    BackHandler(editing) { finishEditing() }
    if (!editing) {
        val shape = GlassShape.pill
        Button(
            onClick = { editing = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .focusRequester(buttonFocus)
                .tvFocusTarget("action:$hint")
                .onFocusChanged { buttonFocused = it.isFocused }
                .tvSpringFocus(buttonFocused, scaleTarget = 1.015f, liftDp = 0.8.dp)
                .glassSurface(
                    shape,
                    highlighted = buttonFocused,
                    base = if (buttonFocused) Glass.surfaceFocus else Glass.surfaceCard,
                    elevation = if (buttonFocused) 6.dp else 2.dp,
                )
                .tvFocus(buttonFocused, shape),
            scale = ButtonDefaults.scale(focusedScale = 1f),
            shape = ButtonDefaults.shape(shape),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (value.isBlank()) Muted else Paper,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = Paper,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeGlyph("search", Modifier.size(14.dp), if (buttonFocused || value.isNotBlank()) Paper else Muted)
                Text(
                    value.ifBlank { hint },
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (value.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else BasicTextField(
        value,
        onChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .focusRequester(editorFocus)
            .tvFocusTarget("input:$hint")
            .onFocusChanged { editorFocused = it.isFocused }
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                if (event.key != Key.Back) false
                else {
                    if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) finishEditing()
                    true
                }
            }
            .glassSurface(GlassShape.pill, highlighted = editorFocused, base = Glass.surfaceFocus, elevation = 8.dp)
            .tvFocus(editorFocused, GlassShape.pill)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finishEditing() }),
        textStyle = TextStyle(color = Paper, fontSize = 16.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(Brand),
    )
}

internal fun JSONObject?.artworkVersion(field: String = "coverVersion"): String =
    this?.optString(field)?.takeIf { it.isNotBlank() && it != "null" }
        ?: this?.optString("revision").orEmpty()

@Composable internal fun SongArtwork(
    app: TvApp,
    song: JSONObject?,
    modifier: Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
    shape: Shape = TvShape.control,
) {
    val scope = app.client.artworkScope
    val path = song?.optString("coverPath").orEmpty()
    val revision = song.artworkVersion()
    val seed = song?.optString("id").orEmpty().ifBlank { song?.optString("title").orEmpty() }
    val (c1, c2) = remember(seed) { fallbackAmbientColors(seed) }
    var bitmap by remember(scope, path, revision) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(scope, path, revision) {
        if (path.isNotEmpty()) bitmap = try {
            app.client.image(path, revision)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }
    Box(
        modifier
            .shadow(elevation = 6.dp, shape = shape, ambientColor = Color(0x66000000), spotColor = Color(0x88000000), clip = false)
            .clip(shape)
            .background(Brush.linearGradient(listOf(c1, c2)))
            .border(0.5.dp, Color(0x16FFFFFF), shape),
    ) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "歌曲封面", Modifier.fillMaxSize(), contentScale = contentScale)
        else MusicMark(Modifier.size(26.dp).align(Alignment.Center), tint = Paper.copy(alpha = 0.34f))
    }
}

@Composable internal fun SongCard(
    app: TvApp,
    song: JSONObject,
    modifier: Modifier = Modifier,
    imageHeight: Dp = Dp.Unspecified,
    metadataVerticalPadding: Dp = 7.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.card
    Button(
        onClick = { app.openSong(song) },
        modifier = modifier
            .tvFocusTarget("song:${song.optString("id")}")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.036f)
            .then(
                if (focused) Modifier.glassSurface(shape, highlighted = true, base = Glass.surfaceFocus, elevation = 12.dp)
                else Modifier
            )
            .tvFocus(focused, shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(7.dp),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                val artworkModifier = if (imageHeight == Dp.Unspecified) Modifier.fillMaxWidth().aspectRatio(1f)
                else Modifier.widthIn(max = imageHeight).fillMaxWidth().aspectRatio(1f)
                SongArtwork(app, song, artworkModifier)
                val playingId = app.snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optString("status") == "PLAYING" }?.optJSONObject("song")?.optString("id").orEmpty()
                if (playingId.isNotBlank() && playingId == song.optString("id")) {
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .brandGlassSurface(GlassShape.pill)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LiveEqualizer(app.playing, color = OnBrand)
                        Text(stateLabel(app.playerState), color = OnBrand, fontSize = 9.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 3.dp, vertical = metadataVerticalPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(song.optString("title"), color = Paper, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun JSONObject.artists(): String = optJSONArray("artists")?.let { array -> (0 until array.length()).joinToString(" / ") { array.optString(it) } }.orEmpty().ifBlank { "歌手未知" }

@Composable internal fun SongTable(app: TvApp, state: LazyListState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongTableText("序号", Modifier.width(32.dp), header = true)
            SongTableText("歌曲", Modifier.weight(1.6f), header = true)
            SongTableText("歌手", Modifier.weight(1f), header = true)
            SongTableText("版本", Modifier.width(80.dp), header = true)
            SongTableText("时长", Modifier.width(48.dp), header = true)
            SongTableText("点歌状态", Modifier.width(100.dp), header = true)
        }
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(app.songs, key = { _, song -> song.getString("id") }) { index, song ->
                SongTableRow(app, song, app.number * 20 + index + 1)
            }
        }
    }
}

@Composable private fun SongTableText(label: String, modifier: Modifier, header: Boolean = false, color: Color = Muted) {
    Text(
        label,
        modifier,
        color = color,
        fontSize = if (header) 11.sp else 12.sp,
        lineHeight = if (header) 16.sp else 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable private fun SongTableRow(app: TvApp, song: JSONObject, number: Int) {
    val id = song.getString("id")
    var focused by remember { mutableStateOf(false) }
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty().filter {
        (it.optString("songId").ifBlank { it.optJSONObject("song")?.optString("id").orEmpty() }) == id &&
            it.optString("status") in listOf("WAITING", "HANDOFF", "PLAYING")
    }
    val status = when {
        app.queueAddSongId == id -> "加入中…"
        app.pendingOperation?.let { it.optString("action") == "ADD" && it.optString("songId") == id } == true -> "待核对"
        queue.isNotEmpty() -> "已点 ×${queue.size}"
        else -> "＋ 点歌"
    }
    Button(
        onClick = { app.addSongFromList(song) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .tvFocusTarget("song:$id")
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) Modifier.glassSurface(TvShape.row, highlighted = true, base = Glass.surfaceFocus, elevation = 5.dp)
                else Modifier
            )
            .tvFocus(focused, TvShape.row)
            .onPreviewKeyEvent {
                if (it.key == Key.DirectionRight) {
                    if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) app.openSong(song)
                    true
                } else false
            },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(TvShape.row),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SongTableText(number.toString().padStart(2, '0'), Modifier.width(32.dp), color = if (focused) Brand else Muted)
            Row(Modifier.weight(1.6f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(app, song, Modifier.size(38.dp), shape = RoundedCornerShape(9.dp))
                Text(
                    song.optString("title"),
                    Modifier.weight(1f),
                    color = Paper,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SongTableText(song.artists(), Modifier.weight(1f), color = Muted)
            Box(
                Modifier
                    .width(80.dp)
                    .background(Color(0x18FFFFFF), GlassShape.pill)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                SongTableText(versionLabel(song.optString("version")), Modifier.fillMaxWidth(), header = true, color = Muted)
            }
            SongTableText(song.optLong("durationMs").takeIf { it > 0 }?.let(::time) ?: "—", Modifier.width(48.dp))
            Column(Modifier.width(100.dp)) {
                SongTableText(
                    status,
                    Modifier.fillMaxWidth(),
                    color = when {
                        queue.isNotEmpty() -> Success
                        focused -> Brand
                        else -> Paper
                    },
                )
                if (focused) SongTableText("右键查看详情", Modifier.fillMaxWidth(), header = true, color = Muted)
            }
        }
    }
}

@Composable internal fun CategoryCard(app: TvApp, tag: JSONObject, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scope = app.client.artworkScope
    val path = tag.optString("coverPath")
    val revision = tag.artworkVersion()
    var focused by remember { mutableStateOf(false) }
    var bitmap by remember(scope, path, revision) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(scope, path, revision) { mutableStateOf(false) }
    LaunchedEffect(scope, path, revision) {
        if (path.isNotBlank()) {
            bitmap = try { app.client.image(path, revision) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
            failed = bitmap == null
        }
    }
    val pastelTones = listOf(
        Color(0xFF2C1B22), Color(0xFF2B2118), Color(0xFF182433),
        Color(0xFF162924), Color(0xFF231C33), Color(0xFF2A241A),
    )
    val tone = pastelTones[(tag.optString("id").hashCode() ushr 1) % pastelTones.size]
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusTarget("category:${tag.optString("id")}")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.03f)
            .glassSurface(
                TvShape.card,
                highlighted = focused,
                base = if (focused) Glass.surfaceFocus else Glass.surfaceFloat,
                tintAccent = tone.copy(alpha = 0.55f),
                elevation = if (focused) 12.dp else 5.dp,
            )
            .tvFocus(focused, TvShape.card),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(0.dp),
        shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(TvShape.card), contentAlignment = Alignment.CenterStart) {
            if (bitmap != null) {
                Image(bitmap!!.asImageBitmap(), "${tag.optString("name")}分类封面", Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x280B0D11), Color(0xE00B0D11)))))
            } else {
                Text(tag.optString("name").take(2), color = Paper.copy(alpha = .07f), fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp))
            }
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tag.optString("name"), modifier = Modifier.weight(1f), color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (selected) Text(
                        "已选",
                        color = OnBrand,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.brandGlassSurface(GlassShape.pill).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text("${tag.optInt("count")} 首歌曲${if (failed) " · 封面暂不可用" else ""}", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun ArtistArtwork(app: TvApp, artist: JSONObject, kind: String, modifier: Modifier) {
    val scope = app.client.artworkScope
    val path = artist.optString(kind + "Path")
    val revision = artist.artworkVersion(kind + "Version")
    val portraitPath = artist.optString("portraitPath")
    val portraitVersion = artist.artworkVersion("portraitVersion")
    var bitmap by remember(scope, path, revision, portraitPath, portraitVersion) { mutableStateOf<Bitmap?>(null) }
    var derived by remember(scope, path, revision, portraitPath, portraitVersion) { mutableStateOf(false) }
    val (c1, c2) = remember(artist.optString("id")) { fallbackAmbientColors(artist.optString("id")) }
    LaunchedEffect(scope, path, revision, portraitPath, portraitVersion) {
        if (path.isNotBlank()) bitmap = try { app.client.image(path, revision) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (kind == "background" && bitmap == null) {
            if (portraitPath.isNotBlank()) {
                val portrait = try { app.client.image(portraitPath, portraitVersion) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
                if (portrait != null) { bitmap = withContext(Dispatchers.Default) { softenedArtistBackground(portrait) }; derived = true }
            }
        }
    }
    val shape = if (kind == "portrait") CircleShape else TvShape.panel
    val fallbackBrush = if (kind == "portrait") {
        Brush.verticalGradient(listOf(Color(0xFF282E3A), Color(0xFF171B24)))
    } else {
        Brush.linearGradient(listOf(c1, c2))
    }
    Box(
        modifier
            .shadow(elevation = 6.dp, shape = shape, ambientColor = Glass.shadowAmbient, spotColor = Glass.shadowSpot, clip = false)
            .clip(shape)
            .background(fallbackBrush)
            .border(1.dp, Color(0x22FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "${artist.optString("name")}${if (kind == "portrait") "头像" else if (derived) "由头像生成的背景" else "背景图"}", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else if (kind == "portrait") Text(artist.optString("name").take(1).uppercase(), color = Paper.copy(alpha = 0.80f), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
        if (derived) Box(Modifier.fillMaxSize().background(Color(0x660B0D11)))
        if (derived) Text(
            "头像生成背景",
            color = Paper,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).glassSurface(GlassShape.pill, base = Glass.surfaceFloat).padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

internal fun softenedArtistBackground(source: Bitmap): Bitmap {
    val width = minOf(96, source.width).coerceAtLeast(1)
    val height = minOf(96, source.height).coerceAtLeast(1)
    val sample = Bitmap.createScaledBitmap(source, width, height, true)
    var pixels = IntArray(width * height)
    sample.getPixels(pixels, 0, width, 0, 0, width, height)
    if (sample !== source) sample.recycle()
    repeat(2) {
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (dx in -6..6) { val color = pixels[y * width + (x + dx).coerceIn(0, width - 1)]; red += android.graphics.Color.red(color); green += android.graphics.Color.green(color); blue += android.graphics.Color.blue(color); count++ }
            horizontal[y * width + x] = android.graphics.Color.rgb(red / count, green / count, blue / count)
        }
        val vertical = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (dy in -6..6) { val color = horizontal[(y + dy).coerceIn(0, height - 1) * width + x]; red += android.graphics.Color.red(color); green += android.graphics.Color.green(color); blue += android.graphics.Color.blue(color); count++ }
            vertical[y * width + x] = android.graphics.Color.rgb(red / count, green / count, blue / count)
        }
        pixels = vertical
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

@Composable internal fun ArtistCard(app: TvApp, artist: JSONObject) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = { app.openArtist(artist) },
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusTarget("artist:${artist.optString("id")}")
            .onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .tvSpringFocus(focused, scaleTarget = 1.075f, liftDp = 2.dp)
                    .then(
                        if (focused) Modifier.shadow(14.dp, CircleShape, ambientColor = Glass.shadowFocus, spotColor = Glass.shadowFocus, clip = false)
                        else Modifier
                    )
                    .tvFocus(focused, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ArtistArtwork(app, artist, "portrait", Modifier.fillMaxSize())
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    artist.optString("name"),
                    color = if (focused) Color.White else Paper.copy(alpha = 0.92f),
                    fontSize = 14.5.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${artist.optInt("count")} 首歌曲",
                    color = if (focused) Paper.copy(alpha = 0.78f) else Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable internal fun PlaylistCard(app: TvApp, playlist: JSONObject, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val coverShape = RoundedCornerShape(14.dp)
    Button(
        onClick = { app.openPlaylist(playlist) },
        modifier = modifier
            .tvFocusTarget("playlist:${playlist.optString("id")}")
            .onFocusChanged { focused = it.isFocused },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(TvShape.card),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Paper, focusedContainerColor = Color.Transparent, focusedContentColor = Paper),
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .tvSpringFocus(focused, scaleTarget = 1.055f, liftDp = 2.dp)
                    .then(
                        if (focused) Modifier.shadow(14.dp, coverShape, ambientColor = Glass.shadowFocus, spotColor = Glass.shadowFocus, clip = false)
                        else Modifier
                    )
                    .tvFocus(focused, coverShape),
            ) {
                SongArtwork(
                    app,
                    playlist,
                    Modifier.fillMaxSize(),
                    shape = coverShape,
                )
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    playlist.optString("name"),
                    color = if (focused) Color.White else Paper.copy(alpha = 0.92f),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${playlist.optInt("count")} 首歌曲",
                    color = if (focused) Paper.copy(alpha = 0.78f) else Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
