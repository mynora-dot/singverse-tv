@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import org.json.*

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun stateLabel(value: String) = mapOf("IDLE" to "已停止", "PREPARING" to "准备中", "BUFFERING" to "缓冲中", "PLAYING" to "正在演唱", "PAUSED" to "已暂停", "ENDED" to "演唱结束", "ERROR" to "播放失败", "WAITING" to "待唱", "HANDOFF" to "等待确认", "COMPLETED" to "已唱", "SKIPPED" to "已跳过", "FAILED" to "播放失败", "CANCELLED" to "已撤回")[value] ?: "等待同步"
internal fun versionLabel(value: String) = mapOf("ORIGINAL" to "原唱", "ACCOMPANIMENT" to "伴奏", "INSTRUMENTAL" to "纯音乐", "DUET" to "合唱", "KARAOKE" to "卡拉 OK")[value] ?: "版本待确认"
internal fun time(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable internal fun Modal(
    title: String,
    subtitle: String = "",
    dismiss: () -> Unit,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val memory = LocalTvFocusMemory.current
    val parent = LocalTvFocusPage.current
    val parentFocus = LocalFocusManager.current
    val dialog = remember(title) { "dialog:${java.util.UUID.randomUUID()}:$title" }
    val returnTarget = remember(memory, parent, dialog) {
        memory.restoring = true
        memory.directionalInput = false
        memory.last[parent]
    }
    DisposableEffect(memory, parent, dialog) {
        memory.restoring = true
        memory.dialogs.add(dialog)
        parentFocus.clearFocus(force = true)
        onDispose {
            memory.dialogs.remove(dialog)
            memory.last.remove(dialog)
            if (returnTarget != null) memory.last[parent] = returnTarget
            memory.restoring = true
            memory.restoreVersion++
        }
    }
    // Dialog owns a separate Android window, whose platform density otherwise replaces
    // the logical TV canvas density. Keep text and controls at the same scale as the player.
    val canvasDensity = LocalDensity.current
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalTvFocusPage provides dialog, LocalDensity provides canvasDensity) {
            val entry = remember { FocusRequester() }
            LaunchedEffect(dialog, memory.dialogs.lastOrNull(), memory.restoreVersion) {
                withFrameNanos { }; withFrameNanos { }
                if (memory.dialogs.lastOrNull() != dialog) return@LaunchedEffect
                memory.restoring = false
                if (!memory.restore(dialog)) {
                    val preferred = listOf("取消", "完成", "关闭提示", "返回").firstNotNullOfOrNull { memory.targets["$dialog:action:$it"] }
                    if (preferred != null) preferred.requestFocus() else entry.requestFocus()
                }
            }
            Column(
                Modifier
                    .width(if (wide) 740.dp else 490.dp)
                    .heightIn(max = 462.dp)
                    .tvDialogEnter()
                    .focusRequester(entry)
                    .focusGroup()
                    .glassSurface(GlassShape.dialog, highlighted = true, base = Glass.surfaceDialog, elevation = 20.dp)
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, color = Paper, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                content()
            }
        }
    }
}

@Composable internal fun TvScreen(app: TvApp, focusMemory: TvFocusMemory = remember(app.client.base) { TvFocusMemory() }) {
    val hazeState = rememberHazeState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        AmbientStage(modifier = Modifier.tvHazeSource(hazeState))
        val density = LocalDensity.current
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale),
            LocalTvHazeState provides hazeState,
        ) {
            Box(Modifier.fillMaxSize()) {
                TvScreenContent(app, focusMemory)
                PartyReactionOverlay(app)
            }
        }
    }
}

@Composable private fun TvScreenContent(app: TvApp, memory: TvFocusMemory) {
    val view = when {
        app.selected != null -> "${app.page}:song:${app.selected?.optString("id")}"
        app.playlistDetail != null -> "${app.page}:playlist:${app.playlistDetail?.optString("id")}"
        app.artistDetail != null -> "${app.page}:artist:${app.artistDetail?.optString("id")}"
        else -> app.page
    }
    if (memory.view != view) { memory.view = view; memory.restoring = true; memory.directionalInput = false }
    CompositionLocalProvider(LocalTvFocusMemory provides memory, LocalTvFocusPage provides view) {
        ConnectedTvContent(app, memory, view)
    }
}

