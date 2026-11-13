package com.mynora.singverse.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*

/** DNS-SD discovery only; every chosen address is verified through server-info before authorization. */
class NasDiscovery(context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    var services by mutableStateOf(listOf<Pair<String,String>>()); private set
    var status by mutableStateOf(""); private set
    var searching by mutableStateOf(false); private set
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0
    @Suppress("DEPRECATION") private fun resolve() {
        if (resolving || pending.isEmpty() || listener == null) return
        resolving = true
        val epoch=generation
        manager.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post { resolving = false; resolve() } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                if(epoch!=generation){resolving=false;resolve();return@post}
                val host = info.host?.hostAddress
                if (host != null && info.port in 1..65535) {
                    val formatted = if (':' in host) "[$host]" else host
                    val address = "http://$formatted:${info.port}"
                    services = (services.filterNot { it.first == info.serviceName } + (info.serviceName to address)).take(20)
                }
                resolving = false; resolve()
            } }
        })
    }
    fun start() {
        stop(); searching = true; services = emptyList(); status = "约需 15 秒，也可手动输入地址"
        val next = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { main.post { if (listener === this) status = "约需 15 秒，也可手动输入地址" } }
            override fun onDiscoveryStopped(type: String) { }
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if(listener===this){ status = "局域网搜索暂不可用，请重试或手动输入地址"; stop() } } }
            override fun onStopDiscoveryFailed(type: String, code: Int) { }
            override fun onServiceFound(info: NsdServiceInfo) { main.post { if (listener === this) { pending.add(info); resolve() } } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { if (listener === this) services = services.filterNot { it.first == info.serviceName } } }
        }
        listener = next
        runCatching { manager.discoverServices("_singverse._tcp.", NsdManager.PROTOCOL_DNS_SD, next) }.onFailure { status = "局域网搜索暂不可用，请重试或手动输入地址"; listener = null; searching = false }
        main.postDelayed({ if (listener === next) { stop(); status = if (services.isEmpty()) "请确认 NAS 已开启 SingVerse 服务，或手动输入地址" else "已找到 ${services.size} 台 NAS" } }, 15000)
    }
    fun stop() { searching = false; generation++;listener?.let { runCatching { manager.stopServiceDiscovery(it) } }; listener = null; pending.clear() }
}
