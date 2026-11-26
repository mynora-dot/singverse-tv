package com.mynora.singverse.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI

class TvHttpException(val status: Int, message: String) : java.io.IOException(message)

class TvClient(context: Context) {
    private class ArtworkAccess(
        val namespace: String? = null,
        val authorizedConnection: Pair<String, String>? = null,
        val blocked: Boolean = false,
    )

    private data class ImageConnection(val address: String, val credential: String, val access: ArtworkAccess)
    private data class MemoryArtwork(val bitmap: Bitmap, val expiresAt: Long?)

    private val artworkAccess = mutableStateOf(ArtworkAccess())
    internal val artworkScope: Any get() = artworkAccess.value
    private val artworkDisk = TvArtworkDiskCache(File(context.cacheDir, "artwork-v1"))
    private val covers = object : LruCache<String, MemoryArtwork>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: MemoryArtwork) = value.bitmap.allocationByteCount
    }
    private val imageRequests = TvImageRequests { key: String ->
        covers.get(key)?.takeIf { it.expiresAt == null || artworkIsFresh(it.expiresAt, System.currentTimeMillis()) }?.bitmap
    }
    private val preferences = context.getSharedPreferences("nas", Context.MODE_PRIVATE)
    private val credentialCipher = TvCredentialCipher(context, preferences)
    @Volatile var base: String = preferences.getString("base", "") ?: ""
    @Volatile var token: String = ""
    @Volatile internal var connectionGeneration = 0L; private set

    internal val hasSavedCredentials: Boolean get() = base.isNotBlank() && preferences.contains("token")

    internal suspend fun restoreCredentials(): Boolean {
        val generation = connectionGeneration
        val restored = withContext(Dispatchers.IO) {
            val encrypted = preferences.getString("token", null) ?: return@withContext ""
            runCatching { credentialCipher.decrypt(encrypted) }.getOrDefault("")
        }
        if (restored.isBlank()) return false
        currentCoroutineContext().ensureActive()
        return synchronized(this) {
            if (generation != connectionGeneration || token.isNotEmpty()) return@synchronized false
            token = restored
            true
        }
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
            resetArtworkAccess()
            connectionGeneration++
            base = uri.toString(); token = credential
            preferences.edit().putString("base", base).putString("token", encrypted).apply()
        }
    }
    @Synchronized fun clear() {
        connectionGeneration++
        resetArtworkAccess()
        token = ""
        preferences.edit().remove("token").apply()
    }

    @Synchronized internal fun authorizeArtworkCache(instanceId: String, expectedGeneration: Long) {
        if (expectedGeneration != connectionGeneration || token.isBlank()) return
        val namespace = artworkNamespace(instanceId, base, token)
        val access = artworkAccess.value
        val connection = credentials()
        if (access.blocked || namespace != access.namespace || connection != access.authorizedConnection) {
            resetArtworkAccess(ArtworkAccess(namespace, connection))
        }
    }

    @Synchronized internal fun releaseArtwork() = resetArtworkAccess(ArtworkAccess(blocked = true))

    private fun resetArtworkAccess(access: ArtworkAccess = ArtworkAccess()) {
        artworkAccess.value = access
        imageRequests.clear()
        covers.evictAll()
    }

    private fun isCurrent(connection: ImageConnection, access: ArtworkAccess = connection.access): Boolean =
        access === artworkAccess.value && connection.address == base && connection.credential == token

    suspend fun image(path: String, revision: String): Bitmap? = withContext(Dispatchers.IO) {
        val source = TvArtworkSource.of(path) ?: return@withContext null
        val connection = synchronized(this@TvClient) { ImageConnection(base, token, artworkAccess.value) }
        if (connection.access.blocked || connection.credential.isBlank() || connection.address.isBlank()) return@withContext null
        val namespace = connection.access.namespace?.takeIf {
            connection.access.authorizedConnection == (connection.address to connection.credential)
        }
        val imageKey = source.cacheKey(
            namespace ?: artworkNamespace("memory", connection.address, connection.credential) ?: return@withContext null,
            revision,
        )
        val diskKey = imageKey.takeIf { namespace != null }
        val memoryKey = "${System.identityHashCode(connection.access)}:$imageKey"
        val bitmap = imageRequests.load(memoryKey) {
            if (!isCurrent(connection)) return@load null
            loadArtwork(source, connection, diskKey, memoryKey)
        }
        if (isCurrent(connection)) bitmap else null
    }

    private suspend fun loadArtwork(source: TvArtworkSource, connection: ImageConnection, diskKey: String?, memoryKey: String): Bitmap? {
        val coroutineContext = currentCoroutineContext()
        var saved = diskKey?.let(artworkDisk::read)
        coroutineContext.ensureActive()
        if (!isCurrent(connection)) return null
        if (saved != null && saved.isFresh(System.currentTimeMillis())) {
            val bitmap = decodeArtwork(saved.bytes, source.decodeLimit, saved.contentType)
            if (bitmap != null) return rememberArtwork(connection, memoryKey, bitmap, saved.expiresAt)
            discardArtwork(connection, diskKey)
            saved = null
        }

        suspend fun request(etag: String? = null) = TvImageTransport.read(connection.address, connection.credential, source.path, etag)
        // If the server's 304 omits the opted-in policy, fetch without a validator.
        var response = request(saved?.etag)
        coroutineContext.ensureActive()
        if (!isCurrent(connection)) return null
        val reused = response.status == 304 && saved != null && response.etag == saved.etag && response.expiresAt != null
        if (response.status == 304 && !reused) {
            discardArtwork(connection, diskKey)
            response = request()
        }
        coroutineContext.ensureActive()
        if (!isCurrent(connection)) return null
        if (response.status == 401 || response.status == 403) {
            val blocked = synchronized(this) {
                if (!isCurrent(connection)) return null
                ArtworkAccess(blocked = true).also(::resetArtworkAccess)
            }
            discardArtwork(connection, diskKey, blocked)
            return null
        }
        if (response.status == 404) discardArtwork(connection, diskKey)
        val bytes = if (reused) saved?.bytes else response.bytes
        val contentType = if (reused) saved?.contentType else response.contentType
        if (bytes == null || contentType == null || contentType !in ARTWORK_CONTENT_TYPES) {
            if (response.status == 200) discardArtwork(connection, diskKey)
            return null
        }
        val bitmap = decodeArtwork(bytes, source.decodeLimit, contentType)
        coroutineContext.ensureActive()
        val expiresAt = response.expiresAt
        val etag = response.etag
        if (bitmap == null || expiresAt == null || etag == null) {
            discardArtwork(connection, diskKey)
        } else if (diskKey != null && isCurrent(connection)) {
            val entry = TvArtworkEntry(etag, expiresAt, contentType, bytes)
            if (entry.isValid()) {
                artworkDisk.write(diskKey, entry) { temporary, destination ->
                    synchronized(this) {
                        if (coroutineContext.isActive && isCurrent(connection)) temporary.renameTo(destination)
                    }
                }
            } else discardArtwork(connection, diskKey)
        }
        return bitmap?.let { rememberArtwork(connection, memoryKey, it, response.expiresAt) }
    }

    private fun discardArtwork(connection: ImageConnection, diskKey: String?, access: ArtworkAccess = connection.access) {
        if (diskKey == null) return
        artworkDisk.remove(diskKey) { cached ->
            synchronized(this) { if (isCurrent(connection, access)) cached.delete() }
        }
    }

    private suspend fun rememberArtwork(connection: ImageConnection, key: String, bitmap: Bitmap, expiresAt: Long?): Bitmap? {
        currentCoroutineContext().ensureActive()
        return synchronized(this) {
            if (!isCurrent(connection)) null else bitmap.also { covers.put(key, MemoryArtwork(it, expiresAt)) }
        }
    }

    private fun decodeArtwork(bytes: ByteArray, limit: Int, contentType: String?): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType != contentType) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth / options.inSampleSize, options.outHeight / options.inSampleSize) > limit) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
