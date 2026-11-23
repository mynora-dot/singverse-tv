package com.mynora.singverse.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LoginInk = Ink
private val LoginPanel = Glass.surface
private val Ivory = Paper
private val LoginMuted = Muted

@Composable internal fun TvLoginScreen(app: TvApp) {
    val state = app.login
    DisposableEffect(state.page, app.foreground) {
        if (state.page == LoginPage.QR && app.foreground) state.resumePairing()
        onDispose { if (state.page == LoginPage.QR && !app.connected) state.pausePairing() }
    }
    BackHandler(enabled = state.page != LoginPage.METHODS || state.error.isNotBlank() || app.error.isNotBlank()) {
        when {
            app.error.isNotBlank() -> app.error = ""
            state.error.isNotBlank() -> state.clearError()
            else -> state.back()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            Box(Modifier.fillMaxSize().background(LoginInk)) {
                AmbientStage(accent = true)
                Row(Modifier.fillMaxWidth().padding(horizontal = 62.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoginIcon("brand", Modifier.size(26.dp), Primary); Spacer(Modifier.width(8.dp))
                    Text("SingVerse", color = Ivory, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (state.page != LoginPage.QR) Text(when (state.page) { LoginPage.METHODS -> "连接你的音乐"; LoginPage.MANUAL -> "账号登录"; LoginPage.DISCOVERY -> "局域网搜索 NAS"; LoginPage.HISTORY -> "最近登录"; LoginPage.QR -> "" }, color = LoginMuted, fontSize = 12.sp)
                }
                key(state.page, state.mfaRequired) {
                    when (state.page) {
                        LoginPage.QR -> LoginQr(state)
                        LoginPage.METHODS -> LoginMethods(state)
                        LoginPage.MANUAL -> LoginForm(state)
                        LoginPage.DISCOVERY -> LoginDiscovery(state, app.discovery)
                        LoginPage.HISTORY -> LoginHistory(state)
                    }
                }
                if (state.page != LoginPage.QR) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("方向键  切换", color = LoginMuted, fontSize = 11.sp)
                    Text("OK  确认选择", color = LoginMuted, fontSize = 11.sp)
                    Text(if (state.page == LoginPage.METHODS) "返回键  退出" else "返回键  返回", color = LoginMuted, fontSize = 11.sp)
                }
                if (state.page !in listOf(LoginPage.METHODS, LoginPage.QR)) LoginButton(
                    Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 44.dp).width(160.dp).height(39.dp),
                    quiet = true, onClick = { state.open(LoginPage.METHODS) }
                ) { Text("返回登录方式", color = LoginMuted, fontSize = 15.sp) }
            }
        }
    }
}

