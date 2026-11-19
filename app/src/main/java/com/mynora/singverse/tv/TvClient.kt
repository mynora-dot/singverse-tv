package com.mynora.singverse.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

class TvHttpException(val status: Int, message: String) : java.io.IOException(message)

class TvClient(context: Context) {
    private val covers = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val imageRequests = TvImageRequests(covers::get)
    private val preferences = context.getSharedPreferences("nas", Context.MODE_PRIVATE)
    private val credentialCipher = TvCredentialCipher(context, preferences)
    @Volatile var base: String = preferences.getString("base", "") ?: ""
    @Volatile var token: String = ""
    @Volatile internal var connectionGeneration = 0L; private set
    init {
        token = runCatching {
            val value = preferences.getString("token", null) ?: return@runCatching ""
            credentialCipher.decrypt(value)
        }.getOrDefault("")
    }
    @Synchronized fun credentials(): Pair<String, String> = base to token
    suspend fun save(address: String, credential: String) {
        val uri = URI(address.trim().trimEnd('/'))
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "请输入 NAS 根地址，例如 http://nas:18080" }
        val generation = connectionGeneration
        // RSA key generation on older TVs must not block the UI or commit a cancelled login.
        val encrypted = withContext(Dispatchers.IO) { credentialCipher.encrypt(credential) }
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            check(generation == connectionGeneration) { "NAS 连接已更换，请重新登录" }
            imageRequests.clear(); covers.evictAll()
            connectionGeneration++
            base = uri.toString(); token = credential
            preferences.edit().putString("base", base).putString("token", encrypted).apply()
        }
    }
    @Synchronized fun clear() { connectionGeneration++; imageRequests.clear(); covers.evictAll(); token = ""; preferences.edit().remove("token").apply() }
    suspend fun image(path: String, revision: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val (address, credential, generation) = synchronized(this@TvClient) { Triple(base, token, connectionGeneration) }
        val key="$generation:$address$path@$revision"
        if (!path.startsWith("/api/v1/tv/home/banners/") && !path.startsWith("/api/v1/tv/library/songs/") && !path.startsWith("/api/v1/tv/library/classification-tags/") && !path.startsWith("/api/v1/tv/library/artists/")) return@withContext null
        imageRequests.load(key) {
            val bytes = TvImageTransport.read(address, credential, path) ?: return@load null
            currentCoroutineContext().ensureActive()
            val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            if(options.outWidth<=0 || options.outHeight<=0)return@load null
            options.inSampleSize=1
            val limit=if(path.startsWith("/api/v1/tv/home/banners/") || path.startsWith("/api/v1/tv/library/classification-tags/") || path.startsWith("/api/v1/tv/library/artists/"))1280 else 512
            while(maxOf(options.outWidth/options.inSampleSize,options.outHeight/options.inSampleSize)>limit)options.inSampleSize*=2
            options.inJustDecodeBounds=false
            currentCoroutineContext().ensureActive()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            currentCoroutineContext().ensureActive()
            synchronized(this@TvClient) {
                if (connectionGeneration != generation) null else bitmap?.also { covers.put(key,it) }
            }
        }
    }
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, connection: Pair<String, String> = credentials()): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/"))
        val (address, _) = connection
        val server = URI(address)
        if (server.scheme == "http") require(java.net.InetAddress.getAllByName(server.host).all { it.isLoopbackAddress || it.isSiteLocalAddress }) { "HTTP 仅限本机或受限内网 NAS，请使用 HTTPS 访问其他地址" }
        val updateAbi = if (path == TV_RELEASE_PATH) requireNotNull(preferredUpdateAbi(android.os.Build.SUPPORTED_ABIS.toList())) { "此电视处理器没有可用的安装包" } else null
        val response = TvApiTransport.request(connection, path, method, body?.toString(), updateAbi)
        if (response.status !in 200..299) throw TvHttpException(response.status, runCatching { JSONObject(response.body).optString("detail", "请求失败：${response.status}") }.getOrDefault("请求失败：${response.status}"))
        response.body
    }
}
