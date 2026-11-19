package com.mynora.singverse.tv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A cancelled or superseded update closes the socket and removes its partial file. */
internal object TvUpdateDownload {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    suspend fun download(
        address: String,
        release: TvRelease,
        file: File,
        isCurrent: () -> Boolean,
        progress: (Long) -> Unit,
        validate: (File) -> Unit,
    ): File {
        TvMediaTransport.requireSameOrigin(address, address + release.url)
        require(release.url == TV_APK_PATH)
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(address + release.url)
                .apply { release.abis.singleOrNull()?.takeIf { it in TV_RELEASE_ABIS }?.let { header("X-SingVerse-ABI", it) } }.build()
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel(); file.delete() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    file.delete()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            require(it.code == 200) { "无法读取升级包（${it.code}）" }
                            val body = it.body ?: error("升级包为空")
                            require(body.contentLength() < 0 || body.contentLength() == release.size) { "升级包大小已改变，请重新检查" }
                            val hash = MessageDigest.getInstance("SHA-256")
                            var length = 0L
                            body.byteStream().use { input -> file.outputStream().use { output ->
                                val bytes = ByteArray(65536)
                                while (true) {
                                    check(continuation.isActive && isCurrent()) { "连接已更换或下载已取消" }
                                    val count = input.read(bytes)
                                    if (count < 0) break
                                    length += count
                                    require(length <= release.size) { "升级包大小异常" }
                                    hash.update(bytes, 0, count); output.write(bytes, 0, count)
                                    progress(length)
                                }
                            } }
                            require(length == release.size && hash.digest().joinToString("") { byte -> "%02x".format(byte) } == release.sha256) { "升级包校验失败" }
                        }
                        check(continuation.isActive && isCurrent()) { "连接已更换或下载已取消" }
                        validate(file)
                        check(continuation.isActive && isCurrent()) { "连接已更换或下载已取消" }
                        continuation.resume(file)
                    } catch (e: Exception) {
                        file.delete()
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
