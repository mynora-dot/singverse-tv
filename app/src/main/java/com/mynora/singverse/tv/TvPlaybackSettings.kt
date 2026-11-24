package com.mynora.singverse.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.media3.common.C
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

private val PlaybackSettingTabs = listOf(
    "音轨" to "音轨设置",
    "歌词字幕" to "歌词字幕",
    "声音输出" to "声音输出",
    "同步" to "同步设置",
)

/** Shared by the player dialog and Settings; playback operations stay in PlaybackOptions/TvApp. */
@Composable internal fun PlaybackSettings(
    app: TvApp,
    initialTab: String = "音轨",
    audioOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val inDialog = LocalTvFocusMemory.current.activeDialogs > 0
    var tab by remember(initialTab) {
        mutableStateOf(PlaybackSettingTabs.firstOrNull { initialTab == it.first || initialTab == it.second }?.first ?: "音轨")
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
                    "音轨" -> audioTrackSettings(app)
                    "歌词字幕" -> textTrackSettings(app)
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

private fun LazyListScope.audioTrackSettings(app: TvApp) {
    val options = app.playbackOptions
    val audio = options.tracks.filter { it.type == C.TRACK_TYPE_AUDIO }
    section("audio", "可用音轨", "选择音轨后试听；仅显示本机可播放的音轨。")
    if (audio.isEmpty()) item("audio:empty") {
        PlaybackEmptyCard(
            title = "开始播放后可选择音轨",
            description = "当前暂无正在播放的歌曲；开唱后这里会显示本机支持的音轨、声道及原伴唱切换选项。",
        )
    }
    items(audio, key = { "audio:${it.group}:${it.index}" }) { track ->
        PlaybackChoice(
            track.label, Modifier.fillMaxWidth(), selected = track.selected,
            focusKey = "playback:audio:${track.group}:${track.index}",
        ) { options.selectTrack(track) }
    }
    if (audio.any { it.selected && it.channels == 2 }) {
        section("channel", "声道", "分别试听左、右声道，或保留立体声。")
        items(listOf("STEREO" to "立体声", "LEFT" to "左声道", "RIGHT" to "右声道", "MONO" to "混合单声道"), key = { "channel:${it.first}" }) { (mode, label) ->
            PlaybackChoice(
                label, Modifier.fillMaxWidth(), selected = options.channel == mode,
                focusKey = "playback:channel:$mode",
            ) { options.selectChannel(mode) }
        }
    }
    if (audio.isNotEmpty()) {
        section("roles", "原唱 / 伴奏标记", "先选择并试听音轨，再标记当前轨道。演唱时可在主控制栏切换。")
        item("roles:status") {
            SettingNote("原唱${if (options.roleOriginal.isBlank()) "未标记" else "已标记"} · 伴奏${if (options.roleBacking.isBlank()) "未标记" else "已标记"}")
        }
        item("roles:original") {
            PlaybackActionRow("当前标记为原唱", "设为默认原唱轨道", focusKey = "playback:role:original") { app.saveAudioRole(true) }
        }
        item("roles:backing") {
            PlaybackActionRow("当前标记为伴奏", "设为默认伴奏轨道", focusKey = "playback:role:backing") { app.saveAudioRole(false) }
        }
    }
}

private fun LazyListScope.textTrackSettings(app: TvApp) {
    val options = app.playbackOptions
    section("text", "歌词与字幕来源", options.textStatus.ifBlank { "开始播放后读取歌词与字幕。" })
    items(listOf("关闭", "内嵌字幕") + (options.sidecars.map { it.first } + options.plainLyrics.map { it.first }).distinct(), key = { "text:source:$it" }) { name ->
        PlaybackChoice(
            name, Modifier.fillMaxWidth(), selected = options.textSelection == name,
            focusKey = "playback:text:source:$name",
        ) { options.chooseText(name) }
    }
    val embedded = options.tracks.filter { it.type == C.TRACK_TYPE_TEXT }
    if (embedded.isNotEmpty()) {
        section("embedded", "内嵌字幕轨道")
        items(embedded, key = { "text:track:${it.group}:${it.index}" }) { track ->
            PlaybackChoice(
                track.label, Modifier.fillMaxWidth(), selected = track.selected && options.textSelection == "内嵌字幕",
                focusKey = "playback:text:track:${track.group}:${track.index}",
            ) { options.selectTrack(track) }
        }
    }
    item("text:reload") {
        PlaybackActionRow("重新读取歌词 / 字幕", "刷新当前曲目的外挂与内嵌歌词轨", focusKey = "playback:text:reload") { app.reloadText() }
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
    section("volume", "应用音量", "只调整应用音量，不等同于电视或功放系统音量。")
    item("volume:stepper") {
        SettingStepper(
            "应用音量", "${options.volume}%", "playback:volume:down", "playback:volume:up",
            decreaseEnabled = options.volume > 0, increaseEnabled = options.volume < 100,
            onDecrease = { options.changeVolume(options.volume - 10) }, onIncrease = { options.changeVolume(options.volume + 10) },
        )
    }
    section("pitch", "音调", "范围 ±6 半音，保持原播放速度。")
    item("pitch:stepper") {
        SettingStepper(
            "音调", "${if (options.semitones > 0) "+" else ""}${options.semitones} 半音", "playback:pitch:down", "playback:pitch:up",
            decreaseEnabled = options.semitones > -6, increaseEnabled = options.semitones < 6,
            onDecrease = { options.setPitch(options.semitones - 1) }, onIncrease = { options.setPitch(options.semitones + 1) },
        )
    }
    item("pitch:reset") {
        PlaybackActionRow("恢复原调", "将音调重置为 0 半音", focusKey = "playback:pitch:reset") { options.setPitch(0) }
    }
}

private fun LazyListScope.synchronizationSettings(app: TvApp, audioOnly: Boolean) {
    val options = app.playbackOptions
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
    section("lyric-offset", "外置歌词偏移", "正数延后显示，负数提前显示；内嵌字幕使用媒体自身时间轴。")
    item("lyric-offset:stepper") {
        SettingStepper(
            "歌词偏移", "${options.lyricOffset} ms", "playback:lyric-offset:earlier", "playback:lyric-offset:later",
            decreaseEnabled = options.lyricOffset > -5000, increaseEnabled = options.lyricOffset < 5000,
            onDecrease = { options.offset(options.lyricOffset - 100) }, onIncrease = { options.offset(options.lyricOffset + 100) },
        )
    }
    section("audio-delay", "PCM 音频延迟", "范围 0–500ms；按实际音响效果调节。")
    item("audio-delay:stepper") {
        SettingStepper(
            "音频延迟", "${options.audioDelay} ms", "playback:audio-delay:earlier", "playback:audio-delay:later",
            decreaseEnabled = options.audioDelay > 0, increaseEnabled = options.audioDelay < 500,
            onDecrease = { options.delayAudio(options.audioDelay - 50) }, onIncrease = { options.delayAudio(options.audioDelay + 50) },
        )
    }
    item("sync:reset") {
        PlaybackActionRow("恢复同步默认值", "将歌词偏移与音频延迟归零", focusKey = "playback:sync:reset") { options.offset(0); options.delayAudio(0) }
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

@Composable private fun PlaybackActionRow(
    title: String,
    subtitle: String = "",
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
            .heightIn(min = 44.dp)
            .tvFocusTarget("action:$focusKey")
            .onFocusChanged { focused = it.isFocused }
            .tvSpringFocus(isLit, scaleTarget = 1.015f, liftDp = 0.8.dp)
            .then(
                if (isLit) Modifier.tvPlatinumFocus(true, shape)
                else Modifier.clip(shape).background(Color(0x0AFFFFFF))
            ),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Paper,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Glass.focusInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = if (isLit) Glass.focusInk else Paper, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = if (isLit) Glass.focusInkMuted else Muted, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("执行", color = if (isLit) Glass.focusInk else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun PlaybackEmptyCard(title: String, description: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(TvShape.control)
            .background(Color(0x0AFFFFFF))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            MusicMark(Modifier.size(20.dp), tint = Paper.copy(alpha = 0.85f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Paper, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun SettingStepper(
    label: String,
    value: String,
    decreaseKey: String,
    increaseKey: String,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(TvShape.control)
            .background(Color(0x0BFFFFFF))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Paper, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            value,
            color = Glass.champagneBright,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Action("−", focusKey = decreaseKey, enabled = decreaseEnabled, onClick = onDecrease)
        Spacer(Modifier.width(6.dp))
        Action("＋", focusKey = increaseKey, enabled = increaseEnabled, onClick = onIncrease)
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