@Composable private fun LoginTitle(title: String, subtitle: String) {
    Text(title, color = Ivory, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    Text(subtitle, color = LoginMuted, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
}

@Composable private fun LoginButton(modifier: Modifier = Modifier, primary: Boolean = false, quiet: Boolean = false, enabled: Boolean = true, padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp), onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.onFocusChanged { focused = it.isFocused }.tvFocus(focused, GlassShape.control), contentPadding = padding,
        shape = ButtonDefaults.shape(GlassShape.control), scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(containerColor = if (quiet) Color.Transparent else if (primary) Primary else Glass.surface, contentColor = if (primary) LoginInk else Ivory,
            focusedContainerColor = if (quiet) Glass.surface else if (primary) Glass.surfaceFocus else Glass.surfaceHover, focusedContentColor = Ivory,
            disabledContainerColor = Glass.surface.copy(alpha = Glass.surface.alpha * .4f), disabledContentColor = LoginMuted)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable private fun InitialFocus(requester: FocusRequester, ready: Boolean = true) {
    LaunchedEffect(requester, ready) { if (ready) requester.requestFocus() }
}

/** A: selecting phone login immediately shows the local QR, with no NAS form. */
@Composable private fun BoxScope.LoginQr(state: TvLogin) {
    val other = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val recoverable = state.pairingStage in listOf(PairingStage.EXPIRED, PairingStage.OFFLINE, PairingStage.FAILED)
    val showQr = state.qrUrl.isNotEmpty() && state.pairingStage in listOf(PairingStage.WAITING, PairingStage.SCANNED)
    LaunchedEffect(recoverable) { if (recoverable) retry.requestFocus() else other.requestFocus() }
    Row(Modifier.align(Alignment.TopStart).padding(start = 67.dp, top = 143.dp).width(826.dp), horizontalArrangement = Arrangement.spacedBy(67.dp)) {
        Column(Modifier.width(490.dp).padding(top = 24.dp)) {
            Text("手机一扫，\n音乐上屏。", color = Ivory, fontSize = 46.sp, lineHeight = 59.sp, fontWeight = FontWeight.SemiBold)
            Text("打开 SingVerse 手机 App 扫码\n连接 App 当前的音乐库", color = LoginMuted, fontSize = 16.sp, lineHeight = 29.sp, modifier = Modifier.padding(top = 26.dp))
        }
        Column(Modifier.width(269.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("手机扫码登录", color = Ivory, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.padding(top = 16.dp).size(212.dp).glassSurface(TvShape.card, base = if (showQr) Color.White else null), contentAlignment = Alignment.Center) {
                if (showQr) Qr(state.qrUrl, Modifier.fillMaxSize(), "用 SingVerse 手机 App 扫描此码登录电视")
                else Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoginIcon(if (state.pairingStage in listOf(PairingStage.SCANNED, PairingStage.CONNECTING)) "user" else "qr", Modifier.size(29.dp), LoginMuted)
                    Text(when (state.pairingStage) {
                        PairingStage.SCANNED -> "已扫码"
                        PairingStage.CONNECTING -> "正在连接"
                        PairingStage.CONNECTED -> "连接成功"
                        PairingStage.EXPIRED -> "二维码已失效"
                        PairingStage.OFFLINE -> "检查电视网络"
                        PairingStage.FAILED -> "连接未完成"
                        else -> "正在生成二维码"
                    }, color = Ivory, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(when (state.pairingStage) {
                        PairingStage.SCANNED -> "请在 App 中确认\n连接这台电视"
                        PairingStage.CONNECTING -> "正在打开你的音乐库"
                        PairingStage.EXPIRED -> "刷新后重新扫码"
                        PairingStage.OFFLINE -> "连接 Wi-Fi 或有线网络"
                        PairingStage.FAILED -> "刷新二维码后重试"
                        else -> "稍候片刻"
                    }, color = LoginMuted, fontSize = 13.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Text(when (state.pairingStage) {
                PairingStage.WAITING -> "等待扫码"
                PairingStage.SCANNED -> "已扫码，请在 App 中确认"
                PairingStage.CONNECTING -> "请保持手机与电视在同一网络"
                PairingStage.EXPIRED -> "二维码仅限本次登录使用"
                PairingStage.OFFLINE, PairingStage.FAILED -> state.pairingMessage
                else -> ""
            }, color = LoginMuted, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 15.dp))
            if (recoverable) LoginButton(Modifier.padding(top = 12.dp).width(180.dp).height(39.dp).focusRequester(retry), primary = true, onClick = state::refreshPairing) {
                Text(if (state.pairingStage == PairingStage.OFFLINE) "重试连接" else "刷新二维码", fontSize = 14.sp)
            }
        }
    }
    LoginButton(Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 44.dp).width(160.dp).height(39.dp).focusRequester(other), quiet = true, onClick = { state.open(LoginPage.METHODS) }) {
        Text("返回登录方式", color = LoginMuted, fontSize = 15.sp)
    }
    if (!recoverable) Row(Modifier.align(Alignment.BottomEnd).padding(end = 67.dp, bottom = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text("手机与电视连接同一网络", color = LoginMuted, fontSize = 12.sp)
        Text("OK  确认", color = LoginMuted, fontSize = 12.sp)
    }
}

@Composable private fun BoxScope.LoginMethods(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial)
    val methods = listOf(LoginPage.MANUAL, LoginPage.DISCOVERY, LoginPage.QR)
    Column(Modifier.align(Alignment.TopStart).padding(start = 67.dp, top = 119.dp).width(826.dp)) {
        Text("让你的音乐，登上大屏。", color = Ivory, fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold)
        Text("选择一种方式，连接你的音乐库", color = LoginMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 34.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(
                Triple("user", "账号登录", "使用 NAS 地址和管理员账号"),
                Triple("nas", "局域网搜索 NAS", "查找同一网络中的音乐库"),
                Triple("qr", "手机扫码登录", "使用已登录的手机 App")
            ).forEachIndexed { index, item ->
                LoginButton(Modifier.weight(1f).height(166.dp).then(if (methods[index] == state.selectedMethod) Modifier.focusRequester(initial) else Modifier),
                    padding = PaddingValues(22.dp), onClick = { state.open(methods[index]) }) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        LoginIcon(item.first, Modifier.size(29.dp))
                        Column {
                            Text(item.second, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                            Text(item.third, color = LoginMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        if (state.recent.isNotEmpty()) LoginButton(Modifier.padding(top = 20.dp).fillMaxWidth().height(42.dp), onClick = { state.open(LoginPage.HISTORY) }) {
            Text("最近登录", color = LoginMuted, fontSize = 13.sp); Spacer(Modifier.width(18.dp))
            Text(state.recent.first().name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("查看全部（${state.recent.size}）", color = LoginMuted, fontSize = 13.sp)
        }
    }
}

@Composable private fun BoxScope.LoginIntro(title: String, subtitle: String) {
    Column(Modifier.align(Alignment.TopStart).padding(start = 67.dp, top = 156.dp).width(355.dp)) {
        Text(title, color = Ivory, fontSize = 42.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = LoginMuted, fontSize = 15.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 25.dp))
    }
}

@Composable private fun BoxScope.LoginForm(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial, !state.busy)
    LoginIntro(if (state.mfaRequired) "再一步，\n音乐即将响起。" else "登录 NAS，\n打开音乐库。",
        if (state.mfaRequired) "使用认证器验证码或恢复码\n完成这次登录" else "使用你的 NAS 管理员账号\n连接收藏的每一首歌")
    Column(Modifier.align(Alignment.TopEnd).padding(end = 67.dp, top = if (state.mfaRequired) 166.dp else 116.dp).width(418.dp)) {
        if (!state.mfaRequired) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NAS 连接", color = LoginMuted, fontSize = 13.sp); Spacer(Modifier.weight(1f))
                Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("http", "https").forEach { scheme ->
                        LoginButton(Modifier.width(70.dp).height(30.dp), primary = state.protocol == scheme, enabled = !state.busy, padding = PaddingValues(0.dp), onClick = { state.protocol = scheme }) {
                            Text(scheme.uppercase(Locale.ROOT), fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LoginField("NAS 地址", state.host, "输入 NAS 地址和端口", Modifier.focusRequester(initial), enabled = !state.busy) { address ->
                if (address.contains("://")) runCatching { state.setAddress(address) }.onFailure { state.host = address } else state.host = address
            }
            Text(state.serverName.ifBlank { "管理员账号" }, color = LoginMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 19.dp, bottom = 9.dp))
            LoginField("账号", state.username, "请输入账号", enabled = !state.busy) { state.username = it }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LoginField("密码", state.password, "请输入密码", Modifier.weight(1f), secret = !state.showPassword, enabled = !state.busy) { state.password = it }
                LoginButton(Modifier.padding(start = 8.dp).size(50.dp).semantics { contentDescription = if (state.showPassword) "隐藏密码" else "显示密码" }, enabled = !state.busy, onClick = { state.showPassword = !state.showPassword }) {
                    LoginIcon(if (state.showPassword) "eyeOff" else "eye", Modifier.size(20.dp))
                }
            }
        } else {
            Text("二次验证", color = LoginMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            LoginField("验证码", state.mfaCode, "验证码或恢复码", Modifier.focusRequester(initial), secret = true, enabled = !state.busy) { state.mfaCode = it }
        }
        LoginButton(Modifier.fillMaxWidth().padding(top = 18.dp).height(44.dp), primary = true, enabled = !state.busy, onClick = { state.submit() }) {
            Text(if (state.busy) "正在连接…" else if (state.mfaRequired) "验证并登录" else "连接并登录", fontSize = 15.sp)
        }
        Text(state.error.ifBlank { if (state.mfaRequired) "返回登录方式可重新开始" else "按 OK 编辑，完成后连接音乐库" },
            color = if (state.error.isBlank()) LoginMuted else Danger, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable private fun LoginField(label: String, value: String, hint: String, modifier: Modifier = Modifier, secret: Boolean = false, enabled: Boolean = true, change: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var restoreFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val dialogDensity = LocalDensity.current
    fun closeEditor(save: Boolean) {
        if (save) change(draft)
        draft = ""; editing = false; restoreFocus = true
    }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            withFrameNanos { }
            returnFocus.requestFocus()
            restoreFocus = false
        }
    }
    LoginButton(modifier.fillMaxWidth().height(50.dp).focusRequester(returnFocus), enabled = enabled, onClick = { draft = value; editing = true }) {
        Text(label, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        Text(if (value.isBlank()) hint else if (secret) "•".repeat(value.length.coerceAtMost(16)) else value, color = if (value.isBlank()) LoginMuted else Ivory, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    if (editing) Dialog(onDismissRequest = { closeEditor(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides dialogDensity) {
            val keyboard = LocalSoftwareKeyboardController.current
            Column(Modifier.width(450.dp).glassSurface(GlassShape.dialog, highlighted = true, base = Glass.surfaceDialog).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, color = Ivory, fontSize = 23.sp)
                BasicTextField(draft, { draft = it.take(256) }, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                        keyboard?.hide(); doneFocus.requestFocus(); true
                    } else false
                }.background(LoginInk, RoundedCornerShape(10.dp)).padding(14.dp),
                    textStyle = TextStyle(color = Ivory, fontSize = 19.sp), singleLine = true, cursorBrush = SolidColor(Ivory),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (label == "NAS 地址") KeyboardType.Uri else KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); closeEditor(true) }))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoginButton(Modifier.weight(1f).height(42.dp), onClick = { keyboard?.hide(); closeEditor(false) }) { Text("取消") }
                    LoginButton(Modifier.weight(1f).height(42.dp).focusRequester(doneFocus), primary = true, onClick = { keyboard?.hide(); closeEditor(true) }) { Text("完成") }
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus(); withFrameNanos { }; keyboard?.show() }
        }
    }
}

@Composable private fun BoxScope.LoginDiscovery(state: TvLogin, discovery: NasDiscovery) {
    val initial = remember { FocusRequester() }; InitialFocus(initial)
    val services = discovery.services
    LoginIntro("音乐库，\n就在身边。", "电视与 NAS 连接同一网络\n找到后，使用账号登录")
    Column(Modifier.align(Alignment.TopEnd).padding(end = 67.dp, top = 128.dp).width(418.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("局域网搜索 NAS", color = Ivory, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(if (discovery.searching) "正在搜索" else if (services.isEmpty()) "搜索结束" else "${services.size} 台 NAS", color = LoginMuted, fontSize = 12.sp)
        }
        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(224.dp), contentAlignment = Alignment.Center) {
            if (services.isEmpty()) Column(Modifier.fillMaxSize().glassSurface(TvShape.card).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LoginIcon("nas", Modifier.size(38.dp), LoginMuted)
                Text(if (discovery.searching) "正在搜索局域网…" else "暂未发现 NAS", color = Ivory, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp))
                Text(discovery.status, color = LoginMuted, fontSize = 13.sp, lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                services.forEach { (name, address) ->
                    LoginButton(Modifier.fillMaxWidth().height(78.dp), onClick = { state.select(name, address) }) {
                        LoginIcon("nas", Modifier.size(28.dp)); Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(NasAddress.authority(address), color = LoginMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Text("选择", color = LoginMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoginButton(Modifier.weight(1f).height(44.dp).focusRequester(initial), primary = true, onClick = { discovery.start() }) { Text("重新搜索", fontSize = 15.sp) }
            LoginButton(Modifier.weight(1f).height(44.dp), onClick = { state.open(LoginPage.MANUAL) }) { Text("手动输入地址", fontSize = 15.sp) }
        }
    }
}

@Composable private fun BoxScope.LoginHistory(state: TvLogin) {
    val initial = remember { FocusRequester() }; InitialFocus(initial)
    Column(Modifier.align(Alignment.TopCenter).padding(top = 110.dp).width(580.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LoginTitle("最近登录的设备", "选择熟悉的 NAS，继续连接你的音乐库")
        Column(Modifier.padding(top = 24.dp).height(250.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.recent.forEachIndexed { index, device ->
                LoginButton(Modifier.fillMaxWidth().height(82.dp).then(if (index == 0) Modifier.focusRequester(initial) else Modifier), onClick = { state.select(device.name, device.address, device.username) }) {
                    LoginIcon("nas", Modifier.size(26.dp)); Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.address, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.username.ifBlank { "请使用账号密码登录" }, fontSize = 11.sp)
                    }
                    Text(SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(device.lastLogin)) + "  ›", fontSize = 12.sp)
                }
            }
            if (state.recent.isEmpty()) Text("暂无登录记录", color = LoginMuted, fontSize = 18.sp)
        }
        Text("选择记录后填写密码，完成登录", color = LoginMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable private fun LoginIcon(kind: String, modifier: Modifier, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) androidx.tv.material3.LocalContentColor.current else color
    if (kind == "brand") {
        HomeGlyph(kind, modifier, tint)
        return
    }
    Canvas(modifier) {
        val unit = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, Offset(x * unit, y * unit), Offset(a * unit, b * unit), 1.4f * unit, StrokeCap.Round)
        when (kind) {
            "nas" -> listOf(3f, 14f).forEach { y -> drawRoundRect(tint, Offset(3 * unit, y * unit), Size(18 * unit, 7 * unit), androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(1.4f * unit)); line(6f, y + 3.5f, 11f, y + 3.5f); drawCircle(tint, unit, Offset(17 * unit, (y + 3.5f) * unit)) }
            "user" -> { drawCircle(tint, 4 * unit, Offset(12 * unit, 7 * unit), style = Stroke(1.4f * unit)); drawArc(tint, 180f, 180f, false, Offset(4 * unit, 13 * unit), Size(16 * unit, 16 * unit), style = Stroke(1.4f * unit)) }
            "eye", "eyeOff" -> { drawOval(tint, Offset(2 * unit, 6 * unit), Size(20 * unit, 12 * unit), style = Stroke(1.4f * unit)); drawCircle(tint, 3 * unit, Offset(12 * unit, 12 * unit), style = Stroke(1.4f * unit)); if (kind == "eyeOff") line(3f, 3f, 21f, 21f) }
            else -> { listOf(Offset(3f, 3f), Offset(15f, 3f), Offset(3f, 15f)).forEach { drawRect(tint, Offset(it.x * unit, it.y * unit), Size(6 * unit, 6 * unit), style = Stroke(1.4f * unit)) }; line(16f, 15f, 16f, 21f); line(16f, 21f, 21f, 21f); line(20f, 15f, 21f, 15f) }
        }
    }
}
