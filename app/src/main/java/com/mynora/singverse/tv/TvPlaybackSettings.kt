package com.mynora.singverse.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

private val PlaybackSettingTabs = listOf(
    "声音输出" to "声音输出",
    "同步" to "同步设置",
)

/** Shared by the player dialog and Settings; playback operations stay in PlaybackOptions/TvApp. */
@Composable internal fun PlaybackSettings(
    app: TvApp,
    initialTab: String = "声音输出",
    audioOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val inDialog = LocalTvFocusMemory.current.activeDialogs > 0
    var tab by remember(initialTab) {
        mutableStateOf(PlaybackSettingTabs.firstOrNull { initialTab == it.first || initialTab == it.second }?.first ?: "声音输出")
    }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = if (inDialog) 340.dp else 400.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            itemsIndexed(PlaybackSettingTabs, key = { _, item -> item.first }) { index, (value, label) ->
                PlaybackTabChip(
                    label,
                    Modifier.focusProperties {
                        if (inDialog && index == 0) left = FocusRequester.Cancel
                        if (inDialog && index == PlaybackSettingTabs.lastIndex) right = FocusRequester.Cancel
                    },
                    selected = tab == value,
                    focusKey = "playback:tab:$value",
                ) { tab = value }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            transitionSpec = {
                fadeIn(tween(160, delayMillis = 30, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(90, easing = FastOutSlowInEasing))
            },
            label = "playback-settings-tab",
        ) { targetTab ->
            val isCurrent = targetTab == tab
            LazyColumn(
                modifier = if (isCurrent) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().focusProperties { canFocus = false },
                state = rememberLazyListState(),
                contentPadding = PaddingValues(top = 2.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (targetTab) {
                    "声音输出" -> outputSettings(app)
                    "同步" -> {
                        item("party:effects") {
                            PlaybackToggleRow(
                                title = "亲友全屏互动特效",
                                subtitle = "亲友在手机发送互动时，电视端叠加全屏动态氛围效果",
                                enabled = app.partyEffectsEnabled,
                                focusKey = "playback:party:effects",
                            ) { app.setPartyEffects(!app.partyEffectsEnabled) }
                        }
                        synchronizationSettings(app, audioOnly)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.outputSettings(app: TvApp) {
    val options = app.playbackOptions
    section(
        "output",
        "声音输出",
        if (options.canSelectOutput) "优先输出：${options.route}。实际输出由电视系统协商。"
        else "使用电视系统默认输出；切换 HDMI、蓝牙或其他设备请在电视系统设置中操作。",
    )
    item("output:default") {
        PlaybackChoice(
            "系统默认输出", Modifier.fillMaxWidth(), selected = options.route == "系统默认",
            focusKey = "playback:output:default",
        ) { options.chooseRoute(null) }
    }
    items(options.routes, key = { "output:route:${it.id}" }) { route ->
        PlaybackChoice(
            route.name, Modifier.fillMaxWidth(), selected = options.route == route.name,
            focusKey = "playback:output:${route.id}",
        ) { options.chooseRoute(route.id) }
    }
}

private fun LazyListScope.synchronizationSettings(app: TvApp, audioOnly: Boolean) {
    if (!audioOnly) {
        section("compatibility", "播放兼容性", "老电视兼容模式最高 720p，优先使用 H.264 和双声道音频；需要时由 NAS 转换。")
        item("compatibility:auto") {
            PlaybackChoice(
                "自动检测", Modifier.fillMaxWidth(), selected = !app.compatiblePlayback,
                focusKey = "playback:compatibility:auto",
            ) { app.changeCompatibility(false) }
        }
        item("compatibility:legacy") {
            PlaybackChoice(
                "老电视兼容", Modifier.fillMaxWidth(), selected = app.compatiblePlayback,
                focusKey = "playback:compatibility:legacy",
            ) { app.changeCompatibility(true) }
        }
        section("quality", "视频画质", if (app.compatiblePlayback) "兼容模式最高 720p；切换后沿用当前歌曲与播放进度。" else "切换后沿用当前歌曲与播放进度。")
        items(if (app.compatiblePlayback) listOf("auto", "720p", "480p") else listOf("auto", "1080p", "720p", "480p"), key = { "quality:$it" }) { quality ->
            PlaybackChoice(
                if (quality == "auto") "自动画质" else quality, Modifier.fillMaxWidth(), selected = app.playbackQuality == quality,
                focusKey = "playback:quality:$quality",
            ) { app.changeQuality(quality) }
        }
    }
}

@Composable private fun PlaybackTabChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean,
    focusKey: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val memory = LocalTvFocusMemory.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val shape = GlassShape.pill
    Button(
        onClick = onClick,
        modifier = modifier
            .height(34.dp)
            .tvFocusTarget("action:$focusKey")
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && memory.directionalInput && !memory.restoring) onClick()
            }
            .tvSpringFocus(focused && windowFocused, scaleTarget = 1.04f, liftDp = 0.5.dp)
            .then(
                when {
                    focused && windowFocused -> Modifier.tvPlatinumFocus(true, shape)
                    selected -> Modifier.clip(shape).background(Color(0x1EFFFFFF))
                    else -> Modifier
                }
            )
            .semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Paper else Glass.inkSecondary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
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

@Composable private fun PlaybackChoice(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean,
    focusKey: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val isLit = focused && windowFocused
    val shape = TvShape.control
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 42.dp)
            .tvFocusTarget("action:$focusKey")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(isLit, scaleTarget = 1.015f, liftDp = 0.8.dp)
            .then(
                when {
                    isLit -> Modifier.tvPlatinumFocus(true, shape)
                    selected -> Modifier.clip(shape).background(Color(0x16FFFFFF))
                    else -> Modifier.clip(shape).background(Color(0x0AFFFFFF))
                }
            )
            .semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Paper else Paper.copy(alpha = 0.84f),
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                label,
                Modifier.weight(1f),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = if (selected || isLit) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isLit) Glass.focusInk.copy(alpha = 0.14f) else Color(0x24FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeGlyph("check", Modifier.size(11.dp), if (isLit) Glass.focusInk else Paper)
                }
            }
        }
    }
}

@Composable private fun PlaybackToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    focusKey: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val isLit = focused && windowFocused
    val shape = TvShape.control
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .tvFocusTarget("action:$focusKey")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(isLit, scaleTarget = 1.015f, liftDp = 0.8.dp)
            .then(
                if (isLit) Modifier.tvPlatinumFocus(true, shape)
                else Modifier.clip(shape).background(Color(0x0BFFFFFF))
            )
            .semantics { this.selected = enabled },
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = if (isLit) Glass.focusInk else Paper, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = if (isLit) Glass.focusInkMuted else Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier
                    .clip(GlassShape.pill)
                    .background(
                        when {
                            isLit && enabled -> Color(0x1F0C0F14)
                            isLit -> Color(0x120C0F14)
                            enabled -> Color(0x2034C759)
                            else -> Color(0x12FFFFFF)
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            isLit -> Color(0x240C0F14)
                            enabled -> Color(0x4534C759)
                            else -> Color(0x16FFFFFF)
                        },
                        GlassShape.pill,
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (enabled) Success else if (isLit) Glass.focusInkMuted else Muted, CircleShape)
                )
                Text(
                    if (enabled) "已开启" else "已关闭",
                    color = if (isLit) Glass.focusInk else if (enabled) Paper else Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun LazyListScope.section(key: String, title: String, description: String = "") {
    item("section:$key") {
        Column(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Paper, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            if (description.isNotBlank()) SettingNote(description)
        }
    }
}

@Composable private fun SettingNote(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
