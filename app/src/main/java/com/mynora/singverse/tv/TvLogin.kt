package com.mynora.singverse.tv

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Owns only the unconnected login flow; leaving a flow invalidates every delayed response. */
internal class TvLogin(context: Context, private val discovery: NasDiscovery, private val pairingLifetimeMillis: Long = 300_000, private val authorized: suspend (String, String) -> Unit) {
    private val preferences = context.getSharedPreferences("recent-nas", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var admin: LoginHttp? = null
    private var generation = 0
    var page by mutableStateOf(LoginPage.METHODS); private set
    var selectedMethod by mutableStateOf(LoginPage.MANUAL); private set
    var pairingStage by mutableStateOf(PairingStage.STARTING); private set
    var qrUrl by mutableStateOf(""); private set
    var pairingMessage by mutableStateOf(""); private set
    private var pairing: TvPairingServer? = null
    private var pairingTimer: Job? = null
    private var pairingConnection: Deferred<Unit>? = null
    private var pairingGeneration = 0
    var protocol by mutableStateOf("http")
    var host by mutableStateOf("")
    var username by mutableStateOf("admin")
    var password by mutableStateOf("")
    var mfaCode by mutableStateOf("")
    var mfaRequired by mutableStateOf(false); private set
    var showPassword by mutableStateOf(false)
    var serverName by mutableStateOf("") ; private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    private var loginUsername = ""
    var recent by mutableStateOf(readHistory()); private set

    private fun readHistory(): List<RecentNas> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until minOf(array.length(), 10)).mapNotNull { i -> runCatching {
            val item = array.getJSONObject(i)
            RecentNas(NasAddress.normalize(item.getString("address")), item.getString("name").take(80), item.optString("username").take(80), item.getLong("lastLogin"))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    fun recordSuccess(address: String, name: String) {
        val base = NasAddress.normalize(address)
        recent = rememberNas(recent, RecentNas(base, name.ifBlank { serverName }.ifBlank { NasAddress.authority(base) }.take(80), (loginUsername.ifBlank { recent.firstOrNull { it.address == base }?.username.orEmpty() }).take(80), System.currentTimeMillis()))
        preferences.edit().putString("items", JSONArray().apply {
            recent.forEach { put(JSONObject().put("address", it.address).put("name", it.name).put("username", it.username).put("lastLogin", it.lastLogin)) }
        }.toString()).apply()
    }

    fun setAddress(value: String) {
        val base = NasAddress.normalize(value)
        protocol = NasAddress.protocol(base); host = NasAddress.authority(base)
    }

    fun open(destination: LoginPage) {
        cancel()
        if (destination in listOf(LoginPage.MANUAL, LoginPage.DISCOVERY, LoginPage.QR)) selectedMethod = destination
        page = destination
        if (destination == LoginPage.DISCOVERY) discovery.start()
        if (destination == LoginPage.QR) resumePairing()
    }

    fun select(name: String, address: String, account: String = "") {
        cancel()
        setAddress(address); serverName = name; username = account.ifBlank { "admin" }
        page = LoginPage.MANUAL
    }

    fun back(): Boolean {
        if (page == LoginPage.METHODS) return false
        open(LoginPage.METHODS)
        return true
    }

    fun resumePairing() {
        if (page != LoginPage.QR || pairing != null || pairingStage == PairingStage.CONNECTED) return
        refreshPairing()
    }

    fun pausePairing() {
        pairingGeneration++
        pairingTimer?.cancel(); pairingTimer = null
        pairingConnection?.cancel(); pairingConnection = null
        pairing?.close(); pairing = null; qrUrl = ""
        pairingStage = PairingStage.STARTING
    }

    fun refreshPairing() {
        pausePairing()
        pairingStage = PairingStage.STARTING; pairingMessage = ""; loginUsername = ""
        val turn = pairingGeneration
        pairingTimer = scope.launch {
            var startingServer: TvPairingServer? = null
            try {
                val host = withContext(Dispatchers.IO) { TvPairingServer.localHost() }
                if (turn != pairingGeneration) return@launch
                if (host == null) {
                    pairingStage = PairingStage.OFFLINE
                    pairingMessage = "请先将电视接入 Wi-Fi 或有线网络"
                    return@launch
                }
                val server = withContext(Dispatchers.IO) {
                    TvPairingServer(host, android.os.Build.MODEL.take(60).ifBlank { "SingVerse TV" }, scanned = {
                        scope.launch { if (turn == pairingGeneration && pairingStage == PairingStage.WAITING) pairingStage = PairingStage.SCANNED }
                    }, connect = { address, credential ->
                        val completion = withContext(Dispatchers.Main.immediate) {
                            check(turn == pairingGeneration && page == LoginPage.QR)
                            scope.async {
                                withTimeout(25_000) {
                                    pairingStage = PairingStage.CONNECTING
                                    val connection = address to credential
                                    val info = TvApiTransport.request(connection, "/api/v1/server-info", "GET", null)
                                    val serverInfo = JSONObject(info.body)
                                    require(info.status == 200 && serverInfo.optString("service") == "singverse" && serverInfo.optInt("apiVersion") == 1) { "不是兼容的 SingVerse 音乐库" }
                                    val home = TvApiTransport.request(connection, "/api/v1/tv/home", "GET", null)
                                    require(home.status == 200) { "电视登录已失效，请在手机重新登录音乐库" }
                                    currentCoroutineContext().ensureActive()
                                    check(turn == pairingGeneration && page == LoginPage.QR)
                                    serverName = serverInfo.optString("name")
                                    authorized(address, credential)
                                    pairingStage = PairingStage.CONNECTED
                                    qrUrl = ""
                                }
                            }.also { pairingConnection = it }
                        }
                        try { completion.await() }
                        catch (failure: Exception) {
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (turn == pairingGeneration) {
                                    qrUrl = ""; pairingStage = PairingStage.FAILED
                                    pairingMessage = failure.message ?: "未能连接音乐库，请刷新二维码重试"
                                }
                            }
                            throw failure
                        }
                    }, lifetimeMillis = pairingLifetimeMillis).also { startingServer = it }
                }
                if (turn != pairingGeneration) { server.close(); return@launch }
                pairing = server; qrUrl = server.code.encode(); pairingStage = PairingStage.WAITING
                while (turn == pairingGeneration && pairingStage !in listOf(PairingStage.CONNECTED, PairingStage.FAILED)) {
                    delay(1000)
                    if (server.remainingSeconds <= 0 && pairingStage != PairingStage.CONNECTING) {
                        server.close(); pairing = null; qrUrl = ""; pairingStage = PairingStage.EXPIRED
                        break
                    }
                }
                // Keep the completed attempt briefly available if the phone loses its reply.
                // The QR is already hidden and the server cannot accept another attempt.
                if (turn == pairingGeneration && pairingStage == PairingStage.CONNECTED) {
                    delay(minOf(10_000L, server.remainingSeconds * 1000L))
                }
                if (turn == pairingGeneration && pairingStage in listOf(PairingStage.CONNECTED, PairingStage.FAILED)) {
                    server.close(); pairing = null
                }
            } catch (cancelled: CancellationException) { startingServer?.close(); throw cancelled }
            catch (_: Exception) {
                startingServer?.close()
                if (turn == pairingGeneration) {
                    pairing?.close(); pairing = null; qrUrl = ""; pairingStage = PairingStage.OFFLINE
                    pairingMessage = "暂时无法生成登录码，请检查电视网络后重试"
                }
            }
        }
    }

    fun cancel() {
        pausePairing()
        generation++; val previousJob = job; previousJob?.cancel(); job = null
        val session = admin; admin = null
        if (session != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { previousJob?.join(); runCatching { session.logout() } } }
        discovery.stop()
        password = ""; mfaCode = ""; mfaRequired = false; showPassword = false; error = ""; busy = false
    }

    private fun launch(task: suspend (Int) -> Unit) {
        if (busy) return
        val turn = ++generation
        job = scope.launch {
            busy = true; error = ""
            try { task(turn) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (turn == generation) {
                    error = failure.message ?: "连接失败，请重试"
                    // A failed MFA attempt may be retried with the same restricted challenge.
                    if (!mfaRequired) { val session = admin; admin = null; if (session != null) runCatching { session.logout() } }
                }
            } finally { if (turn == generation) busy = false }
        }
    }

    private suspend fun prepare(turn: Int): LoginHttp {
        val session = LoginHttp(NasAddress.normalize(host, protocol))
        val info = session.request("/api/v1/server-info")
        check(turn == generation)
        require(info.optString("service") == "singverse" && info.optInt("apiVersion") == 1) { "不是兼容的 SingVerse 服务" }
        serverName = info.optString("name").ifBlank { serverName }.ifBlank { NasAddress.authority(session.base) }
        return session
    }

    fun submit() {
        if (mfaRequired) { verifyMfa(); return }
        if (busy) return
        val suppliedPassword = password
        password = ""
        launch { turn ->
            require(username.isNotBlank() && suppliedPassword.isNotEmpty()) { "请输入账号和密码" }
            val session = prepare(turn); admin = session; loginUsername = username.trim()
            val state = session.adminPost("/api/v1/auth/login", JSONObject().put("username", loginUsername).put("password", suppliedPassword)).getString("state")
            if (turn != generation) return@launch
            if (state == "mfaRequired") mfaRequired = true else {
                require(state == "authenticated") { "未知登录状态" }
                connect(session, turn)
            }
        }
    }

    private fun verifyMfa() {
        val code = mfaCode.trim(); mfaCode = ""
        launch { turn ->
            require(code.isNotBlank()) { "请输入验证码或恢复码" }
            val session = admin ?: error("登录已过期，请返回重新登录")
            require(session.adminPost("/api/v1/auth/mfa/verify", JSONObject().put("code", code)).optString("state") == "authenticated") { "二次验证失败" }
            if (turn != generation) return@launch
            mfaRequired = false
            connect(session, turn)
        }
    }

    private suspend fun connect(session: LoginHttp, turn: Int) {
        val credential = try {
            session.adminPost("/api/v1/auth/tv-session", JSONObject().put("name", android.os.Build.MODEL.take(60).ifBlank { "SingVerse TV" }))
        } finally { withContext(NonCancellable) { runCatching { session.logout() }; if (admin === session) admin = null } }
        currentCoroutineContext().ensureActive()
        if (turn != generation) return
        password = ""; mfaCode = ""
        authorized(session.base, credential.getString("token"))
    }

    fun clearError() { error = "" }

    fun close() { cancel(); scope.cancel() }
}