@Composable private fun ConnectedTvContent(app: TvApp, memory: TvFocusMemory, view: String) {
    var invitationOpen by remember { mutableStateOf(false) }
    var homeSearchOpen by remember { mutableStateOf(false) }
    val librarySongs = rememberLazyListState()
    val playlistsGrid = rememberLazyGridState()
    val playlistSongs = rememberLazyListState()
    val artistsGrid = rememberLazyGridState()
    val categoriesGrid = rememberLazyGridState()
    var categoryDimension by remember { mutableStateOf("LANGUAGE") }
    val artistSongs = rememberLazyListState()
    val historySongs = rememberLazyListState()
    val popularSongs = rememberLazyListState()
    var handledSongListReset by remember { mutableIntStateOf(app.songListResetVersion) }
    val homeFocus = remember { FocusRequester() }
    val home = if (view == "首页") TvHomeFocusRoutes(
        memory, app.favoriteSongs().take(4), app.homeHistorySongs.take(4), app.homePopularSongs.take(4),
    ) else null
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val selectedNavigation = when {
        app.page in listOf("首页", "收藏", "历史记录", "热门歌曲") -> "首页"
        app.selected != null || app.page in listOf("找歌", "歌手", "分类", "歌单", "点歌") -> "点歌"
        else -> app.page
    }
    val activityFocusItems = home?.availability ?: when (app.page) {
        "历史记录" -> app.homeHistorySongs
        "热门歌曲" -> app.homePopularSongs
        else -> emptyList()
    }.associate { it.optString("id") to it.optBoolean("available", true) }
    val homeRetries = home?.let {
        listOf(
            it.favorites.isEmpty() && showsHomeRetry(app.favoritesLoading, app.favoritesReady, app.favoritesError),
            it.history.isEmpty() && showsHomeRetry(app.homeHistoryLoading, app.homeHistoryReady, app.homeHistoryError),
            it.popular.isEmpty() && showsHomeRetry(app.homePopularLoading, app.homePopularReady, app.homePopularError),
        )
    }
    var settledView by remember { mutableStateOf(view) }
    val navDestinationKey = "nav:$selectedNavigation"
    val activityTabKey = when (app.page) {
        "历史记录" -> "action:activity:tab:history"
        "热门歌曲" -> "action:activity:tab:popular"
        else -> null
    }
    if (view != settledView && memory.activeDialogs == 0 &&
        memory.last[view] != navDestinationKey &&
        (activityTabKey == null || memory.last[view] != activityTabKey)
    ) {
        // Suppress transient TopBar focus flash while the outgoing screen disposes and the new screen settles.
        memory.restoring = true
    }
    if (memory.activeDialogs == 0 && home?.unavailableSong(memory.last[view]) == true) {
        // Keep the previous song address until the home shelf chooses its replacement.
        memory.restoring = true
    }
    fun selectNavigation(destination: String) {
        if (app.page == destination && app.selected == null && app.artistDetail == null && app.playlistDetail == null) return
        // Selecting a tab must keep focus in the navigation, even if its page remembers content focus.
        memory.last[destination] = "nav:$destination"
        homeSearchOpen = false
        app.navigate(destination)
    }
    LaunchedEffect(app.connected, app.page) {
        if (!app.connected || app.page !in listOf("首页", "找歌")) homeSearchOpen = false
    }
    LaunchedEffect(app.connected, view, memory.restoreVersion, windowFocused, app.songListResetVersion, activityFocusItems.entries.toList(), homeRetries) {
        if (app.connected && memory.activeDialogs == 0 && windowFocused) {
            val navigation = "nav:$selectedNavigation"
            if (app.page == selectedNavigation && memory.last[view] == navigation && memory.focusedTargets.singleOrNull() == "$view:$navigation") {
                // Navigation survives the page content change; restoring it would interrupt key repeats.
                settledView = view
                memory.restoring = false
                return@LaunchedEffect
            }
            val activityTab = when (app.page) {
                "历史记录" -> "action:activity:tab:history"
                "热门歌曲" -> "action:activity:tab:popular"
                else -> null
            }
            if (activityTab != null && memory.last[view] == activityTab && memory.focusedTargets.singleOrNull() == "$view:$activityTab") {
                // Activity header tab survives switching between 历史记录 and 热门歌曲 without bouncing focus.
                settledView = view
                memory.restoring = false
                return@LaunchedEffect
            }
            val focused = memory.last[view]
            if (home != null && !memory.restoring && focused != null &&
                !home.unavailableSong(focused) && memory.focusedTargets.singleOrNull() == "$view:$focused"
            ) {
                settledView = view
                return@LaunchedEffect
            }
            memory.restoring = true
            withFrameNanos { }
            if (memory.activeDialogs > 0) return@LaunchedEffect
            var scrolled = false
            val songList = when {
                app.playlistDetail != null -> playlistSongs
                app.artistDetail != null -> artistSongs
                app.page == "找歌" -> librarySongs
                else -> null
            }
            if (app.selected == null && songList != null && handledSongListReset != app.songListResetVersion) {
                handledSongListReset = app.songListResetVersion
                songList.scrollToItem(0)
                scrolled = true
                if (app.songListFocusFirst || memory.last[view]?.startsWith("song:") == true) {
                    app.songs.firstOrNull()?.let { memory.last[view] = "song:${it.getString("id")}" }
                }
            }
            if (memory.last[view]?.startsWith("nav:") == true) memory.last[view] = "nav:$selectedNavigation"
            val target = memory.last[view].orEmpty()
            if (app.selected == null) {
                val id = target.substringAfter(':', "")
                if ((app.page == "历史记录" && target.startsWith("history:")) || (app.page == "热门歌曲" && target.startsWith("popular:"))) {
                    val (state, items) = if (app.page == "历史记录") historySongs to app.homeHistorySongs else popularSongs to app.homePopularSongs
                    val index = items.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) { state.scrollToItem(index); scrolled = true }
                } else if (songList != null && target.startsWith("song:")) {
                    var index = app.songs.indexOfFirst { it.optString("id") == id }
                    if (index < 0 && app.songs.isNotEmpty()) {
                        index = 0
                        memory.last[view] = "song:${app.songs.first().getString("id")}"
                    }
                    if (index >= 0) {
                        val layout = songList.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item == null || item.offset < layout.viewportStartOffset || item.offset + item.size > layout.viewportEndOffset) {
                            songList.scrollToItem(index)
                            scrolled = true
                        }
                    }
                } else {
                    val (state, items) = when {
                        app.page == "歌手" && target.startsWith("artist:") -> artistsGrid to app.artists
                        app.page == "歌单" && target.startsWith("playlist:") -> playlistsGrid to app.playlists
                        app.page == "分类" && target.startsWith("category:") -> categoriesGrid to app.tags.filter { it.optString("dimension") == categoryDimension }
                        else -> artistsGrid to emptyList()
                    }
                    val index = items.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) {
                        val layout = state.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item == null || item.offset.y < layout.viewportStartOffset || item.offset.y + item.size.height > layout.viewportEndOffset) {
                            state.scrollToItem(index)
                            scrolled = true
                        }
                    }
                }
            }
            if (scrolled) withFrameNanos { }
            if ((home?.unavailableSong(target) == true || !memory.restore(view)) && app.page != "演唱") {
                val homeEntry = home?.restoredEntry(target)
                val default = when {
                    homeEntry != null -> homeEntry
                    app.selected != null -> "action:加入待唱"
                    app.playlistDetail != null -> app.songs.firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { "song:$it" } ?: "action:返回歌单"
                    app.artistDetail != null -> app.songs.firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { "song:$it" } ?: "action:返回歌手"
                    app.page == "历史记录" -> "action:activity:tab:history"
                    app.page == "热门歌曲" -> "action:activity:tab:popular"
                    app.page == "找歌" -> app.songs.firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { "song:$it" } ?: "action:$SongSearchHint"
                    app.page == "歌手" -> app.artists.firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { "artist:$it" } ?: "action:查找歌手"
                    app.page == "分类" -> "action:${categoryDimensionLabel(categoryDimension)}"
                    app.page == "歌单" -> app.playlists.firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { "playlist:$it" } ?: "action:查找歌单"
                    app.page == "收藏" -> app.favoriteSongs().firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { "action:favorite:$it" } ?: "action:刷新收藏"
                    else -> null
                }
                val defaultRequester = default?.let { memory.targets["$view:$it"] }
                if (default != null && defaultRequester != null) {
                    memory.rememberFocus(view, default)
                    defaultRequester.requestFocus()
                } else {
                    memory.last[view] = "nav:$selectedNavigation"
                    homeFocus.requestFocus()
                }
            }
            settledView = view
            memory.restoring = false
            if (app.page == "演唱") memory.focusedTargets.singleOrNull()?.takeIf { it.startsWith("$view:") }?.let { memory.last[view] = it.removePrefix("$view:") }
        }
    }
    LaunchedEffect(app.connected, app.snapshot.optString("id")) { if (app.connected) app.ensureInvitation() }
    BackHandler(enabled = app.connected) {
        when {
            app.error.isNotEmpty() && !app.error.startsWith("与 NAS 同步失败") -> app.error = ""
            app.selected != null -> app.selected = null
            app.playlistDetail != null -> app.closePlaylist()
            app.artistDetail != null -> app.closeArtist()
            app.page == "演唱" && app.controlVisible -> app.controlVisible = false
            app.page == "找歌" && app.categoryResults -> app.navigate("分类")
            app.page == "找歌" && homeSearchOpen -> { homeSearchOpen = false; app.navigate("首页") }
            app.page in listOf("找歌", "歌手", "分类", "歌单") -> app.navigate("点歌")
            app.page != "首页" -> app.navigate("首页")
            else -> app.pendingConfirm = "退出演唱"
        }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        // Automatic selection belongs to D-pad focus movement, not platform/editor focus fallback.
        memory.directionalInput = it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
        false
    }) {
        val rootMode = when {
            !app.connected -> "login"
            app.page == "演唱" -> "player"
            else -> "shell"
        }
        AnimatedContent(
            targetState = rootMode,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(tween(200, delayMillis = 40, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(120, easing = FastOutSlowInEasing))
            },
            label = "tv-root-stage",
        ) { mode ->
            val modeCurrent = mode == rootMode
            Box(if (modeCurrent) Modifier.fillMaxSize() else Modifier.fillMaxSize().focusProperties { canFocus = false }) {
                when (mode) {
                    "login" -> TvLoginScreen(app)
                    "player" -> PlayerScreen(app)
                    else -> Column(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 42.dp, end = 42.dp, top = 18.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                // Consistent centered Floating Liquid Glass Top Navigation Bar across all pages
                Box(Modifier.fillMaxWidth().height(42.dp)) {
                    Row(
                        Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        HomeGlyph("brand", Modifier.size(26.dp), Paper)
                        Text("SingVerse", color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .glassSurface(GlassShape.pill, highlighted = false, intensity = 0.92f, base = Glass.surfaceFloat, elevation = 4.dp, backdropBlur = true)
                            .padding(horizontal = 5.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf("首页", "点歌", "已点", "设置").forEach { page ->
                            TvNavigation(
                                if (page == "已点") "已点 ${queuedSongCount(app)}" else page,
                                (if (page == selectedNavigation) Modifier.focusRequester(homeFocus) else Modifier)
                                    .tvFocusTarget("nav:$page")
                                    .focusProperties {
                                        if (home != null && page == "首页") {
                                            left = FocusRequester.Cancel
                                            up = FocusRequester.Cancel
                                            down = home.target(home.navigationEntry())
                                        }
                                        if (app.page == "设置" && page == "设置") {
                                            down = memory.targets["$view:action:连接"] ?: FocusRequester.Default
                                        }
                                    }
                                    .onFocusChanged { if (it.isFocused && memory.directionalInput && memory.activeDialogs == 0) selectNavigation(page) },
                                selected = selectedNavigation == page,
                            ) { selectNavigation(page) }
                        }
                    }
                    Row(
                        Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (app.page != "首页") HomeButton("手机点歌", focusKey = "phone") { invitationOpen = true; app.ensureInvitation() }
                        Row(
                            Modifier
                                .glassSurface(GlassShape.pill, intensity = 0.78f, base = Glass.surfaceCard, elevation = 2.dp, backdropBlur = true)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(6.dp).background(if (app.online) Success else Danger, CircleShape))
                            Text(if (app.online) "NAS 已连接" else "网络异常", color = if (app.online) Paper.copy(alpha = 0.85f) else Danger, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                val screenTransitionKey = when {
                    app.selected != null || app.playlistDetail != null || app.artistDetail != null -> view
                    app.page == "历史记录" || app.page == "热门歌曲" -> "activity"
                    else -> view
                }
                val contentState = TvContentState(
                    key = screenTransitionKey,
                    page = app.page,
                    selected = app.selected,
                    playlist = app.playlistDetail,
                    artist = app.artistDetail,
                    home = home,
                )
                AnimatedContent(
                    targetState = contentState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentKey = { it.key },
                    transitionSpec = {
                        fadeIn(tween(180, delayMillis = 40, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(tween(110, easing = FastOutSlowInEasing))
                    },
                    label = "tv-main-content",
                ) { state ->
                    val isCurrent = state.key == screenTransitionKey
                    val currentFocusPage = LocalTvFocusPage.current
                    CompositionLocalProvider(LocalTvFocusPage provides if (isCurrent) currentFocusPage else "__exiting__:${state.key}") {
                        Box(if (isCurrent) Modifier.fillMaxSize() else Modifier.fillMaxSize().focusProperties { canFocus = false }) {
                            if (state.selected != null) Detail(app, songOverride = state.selected)
                            else if (state.playlist != null) PlaylistDetail(app, playlistSongs, playlistOverride = state.playlist)
                            else if (state.artist != null) ArtistDetail(app, artistSongs, artistOverride = state.artist)
                            else when (state.page) {
                                "首页" -> state.home?.let { homeRoutes ->
                                    TvHomeScreen(
                                        app, homeRoutes,
                                        onSearch = {
                                            homeSearchOpen = true
                                            memory.last["找歌"] = "action:$SongSearchHint"
                                            app.navigate("找歌")
                                        },
                                        onPhone = {
                                            if (!app.online) app.reconnect()
                                            app.ensureInvitation()
                                            invitationOpen = true
                                        },
                                    )
                                }
                                "历史记录", "热门歌曲" -> TvHomeActivityScreen(
                                    app,
                                    popular = if (isCurrent) app.page == "热门歌曲" else state.page == "热门歌曲",
                                    historyState = historySongs,
                                    popularState = popularSongs,
                                )
                                "点歌" -> SongCenter(app)
                                "找歌" -> Library(app, librarySongs)
                                "歌单" -> Playlists(app, playlistsGrid)
                                "歌手" -> Artists(app, artistsGrid)
                                "分类" -> Categories(app, categoriesGrid, categoryDimension) { categoryDimension = it }
                                "收藏" -> FavoritesContent(app, Modifier.fillMaxSize())
                                "已点" -> Queue(app)
                                "设置" -> Settings(app)
                            }
                        }
                    }
                }
                if (app.page != "已点") {
                    val compact = app.page == "首页" || (app.page == "点歌" && app.selected == null)
                    SingingBar(app, compact = compact, home = home)
                }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 78.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = app.queueAddMessage.isNotBlank() && app.selected == null && app.page != "演唱",
            enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            Row(
                Modifier
                    .widthIn(max = 700.dp)
                    .glassSurface(GlassShape.pill, highlighted = true, base = Glass.surfaceSheet, elevation = 12.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(7.dp).background(Brand, CircleShape))
                Text(app.queueAddMessage, color = Paper, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (app.error.isNotEmpty() && !app.error.startsWith("与 NAS 同步失败")) Modal("操作提示", "操作没有完成，请根据提示继续。", dismiss = { app.error = "" }) {
        Text(app.error, color = Paper, fontSize = 15.sp, lineHeight = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("关闭提示") { app.error = "" }
            if (app.pendingOperation != null) {
                Action("核对后重试", selected = true) { app.retryPending() }
                Action("放弃本次重试", danger = true) { app.discardPending(); app.error = "" }
            }
        }
    }
    if (invitationOpen) Modal("手机点歌", "微信扫一扫，加入这场聚会。", { invitationOpen = false }, wide = true) {
        if (app.snapshot.optString("status") == "ENDED") {
            EmptyState("本场聚会已结束", "开始新聚会后即可邀请亲友。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("完成") { invitationOpen = false }
                Action("开始新聚会", selected = true) { app.newSession() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(206.dp).clip(TvShape.card).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                    if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.fillMaxSize())
                    else Text(if (app.invitationLoading) "正在生成邀请…" else if (!app.online) "网络异常" else "邀请待刷新", color = Ink, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("让亲友自己选一首", color = Paper, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("电视与手机连接同一网络。扫码后填写昵称即可点歌，无需安装应用。", color = Muted, fontSize = 13.5.sp, lineHeight = 20.sp)
                    StatusPill(if (app.participantsKnown) "已加入 ${app.participantCount} 台设备" else "正在同步设备", Success, pulse = app.participantsKnown)
                    if (app.invitationError.isNotBlank()) Text(app.invitationError, color = Danger, fontSize = 13.sp, lineHeight = 19.sp)
                    Text("刷新邀请后，旧二维码立即失效。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("刷新邀请", selected = true) { app.invite() }
                if (!app.online) Action("重新连接") { app.reconnect() }
            }
            Action("完成") { invitationOpen = false }
        }
    }
    if (app.showUpdatePrompt && app.error.isEmpty() && app.pendingConfirm == null && app.selected == null && !invitationOpen) {
        val release = app.updateRelease
        if (release != null) Modal("发现 TV 新版本 ${release.versionName}", "安装包来自当前 NAS，下载后由 Android 确认安装。", dismiss = { app.dismissUpdate() }) {
            Text(
                if (release.notes.isBlank()) "可更新到 ${release.versionName}，约 ${release.size / 1048576} MB。" else release.notes,
                color = Paper,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("取消") { app.dismissUpdate() }
                Action("跳过此版本") { app.skipUpdate() }
                Action("立即更新", selected = true) { app.downloadUpdate() }
            }
        }
    }
    app.pendingConfirm?.let { action ->
        Modal("${action}？", "此操作会影响当前演唱。", { app.pendingConfirm = null }) {
            Text(if (action == "清空待唱") "所有待唱歌曲将被移除，当前演唱不受影响。" else "请确认后继续。", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("取消") { app.pendingConfirm = null }
                Action("确认", selected = true, danger = action in listOf("散场", "清空待唱", "更换 NAS")) {
                    app.pendingConfirm = null
                    when (action) {
                        "散场" -> app.command("END")
                        "清空待唱" -> app.command("CLEAR")
                        "退出演唱" -> { app.stopLocal(); app.navigate("首页") }
                        "更换 NAS" -> app.disconnect()
                    }
                }
            }
        }
    }
}

internal fun queuedSongCount(app: TvApp) = app.snapshot.optJSONArray("queue")?.objects().orEmpty().count { it.optString("status") in listOf("WAITING", "HANDOFF", "PLAYING") }

@Composable private fun SongCenter(app: TvApp) {
    val covers = app.homeSongs.ifEmpty { app.songs }.asSequence()
        .filter { it.optString("id").isNotBlank() }.take(8).toList()
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val firstSongId = covers.firstOrNull()?.getString("id")
    val lastSongId = covers.lastOrNull()?.getString("id")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SongEntryRow(app, searchModifier = Modifier.focusProperties {
            firstSongId?.let { memory.targets["$page:song:$it"] }?.let { down = it }
        })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("曲库", "按封面快速点唱")
                Spacer(Modifier.weight(1f))
                if (app.snapshot.optString("status") == "ENDED") Action("开始新聚会", selected = true, textSize = 12.5.sp) { app.newSession() }
                Action(
                    "刷新曲库",
                    modifier = Modifier.focusProperties {
                        lastSongId?.let { memory.targets["$page:song:$it"] }?.let { down = it }
                    },
                    quiet = true,
                    textSize = 12.5.sp,
                ) { app.run { app.loadHome(); app.loadSongs(); app.loadTags() } }
            }
            LibraryShelf(app, covers, Modifier.weight(1f))
        }
    }
}

@Composable private fun SongEntryRow(app: TvApp, searchModifier: Modifier) {
    val entries = listOf(
        Triple("search", "搜索歌曲", "歌名 · 歌手 · 拼音 · 编号"),
        Triple("mic", "歌手点歌", "按歌手找歌"),
        Triple("grid", "分类点歌", "语种 · 风格 · 年代"),
        Triple("playlist", "我的歌单", if (app.homePlaylists.isNotEmpty()) "${app.homePlaylists.size} 个共享歌单" else "共享歌单"),
    )
    val badgeTones = listOf(Brand, Glass.amber, Accent, Glass.violet)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEachIndexed { index, entry ->
            val modifier = Modifier.weight(if (index == 0) 1.2f else 1f).heightIn(min = 60.dp)
            SongEntryCard(
                entry.first,
                entry.second,
                entry.third,
                badgeTones[index],
                if (index == 0) modifier.then(searchModifier) else modifier,
                "quick:$index",
            ) {
                app.navigate(listOf("找歌", "歌手", "分类", "歌单")[index])
            }
        }
    }
}

@Composable private fun SongEntryCard(
    icon: String,
    title: String,
    subtitle: String,
    badgeTone: Color,
    modifier: Modifier,
    focusKey: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.card
    Button(
        onClick,
        modifier = modifier
            .tvFocusTarget(focusKey)
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.03f)
            .glassSurface(
                shape,
                highlighted = focused,
                base = if (focused) Glass.surfaceFocus else Glass.surfaceFloat,
            )
            .tvFocus(focused, shape),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Paper,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(badgeTone.copy(alpha = 0.13f), RoundedCornerShape(10.dp))
                    .border(0.75.dp, badgeTone.copy(alpha = 0.24f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                HomeGlyph(icon, Modifier.size(17.dp), badgeTone)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = Paper, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HomeGlyph("chevron", Modifier.size(10.dp), if (focused) Paper else Muted)
        }
    }
}

@Composable private fun LibraryShelf(app: TvApp, covers: List<JSONObject>, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (covers.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().glassSurface(TvShape.card, base = Glass.surfaceCard).padding(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MusicMark(Modifier.size(26.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (app.busy) "正在读取曲库" else "曲库还没有歌曲", color = Paper, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                    Text("从 NAS 扫描歌曲后，封面会排在这里", color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val gap = 11.dp
            val width = ((maxWidth - gap * 7) / 8f).coerceAtLeast(1.dp)
            Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                covers.forEach { song ->
                    key(song.getString("id")) {
                        LibrarySongTile(app, song, Modifier.width(width))
                    }
                }
            }
        }
    }
}

@Composable private fun LibrarySongTile(app: TvApp, song: JSONObject, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = TvShape.control
    Button(
        onClick = { app.openSong(song) },
        modifier = modifier
            .tvFocusTarget("song:${song.getString("id")}")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(focused, scaleTarget = 1.04f)
            .then(
                if (focused) Modifier.glassSurface(shape, highlighted = true, base = Glass.surfaceFocus, elevation = 12.dp)
                else Modifier
            )
            .tvFocus(focused, shape),
        contentPadding = PaddingValues(5.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
    ) {
        Column(Modifier.fillMaxWidth()) {
            SongArtwork(
                app,
                song,
                Modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics { },
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                song.optString("title").ifBlank { "未命名歌曲" },
                color = Paper,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(song.artists(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun SingingBar(app: TvApp, compact: Boolean = false, home: TvHomeFocusRoutes? = null) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
    val next = queue.firstOrNull { it.optString("status") == "HANDOFF" } ?: queue.firstOrNull { it.optString("status") == "WAITING" }
    val song = (current ?: next)?.optJSONObject("song")
    val barHeight = if (compact) 46.dp else 50.dp
    val artSize = if (compact) 32.dp else 36.dp
    val buttonSize = Modifier.height(if (compact) 30.dp else 32.dp)
    val idle = song == null && current == null
    val primaryKey = when {
        app.snapshot.optString("status") == "ENDED" -> "bar:session"
        current == null && next != null -> "bar:start"
        current != null || app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty().isNotEmpty() -> "bar:sing"
        else -> null
    }
    val controls = listOfNotNull("bar:reconnect".takeIf { !app.online }, "bar:queue", primaryKey)
    fun control(key: String) = buttonSize.then(home?.bottom(controls, key) ?: Modifier)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .glassSurface(
                    GlassShape.pill,
                    highlighted = current != null,
                    intensity = if (idle) 0.85f else 1f,
                    base = Glass.surfaceFloat,
                    elevation = 6.dp,
                    backdropBlur = true,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (song != null) {
                SongArtwork(app, song, Modifier.size(artSize), shape = CircleShape)
            } else {
                Box(
                    Modifier
                        .size(artSize)
                        .clip(CircleShape)
                        .background(Color(0xFF1D212A))
                        .border(0.75.dp, Glass.champagneGold.copy(alpha = 0.30f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MusicMark(Modifier.size(16.dp), tint = Glass.champagneGold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (current != null) LiveEqualizer(app.playing, color = Glass.champagneGold)
                    Text(
                        when {
                            song == null -> "今晚，唱你喜欢的歌"
                            else -> song.optString("title")
                        },
                        color = Paper,
                        fontSize = if (compact) 13.5.sp else 14.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when {
                        app.snapshot.optString("status") == "ENDED" -> "聚会已结束"
                        current != null -> "${song?.artists()} · ${stateLabel(app.playerState)}"
                        next != null -> "${song?.artists()} · 等待确认开唱"
                        else -> "选一首喜欢的歌开始"
                    },
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!app.online) HomeButton("网络异常 · 重连", control("bar:reconnect"), focusKey = "bar:reconnect") { app.reconnect() }
            HomeButton("已点歌曲 (${queuedSongCount(app)})", control("bar:queue"), focusKey = "bar:queue") { app.navigate("已点") }
            when (primaryKey) {
                "bar:session" -> HomeButton("开始新聚会", control(primaryKey), primary = true, focusKey = primaryKey) { app.newSession() }
                "bar:start" -> HomeButton("确认开唱", control(primaryKey), primary = true, focusKey = primaryKey) { app.command("START") }
                "bar:sing" -> HomeButton("返回演唱", control(primaryKey), primary = current != null, focusKey = primaryKey) { app.page = "演唱" }
            }
        }
    }
}

@Composable internal fun Qr(value: String, modifier: Modifier = Modifier.size(190.dp), description: String = "本场邀请二维码") {
    BoxWithConstraints(modifier.background(Color.White)) {
        val size = minOf(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(1)
        val bitmap = remember(value, size) {
            runCatching {
                val matrix = MultiFormatWriter().encode(
                    value,
                    BarcodeFormat.QR_CODE,
                    size,
                    size,
                    mapOf(EncodeHintType.ERROR_CORRECTION to "M", EncodeHintType.MARGIN to 1),
                )
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
                    setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
                }
            }.getOrNull()
        }
        if (bitmap != null) Image(bitmap.asImageBitmap(), description, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
}

private const val SongSearchHint = "搜索歌名、歌手、拼音或编号"

@Composable private fun Library(app: TvApp, list: LazyListState) {
    val hasFilters = app.selectedTags.isNotEmpty() || app.unclassified || app.artistId.isNotBlank() || app.query.isNotBlank()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Input(app.query, SongSearchHint) { app.query = it } }
            Text(
                "${app.total} 首${if (app.selectedTags.isNotEmpty()) " · ${app.selectedTags.size} 项分类" else ""}",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .clip(GlassShape.pill)
                    .background(Color(0x12FFFFFF))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
            Action("搜索", selected = true, shape = GlassShape.pill) { app.artistId = ""; app.search() }
            Action("分类", shape = GlassShape.pill) { app.navigate("分类") }
            if (hasFilters) {
                Action("清除筛选", shape = GlassShape.pill) { app.selectedTags = emptySet(); app.unclassified = false; app.artistId = ""; app.query = ""; app.search() }
            }
        }
        if (app.songs.isEmpty()) EmptyState(if (app.busy) "正在找歌" else "没有找到歌曲", "试试其他关键词，或清除筛选条件。", Modifier.weight(1f))
        else SongTable(app, list, Modifier.weight(1f))
        SongPagination(app)
    }
}

@Composable private fun Pagination(page: Int, total: Long, previous: () -> Unit, next: () -> Unit, hint: String = "方向键选择 · 确认打开") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Action("上一页", enabled = page > 0, onClick = previous)
        Text("${page + 1} / ${maxOf(1, (total + 19) / 20)}", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        Action("下一页", enabled = (page + 1) * 20 < total, onClick = next)
        Spacer(Modifier.weight(1f))
        Text(hint, color = Muted.copy(alpha = .7f), fontSize = 11.sp)
    }
}

@Composable private fun SongPagination(app: TvApp) {
    Pagination(
        app.number,
        app.total,
        { if (app.number > 0) { app.number--; app.search(false, focusFirst = true) } },
        { if ((app.number + 1) * 20 < app.total) { app.number++; app.search(false, focusFirst = true) } },
        hint = "上下选歌 · 确认点歌 · 右键详情",
    )
}

private fun categoryDimensionLabel(dimension: String): String = when (dimension) {
    "LANGUAGE" -> "语种"
    "DECADE" -> "年代"
    "GENRE" -> "风格"
    "MOOD" -> "情绪"
    "SCENE" -> "场景"
    "CUSTOM" -> "自定义"
    else -> "语种"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Categories(app: TvApp, grid: LazyGridState, dimension: String, onDimensionChange: (String) -> Unit) {
    val dimensions = linkedMapOf("LANGUAGE" to "语种", "DECADE" to "年代", "GENRE" to "风格", "MOOD" to "情绪", "SCENE" to "场景", "CUSTOM" to "自定义")
    val scope = rememberCoroutineScope()
    val memory = LocalTvFocusMemory.current
    fun selectDimension(key: String) {
        if (dimension == key) return
        onDimensionChange(key)
        scope.launch { grid.scrollToItem(0) }
    }
    val focusMargin = with(LocalDensity.current) { 14.dp.toPx() }
    val focusScroll = remember(focusMargin) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < focusMargin -> offset - focusMargin
                offset + size > containerSize - focusMargin -> offset + size - containerSize + focusMargin
                else -> 0f
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LazyRow(
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(GlassShape.pill)
                    .background(Color(0x0FFFFFFF))
                    .border(1.dp, Color(0x14FFFFFF), GlassShape.pill),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(4.dp),
            ) {
                items(dimensions.keys.toList()) { key ->
                    CategoryDimensionPill(
                        label = dimensions.getValue(key),
                        selected = dimension == key,
                        onFocusSelect = { if (memory.directionalInput && !memory.restoring && memory.activeDialogs == 0) selectDimension(key) },
                    ) { selectDimension(key) }
                }
            }
            Action("未分类", shape = GlassShape.pill, textSize = 13.sp) { app.openCategory() }
        }
        AnimatedContent(
            targetState = dimension,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                fadeIn(tween(160, delayMillis = 30, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(90, easing = FastOutSlowInEasing))
            },
            label = "category-grid",
        ) { targetDimension ->
            val focusPage = LocalTvFocusPage.current
            val activeTabKey = "$focusPage:action:${dimensions.getValue(targetDimension)}"
            val tags = app.tags.filter { it.optString("dimension") == targetDimension }
            if (tags.isEmpty()) EmptyState("暂无${dimensions[targetDimension]}分类", "可以在 NAS 管理页整理歌曲分类。", Modifier.fillMaxSize())
            else CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    state = if (targetDimension == dimension) grid else rememberLazyGridState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(tags, key = { _, tag -> tag.getString("id") }) { index, tag ->
                        val id = tag.getString("id")
                        CategoryCard(
                            app,
                            tag,
                            selected = id in app.selectedTags,
                            modifier = if (index < 4) Modifier.focusProperties {
                                memory.targets[activeTabKey]?.let { up = it }
                            } else Modifier,
                        ) { app.openCategory(id) }
                    }
                }
            }
        }
    }
}

@Composable private fun CategoryDimensionPill(
    label: String,
    selected: Boolean,
    onFocusSelect: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = GlassShape.pill
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(34.dp)
            .tvFocusTarget("action:$label")
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocusSelect()
            }
            .tvSpringFocus(focused, scaleTarget = 1.04f, liftDp = 0.5.dp)
            .then(
                when {
                    focused -> Modifier.tvPlatinumFocus(true, shape)
                    selected -> Modifier.clip(shape).background(Color(0x1EFFFFFF))
                    else -> Modifier
                }
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Paper else Glass.inkSecondary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
    ) {
        Text(
            label,
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable private fun Artists(app: TvApp, grid: LazyGridState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("歌手", color = Paper, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                if (app.artistTotal > 0) {
                    Text(
                        "${app.artistTotal} 位",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .clip(GlassShape.pill)
                            .background(Color(0x12FFFFFF))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Box(Modifier.width(300.dp)) { Input(app.artistQuery, "查找歌手") { app.artistQuery = it } }
            Action("查找", selected = true, shape = GlassShape.pill) { app.artistPage = 0; app.loadArtists() }
        }
        if (app.artists.isEmpty()) EmptyState(if (app.busy) "正在读取歌手" else "还没有歌手", "请检查已保存的歌曲元数据。", Modifier.weight(1f))
        else LazyVerticalGrid(
            GridCells.Fixed(5),
            state = grid,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(app.artists, key = { it.getString("id") }) { artist -> ArtistCard(app, artist) }
        }
        Pagination(app.artistPage, app.artistTotal, { if (app.artistPage > 0) { app.artistPage--; app.loadArtists() } }, { if ((app.artistPage + 1) * 20 < app.artistTotal) { app.artistPage++; app.loadArtists() } })
    }
}

@Composable private fun Playlists(app: TvApp, grid: LazyGridState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) { Input(app.playlistQuery, "查找歌单") { app.playlistQuery = it } }
            if (app.playlistTotal > 0) {
                Text(
                    "${app.playlistTotal} 个歌单",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .clip(GlassShape.pill)
                        .background(Color(0x12FFFFFF))
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
            Action("查找", selected = true, shape = GlassShape.pill) { app.playlistPage = 0; app.loadPlaylists() }
        }
        if (app.playlists.isEmpty()) EmptyState(if (app.busy) "正在读取歌单" else "还没有歌单", "可在 NAS 管理端的歌单管理中创建，支持 AI 辅助选歌。", Modifier.weight(1f))
        else LazyVerticalGrid(
            GridCells.Fixed(5),
            state = grid,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(app.playlists, key = { it.getString("id") }) { playlist -> PlaylistCard(app, playlist, Modifier.fillMaxWidth()) }
        }
        Pagination(app.playlistPage, app.playlistTotal, { if (app.playlistPage > 0) { app.playlistPage--; app.loadPlaylists() } }, { if ((app.playlistPage + 1) * 20 < app.playlistTotal) { app.playlistPage++; app.loadPlaylists() } })
    }
}

@Composable private fun PlaylistDetail(app: TvApp, list: LazyListState, playlistOverride: JSONObject? = null) {
    val playlist = playlistOverride ?: app.playlistDetail ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).glassSurface(TvShape.panel, base = Glass.surfaceCard).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SongArtwork(app, JSONObject(playlist.toString()).put("revision", playlist.optString("coverVersion")), Modifier.size(62.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(playlist.optString("name"), color = Paper, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.optInt("count")} 首歌曲", color = Brand, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Text(playlist.optString("description").ifBlank { "选择一首想唱的歌" }, color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Action("返回歌单") { app.closePlaylist() }
        }
        if (app.songs.isEmpty()) EmptyState(if (app.busy) "正在读取歌曲" else "歌单暂无可用歌曲", "请在 NAS 管理端选择曲库中的歌曲。", Modifier.weight(1f))
        else SongTable(app, list, Modifier.weight(1f))
        SongPagination(app)
    }
}

@Composable private fun ArtistDetail(app: TvApp, list: LazyListState, artistOverride: JSONObject? = null) {
    val artist = artistOverride ?: app.artistDetail ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 98.dp).clip(TvShape.panel).glassSurface(TvShape.panel, base = Glass.surfaceCard)) {
            ArtistArtwork(app, artist, "background", Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xF20B0D11), Color(0x880B0D11)))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ArtistArtwork(app, artist, "portrait", Modifier.size(60.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(artist.optString("name"), color = Paper, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${artist.optInt("count")} 首歌曲 · ${if (artist.optBoolean("managed")) "曲库歌手" else "曲库原署名"}", color = Brand, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    Text(artist.optString("biography").ifBlank { "歌手信息随歌曲自动同步" }, color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Action("返回歌手") { app.closeArtist() }
            }
        }
        if (app.songs.isEmpty()) EmptyState(if (app.busy) "正在读取歌曲" else "暂无关联歌曲", "可以返回歌手页刷新资料。", Modifier.weight(1f))
        else SongTable(app, list, Modifier.weight(1f))
        SongPagination(app)
    }
}

@Composable private fun Detail(app: TvApp, songOverride: JSONObject? = null) {
    val song = songOverride ?: app.selected ?: return
    fun field(name: String) = song.optString(name).takeIf { it.isNotBlank() && it != "null" }
    val album = listOfNotNull(field("album"), field("releaseDate")).joinToString(" · ")
    val coverShape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .glassSurface(TvShape.panel, base = Glass.surfaceCard, elevation = 6.dp)
                .clip(TvShape.panel),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(248.dp)
                        .shadow(20.dp, coverShape, ambientColor = Glass.shadowFocus, spotColor = Glass.shadowFocus, clip = false),
                ) {
                    SongArtwork(app, song, Modifier.fillMaxSize(), shape = coverShape)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        song.optString("title"),
                        color = Paper,
                        fontSize = 32.sp,
                        lineHeight = 39.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artists(),
                        color = Paper.copy(alpha = 0.86f),
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (album.isNotEmpty()) {
                        Text(
                            album,
                            color = Muted,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (song.optLong("durationMs") > 0) DetailTag(time(song.optLong("durationMs")))
                        DetailTag(versionLabel(song.optString("version")))
                        field("sourceName")?.let { DetailTag("来源 · $it") }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color(0x12FFFFFF)))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Action(
                            "＋ 加入待唱",
                            modifier = Modifier.height(44.dp).width(164.dp),
                            selected = true,
                            focusKey = "加入待唱",
                            textSize = 15.5.sp,
                            shape = GlassShape.pill,
                            fillWidth = true,
                        ) {
                            if (app.page == "首页") {
                                app.addSongFromHomeDetail(song)
                            } else {
                                app.command("ADD", songId = song.getString("id"))
                                app.selected = null
                            }
                        }
                        Action(
                            "返回曲库",
                            modifier = Modifier.height(44.dp),
                            textSize = 14.5.sp,
                            shape = GlassShape.pill,
                        ) { app.selected = null }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailTag(label: String) {
    Text(
        label,
        modifier = Modifier
            .clip(GlassShape.pill)
            .background(Color(0x12FFFFFF))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = Paper.copy(alpha = 0.88f),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable internal fun Queue(app: TvApp, compact: Boolean = false) {
    val fair = app.snapshot.optString("queuePolicy") == "FAIR"
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val waiting = tvWaitingQueue(queue) { it.optString("status") }
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" } ?: queue.firstOrNull { it.optString("status") == "HANDOFF" }
    if (compact) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("接下来", "${waiting.size} 首待唱")
                Spacer(Modifier.weight(1f))
                if (current == null && waiting.isNotEmpty()) Action("开始下一首", selected = true, shape = GlassShape.pill, textSize = 13.sp) { app.command("START") }
            }
            if (waiting.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    EmptyState("暂无待唱歌曲", "去点歌，选一首喜欢的歌。")
                    Action("去点歌", selected = true, shape = GlassShape.pill) { app.navigate("点歌") }
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(waiting, key = { it.getString("id") }) { item -> QueueRow(app, item, waiting.indexOf(item), waiting.size) }
                }
            }
        }
        return
    }
    if (current == null && waiting.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyState("暂无待唱歌曲", "去点歌，选一首喜欢的歌。")
            Action("去点歌", selected = true, shape = GlassShape.pill) { app.navigate("点歌") }
        }
        return
    }
    val heroItem = current ?: waiting.first()
    val heroSong = heroItem.optJSONObject("song")
    val playback = app.snapshot.optJSONObject("playback")
    val heroConfirmed = current != null && playback?.optString("itemId") == current.optString("id") && playback?.optBoolean("confirmed") == true
    val heroStatus = when {
        current == null -> "即将演唱 · 01"
        current.optString("status") == "HANDOFF" && !heroConfirmed -> "等待确认开唱"
        !app.hasConfirmedPlayback -> "准备中"
        else -> stateLabel(app.playerState)
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Left Hero Stage: Currently Playing or Next Up (#01)
        Column(
            Modifier
                .width(284.dp)
                .fillMaxHeight()
                .glassSurface(TvShape.panel, base = Glass.surfaceCard, elevation = 5.dp)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (current != null) LiveEqualizer(app.playing, color = Brand)
                    else Box(Modifier.size(7.dp).background(Brand, CircleShape))
                    Text(heroStatus, color = Paper.copy(alpha = 0.88f), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "${waiting.size} 首待唱",
                    color = Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                )
            }
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val heroShape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(176.dp)
                        .shadow(16.dp, heroShape, ambientColor = Glass.shadowFocus, spotColor = Glass.shadowFocus, clip = false),
                ) {
                    SongArtwork(app, heroSong, Modifier.fillMaxSize(), shape = heroShape)
                }
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        heroSong?.optString("title").orEmpty(),
                        color = Paper,
                        fontSize = 19.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${heroSong?.artists().orEmpty()} · ${heroItem.optString("ownerName", "电视")}",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                current == null -> Action("开始下一首", Modifier.fillMaxWidth().height(40.dp), selected = true, shape = GlassShape.pill, textSize = 14.sp, fillWidth = true) { app.command("START") }
                current.optString("status") == "HANDOFF" && !heroConfirmed -> Action("确认开唱", Modifier.fillMaxWidth().height(40.dp), selected = true, shape = GlassShape.pill, textSize = 14.sp, fillWidth = true) { app.command("START") }
                else -> Action("返回演唱", Modifier.fillMaxWidth().height(40.dp), selected = true, shape = GlassShape.pill, textSize = 14.sp, fillWidth = true) { app.page = "演唱" }
            }
        }
        // Right Stage: Waiting Queue List Panel
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .glassSurface(TvShape.panel, base = Glass.surfaceCard, elevation = 4.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("待唱列表", color = Paper, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${waiting.size} 首",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .clip(GlassShape.pill)
                        .background(Color(0x12FFFFFF))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                if (fair && waiting.size > 1) Action("公平打乱", shape = GlassShape.pill, textSize = 12.5.sp) { app.command("SHUFFLE") }
                if (waiting.isNotEmpty()) Action("清空待唱", danger = true, shape = GlassShape.pill, textSize = 12.5.sp) { app.pendingConfirm = "清空待唱" }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
            if (waiting.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    EmptyState("队列已空", "当前歌曲结束后可继续点歌。")
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(waiting, key = { it.getString("id") }) { item -> QueueRow(app, item, waiting.indexOf(item), waiting.size) }
                }
            }
            Text(
                if (fair) "临时插队仅占一个下一首优先位，唱完恢复轮流 · 选中歌曲按确认键排序或移除" else "选中歌曲按确认键进行置顶、调整顺序或移除",
                color = Muted.copy(alpha = 0.78f),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable private fun QueueRow(app: TvApp, item: JSONObject, index: Int, count: Int) {
    var rowFocused by remember { mutableStateOf(false) }
    var songFocused by remember { mutableStateOf(false) }
    val firstAction = remember { FocusRequester() }
    val id = item.getString("id")
    val song = item.optJSONObject("song")
    Row(
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
            .onFocusChanged { rowFocused = it.hasFocus }
            .focusGroup()
            .clip(TvShape.control)
            .background(if (rowFocused) Color(0x18FFFFFF) else Color(0x0AFFFFFF))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Button(
            onClick = { firstAction.requestFocus() },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .tvFocusTarget("action:queue:$id")
                .onFocusChanged { songFocused = it.isFocused }
                .then(
                    if (songFocused) Modifier.tvPlatinumFocus(true, TvShape.control)
                    else Modifier
                ),
            scale = ButtonDefaults.scale(focusedScale = 1f),
            shape = ButtonDefaults.shape(TvShape.control),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                contentColor = Paper,
                focusedContentColor = Glass.focusInk,
            ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    (index + 1).toString().padStart(2, '0'),
                    Modifier.width(26.dp),
                    color = when {
                        songFocused -> Glass.focusInk
                        index == 0 -> Brand
                        else -> Muted
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                SongArtwork(app, song, Modifier.size(38.dp), shape = RoundedCornerShape(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.optString("title").orEmpty(),
                        color = if (songFocused) Glass.focusInk else Paper,
                        fontSize = 14.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${song?.artists().orEmpty()} · ${item.optString("ownerName", "电视")}点的${if (item.optBoolean("priority")) " · 下一首优先" else ""}",
                        color = when {
                            songFocused -> Glass.focusInkMuted
                            item.optBoolean("priority") -> Brand
                            else -> Muted
                        },
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (rowFocused) {
            val actions = buildList {
                if (app.snapshot.optString("queuePolicy") == "FAIR") {
                    add(if (item.optBoolean("priority")) "取消插队" to "UNTOP" else "优先下一首" to "TOP")
                } else {
                    if (index > 0) { add("置顶" to "TOP"); add("上移" to "UP") }
                    if (index < count - 1) add("下移" to "DOWN")
                }
                add("移除" to "REMOVE")
            }
            actions.forEachIndexed { actionIndex, (label, command) ->
                Action(
                    label,
                    if (actionIndex == 0) Modifier.focusRequester(firstAction) else Modifier,
                    focusKey = "queue:$id:$command",
                    danger = command == "REMOVE",
                ) { app.command(command, id) }
            }
        }
    }
}

@Composable private fun Settings(app: TvApp) {
    var group by remember { mutableStateOf("连接") }
    val updateActionFocus = remember { FocusRequester() }
    LaunchedEffect(app.updatePanelRequested) { if (app.updatePanelRequested > 0) group = "应用更新" }
    LaunchedEffect(group, app.updateDownloading, app.updateFile) {
        if (group == "应用更新" && (app.updateDownloading || app.updateFile != null)) {
            withFrameNanos { }; withFrameNanos { }; updateActionFocus.requestFocus()
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        val settingsGroups = remember { listOf("连接", "播放设置", "聚会管理", "本场参与者", "设备信息", "应用更新") }
        Column(
            Modifier
                .width(196.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            settingsGroups.forEach { label ->
                SettingsMenuItem(label, active = label == group) { group = label }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
            AnimatedContent(
                targetState = group,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    fadeIn(tween(160, delayMillis = 30, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(90, easing = FastOutSlowInEasing))
                },
                label = "settings-panel",
            ) { targetGroup ->
                val isCurrent = targetGroup == group
                Column(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .then(if (isCurrent) Modifier else Modifier.focusProperties { canFocus = false })
                        .glassSurface(TvShape.panel, base = Glass.surfaceCard, elevation = 4.dp)
                        .clip(TvShape.panel)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (targetGroup) {
                    "连接" -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(app.serverName, color = Paper, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.client.base, color = Glass.champagneBright, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(if (app.online) "连接正常" else "网络异常", if (app.online) Success else Danger, pulse = app.online)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
                        SettingActionRow("检查连接", "重新读取服务身份、聚会和播放状态") { Action("检查连接", textSize = 13.sp) { app.connect() } }
                        SettingActionRow("更换 NAS", "忘记这台电视保存的当前设备凭据") { Action("更换 NAS / 忘记连接", danger = true, textSize = 13.sp) { app.pendingConfirm = "更换 NAS" } }
                        SettingActionRow("停止本机播放", "只停止这台电视，不替远程交接自动确认") { Action("停止本机播放", textSize = 13.sp) { app.stopLocal() } }
                    }
                    "聚会管理" -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("聚会管理", color = Paper, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (app.snapshot.optBoolean("acceptingRequests")) "当前允许亲友点歌" else "当前已暂停接单", color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp)
                            }
                            StatusPill(
                                if (app.snapshot.optString("status") == "ENDED") "聚会已结束" else if (app.snapshot.optBoolean("acceptingRequests")) "接单中" else "已暂停接单",
                                if (app.snapshot.optBoolean("acceptingRequests")) Success else Glass.champagneGold,
                                pulse = app.snapshot.optBoolean("acceptingRequests"),
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
                        if (app.snapshot.optString("status") == "ENDED") Action("开始新聚会", selected = true, shape = GlassShape.pill) { app.newSession() }
                        else {
                            SettingActionRow("亲友点歌", "暂停后保留现有队列，不再接收新点歌") {
                                Action(if (app.snapshot.optBoolean("acceptingRequests")) "暂停接单" else "恢复接单", textSize = 13.sp) { app.command(if (app.snapshot.optBoolean("acceptingRequests")) "LOCK" else "UNLOCK") }
                            }
                            SettingActionRow("结束这场聚会", "停止接单并使邀请与参与授权失效") { Action("结束聚会", danger = true, textSize = 13.sp) { app.pendingConfirm = "散场" } }
                        }
                    }
                    "播放设置" -> PlaybackSettings(app)
                    "应用更新" -> {
                        val updateFailed = app.updateStatus.contains("失败") || app.updateStatus.contains("不可用")
                        val badgeLabel = when {
                            app.updateChecking -> "正在检查"
                            app.updateDownloading -> "正在下载"
                            app.updateFile != null -> "已就绪 · 待安装"
                            app.updateAvailable -> "发现新版本"
                            updateFailed -> "暂未获取更新"
                            else -> "已是最新版本"
                        }
                        val badgeColor = when {
                            app.updateChecking || app.updateDownloading || app.updateAvailable -> Glass.champagneGold
                            updateFailed -> Danger
                            else -> Success
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(Color(0x14FFFFFF))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                HomeGlyph("brand", Modifier.size(28.dp), Paper)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("SingVerse TV", color = Paper, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "当前版本 v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}",
                                    color = Glass.champagneBright,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            StatusPill(badgeLabel, badgeColor, pulse = app.updateChecking || app.updateDownloading)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
                        if (app.updateStatus.isNotBlank()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(TvShape.control)
                                    .background(if (updateFailed) Color(0x16E85668) else Color(0x0EFFFFFF))
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(if (updateFailed) Danger else Glass.champagneGold, CircleShape)
                                )
                                Text(
                                    app.updateStatus,
                                    color = Paper.copy(alpha = 0.92f),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UpdateMetaTile("安装来源", "当前已连接的 NAS", Modifier.weight(1f))
                            UpdateMetaTile("检查策略", "连接自动检查 · 每 24h 复查", Modifier.weight(1f))
                            UpdateMetaTile("安装方式", "校验后由系统确认安装", Modifier.weight(1f))
                        }
                        app.updateRelease?.let { release ->
                            if (release.notes.isNotBlank()) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(TvShape.control)
                                        .background(Color(0x0AFFFFFF))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("更新说明", color = Glass.champagneBright, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                                    Text(release.notes, color = Paper.copy(alpha = 0.9f), fontSize = 13.5.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (app.updateDownloading) {
                                val percent = (100 * app.updateDownloaded / release.size.coerceAtLeast(1L)).coerceIn(0, 100)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(TvShape.control)
                                        .background(Color(0x0CFFFFFF))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("正在下载更新包", color = Paper, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                                        Text("$percent% · ${app.updateDownloaded / 1048576} / ${release.size / 1048576} MB", color = Glass.champagneBright, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x18FFFFFF), GlassShape.pill)) {
                                        Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().background(Glass.champagneGold, GlassShape.pill))
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Action(
                                if (app.updateChecking) "正在检查…" else "检查更新",
                                selected = app.updateFile == null && !app.updateAvailable,
                                focusKey = "check-update",
                                enabled = !app.updateDownloading,
                                textSize = 13.5.sp,
                                shape = GlassShape.pill,
                            ) { app.checkUpdate() }
                            if (app.updateDownloading) {
                                Action("取消下载", Modifier.focusRequester(updateActionFocus), textSize = 13.5.sp, shape = GlassShape.pill) { app.cancelUpdateDownload() }
                            } else if (app.updateAvailable && app.updateFile == null) {
                                Action("下载并校验", selected = true, textSize = 13.5.sp, shape = GlassShape.pill) { app.downloadUpdate() }
                                Action("跳过此版本", quiet = true, textSize = 13.5.sp, shape = GlassShape.pill) { app.skipUpdate() }
                            }
                            if (app.updateFile != null) {
                                Action("确认安装", Modifier.focusRequester(updateActionFocus), selected = true, textSize = 13.5.sp, shape = GlassShape.pill) { app.installUpdate() }
                            }
                        }
                    }
                    "设备信息" -> {
                        val tierLabel = when (GlassTier.current) {
                            GlassTier.LIQUID -> "Liquid 硬件毛玻璃 (API 33+)"
                            GlassTier.FROSTED -> "Frosted 轻量毛玻璃 (API 31+)"
                            GlassTier.LEGACY -> "Legacy 高性能哑光 (API 21+)"
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(Color(0x14FFFFFF))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                HomeGlyph("brand", Modifier.size(28.dp), Paper)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    android.os.Build.MODEL,
                                    color = Paper,
                                    fontSize = 20.sp,
                                    lineHeight = 25.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "用于确认播放终端与系统环境",
                                    color = Muted,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                            StatusPill("Android ${android.os.Build.VERSION.RELEASE}", Glass.champagneGold)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UpdateMetaTile("设备型号", android.os.Build.MODEL, Modifier.weight(1f))
                            UpdateMetaTile("系统环境", "Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UpdateMetaTile("应用版本", "SingVerse TV v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}", Modifier.weight(1f))
                            UpdateMetaTile("播放引擎", "Media3 · 直放 / 兼容播放", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UpdateMetaTile("界面渲染策略", tierLabel, Modifier.weight(1f))
                            UpdateMetaTile("兼容基线", "Android 5.0+ (minSdk 21)", Modifier.weight(1f))
                        }
                    }
                    else -> {
                        Heading("本场参与者", "主持权限只在电视上授予；撤销后可重新扫码加入。")
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
                        if (app.participants.isEmpty()) EmptyState("还没有亲友加入", "通过「手机点歌」邀请亲友。")
                        app.participants.forEach { participant ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(TvShape.control)
                                    .background(Color(0x0BFFFFFF))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(participant.optString("name"), color = Paper, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (participant.optBoolean("revoked")) "已撤销" else "已加入本场", color = if (participant.optBoolean("revoked")) Muted else Success, fontSize = 12.sp)
                                }
                                Action("授予主持", focusKey = "host:${participant.getString("id")}", textSize = 13.sp) { app.command("HOST", participantId = participant.getString("id")) }
                                Spacer(Modifier.width(8.dp))
                                Action("撤销访问", focusKey = "revoke:${participant.getString("id")}", danger = true, textSize = 13.sp) { app.command("REVOKE", participantId = participant.getString("id")) }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private data class TvContentState(
    val key: String,
    val page: String,
    val selected: JSONObject?,
    val playlist: JSONObject?,
    val artist: JSONObject?,
    val home: TvHomeFocusRoutes?,
)

@Composable private fun UpdateMetaTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(TvShape.control)
            .background(Color(0x0AFFFFFF))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = Muted, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Paper, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun SettingsMenuItem(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val memory = LocalTvFocusMemory.current
    val shape = TvShape.control
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .tvFocusTarget("action:$label")
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && memory.directionalInput && !memory.restoring && memory.activeDialogs == 0) onClick()
            }
            .tvSpringFocus(focused, scaleTarget = 1.035f, liftDp = 0.8.dp)
            .then(
                when {
                    focused -> Modifier.tvPlatinumFocus(true, shape)
                    active -> Modifier.clip(shape).background(Color(0x16FFFFFF))
                    else -> Modifier
                }
            ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (active) Paper else Glass.inkSecondary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(16.dp)
                    .background(
                        when {
                            focused && active -> Glass.focusInk
                            active -> Paper
                            else -> Color.Transparent
                        },
                        GlassShape.pill,
                    )
            )
            Text(
                label,
                fontSize = 14.5.sp,
                lineHeight = 20.sp,
                fontWeight = if (active || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable private fun SettingActionRow(title: String, description: String, action: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(TvShape.control)
            .background(Color(0x0BFFFFFF))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Paper, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        action()
    }
}
