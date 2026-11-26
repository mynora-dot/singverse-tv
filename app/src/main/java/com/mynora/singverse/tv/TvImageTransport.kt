package com.mynora.singverse.tv

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TvImageResponse(
    val status: Int,
    val bytes: ByteArray?,
    val contentType: String?,
    val etag: String?,
    val expiresAt: Long?,
)

internal object TvImageTransport {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun read(address: String, credential: String, path: String, etag: String? = null): TvImageResponse {
        TvMediaTransport.requireSameOrigin(address, address + path)
        val request = Request.Builder().url(address + path).header("Authorization", "Bearer $credential")
            .apply { if (etag != null) header("If-None-Match", etag) }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val bytes = if (it.code != 200) null else it.body?.byteStream()?.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_ARTWORK_BYTES) throw IOException("服务响应过大")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            val control = CacheControl.parse(it.headers)
                            val age = (it.header("Age") ?: "0").toLongOrNull()
                            val maxAge = ARTWORK_MAX_AGE_MILLIS / 1000
                            val variesOnAnything = it.headers("Vary").any { value -> value.split(',').any { it.trim() == "*" } }
                            val expiresAt = if (control.isPrivate && !control.isPublic && !control.noStore &&
                                !control.noCache && !variesOnAnything && control.maxAgeSeconds.toLong() == maxAge && age != null && age >= 0
                            ) {
                                System.currentTimeMillis() + (maxAge - age.coerceAtMost(maxAge)) * 1000
                            } else null
                            TvImageResponse(
                                it.code, bytes, it.body?.contentType()?.let { media -> "${media.type}/${media.subtype}" },
                                it.headers("ETag").singleOrNull(), expiresAt,
                            )
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
