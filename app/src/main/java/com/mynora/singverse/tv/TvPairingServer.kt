package com.mynora.singverse.tv

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** No NAS credentials are needed to start this listener. Only AEAD-authenticated QR holders can use it. */
internal class TvPairingServer(
    host: String,
    private val name: String,
    private val scanned: () -> Unit,
    private val connect: suspend (String, String) -> Unit,
    private val lifetimeMillis: Long = 300_000,
) : AutoCloseable {
    private val listener = ServerSocket(0, 4)
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val key = TvPairingProtocol.unhex(TvPairingProtocol.randomHex(32), 32)
    val code = TvPairingCode("http://$host:${listener.localPort}", TvPairingProtocol.randomHex(16), key)
    private val deadline = System.nanoTime() + lifetimeMillis * 1_000_000
    @Volatile private var closed = false
    private var attempt = ""
    private var status = "waiting"
    private var message = ""
    val remainingSeconds: Int get() = ((deadline - System.nanoTime()).coerceAtLeast(0) / 1_000_000_000).toInt()

    init {
        Thread({
            while (!closed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                try { workers.execute { socket.use(::handle) } } catch (_: Exception) { socket.close() }
            }
        }, "singverse-tv-pairing").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        // Keep a private copy until the reply is written, even when success removes the login screen.
        val secret = synchronized(this) { if (closed) null else key.copyOf() } ?: return
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            var headerBytes = 0
            fun line(): String {
                val out = StringBuilder()
                while (true) {
                    val byte = input.read()
                    require(byte >= 0 && ++headerBytes <= 4096)
                    if (byte == 10) break
                    require(byte in 13..126)
                    if (byte != 13) out.append(byte.toChar())
                }
                return out.toString()
            }
            val request = line().split(' ')
            require(request.size == 3 && request[0] == "POST" && request[2] == "HTTP/1.1")
            val action = request[1].removePrefix("/pair/v1/${code.session}/")
            require(action in listOf("inspect", "connect") && request[1] == "/pair/v1/${code.session}/$action")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val text = line()
                if (text.isEmpty()) break
                val pieces = text.split(':', limit = 2)
                require(pieces.size == 2 && headers.put(pieces[0].lowercase(), pieces[1].trim()) == null)
            }
            require(!headers.containsKey("origin") && !headers.containsKey("transfer-encoding") &&
                headers["content-type"].orEmpty().startsWith("application/json"))
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 1..8192)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val count = input.read(bytes, offset, length - offset); require(count > 0); offset += count }
            require(remainingSeconds > 0 && !closed) { "二维码已失效，请刷新后重试" }
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
            val clear = JSONObject(TvPairingProtocol.open(secret, code.session, "$action-request", envelope.getString("nonce"), envelope.getString("ciphertext")).toString(Charsets.UTF_8))
            val requestId = clear.getString("requestId")
            require(requestId.matches(Regex("[0-9a-f]{32}")))
            if (action == "inspect") {
                scanned()
            } else {
                val base = NasAddress.normalize(clear.getString("origin"))
                val token = clear.getString("token")
                require(token.matches(Regex("[A-Za-z0-9_-]{40,200}")))
                val begin = synchronized(this) {
                    require(!closed && remainingSeconds > 0)
                    require(attempt.isBlank() || attempt == requestId) { "这台电视正在连接，请查看电视屏幕" }
                    if (status == "waiting" || status == "scanned") { attempt = requestId; status = "connecting"; true } else false
                }
                if (begin) {
                    try {
                        runBlocking { connect(base, token) }
                        synchronized(this) { status = "connected" }
                    } catch (_: Exception) {
                        synchronized(this) { status = "error"; message = "电视未能连接音乐库，请刷新二维码重试" }
                    }
                }
            }
            val state = synchronized(this) {
                JSONObject().put("requestId", requestId).put("name", name).put("state", status)
                    .put("remainingSeconds", remainingSeconds).put("message", message)
            }
            val sealed = TvPairingProtocol.seal(secret, code.session, "$action-response", state.toString().toByteArray(Charsets.UTF_8))
            respond(socket, 200, JSONObject().put("nonce", sealed.first).put("ciphertext", sealed.second).toString())
        } catch (_: Exception) {
            runCatching { respond(socket, 400, "{\"detail\":\"配对已失效，请刷新电视二维码重试\"}") }
        } finally { secret.fill(0) }
    }

    private fun respond(socket: Socket, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }

    override fun close() {
        synchronized(this) { if (closed) return; closed = true; key.fill(0) }
        runCatching { listener.close() }
        workers.shutdown()
    }

    companion object {
        fun localHost(): String? = Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            .firstOrNull(TvPairingProtocol::privateHost)
    }
}
