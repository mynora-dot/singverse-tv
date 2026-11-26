package com.mynora.singverse.tv

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MAX_ARTWORK_BYTES = 2_000_000
internal const val ARTWORK_MAX_AGE_MILLIS = 300_000L
internal val ARTWORK_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private val artworkEtag = Regex("\"[0-9a-f]{64}\"")
private val artworkKey = Regex("[0-9a-f]{64}")

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun artworkEtagFor(bytes: ByteArray) = "\"${sha256(bytes)}\""

internal fun artworkNamespace(instanceId: String, address: String, credential: String): String? {
    val origin = address.toHttpUrlOrNull() ?: return null
    if (instanceId.isBlank() || credential.isBlank()) return null
    return sha256("$instanceId\u0000$origin\u0000${sha256(credential.toByteArray(Charsets.UTF_8))}"
        .toByteArray(Charsets.UTF_8))
}

internal data class TvArtworkSource(val path: String, val decodeLimit: Int) {
    fun cacheKey(namespace: String, version: String) =
        sha256("$namespace\u0000$path\u0000$version".toByteArray(Charsets.UTF_8))

    companion object {
        private val song = Regex("/api/v1/tv/library/songs/[A-Za-z0-9_-]+/cover(?:\\?variant=thumbnail)?")
        private val category = Regex("/api/v1/tv/library/classification-tags/[A-Za-z0-9_-]+/cover")
        private val artist = Regex("/api/v1/tv/library/artists/[A-Za-z0-9_-]+/images/(portrait|background)")

        fun of(path: String): TvArtworkSource? = when {
            song.matches(path) -> TvArtworkSource(path.substringBefore('?') + "?variant=thumbnail", 512)
            category.matches(path) || artist.matches(path) -> TvArtworkSource(path, 1280)
            else -> null
        }
    }
}

internal data class TvArtworkEntry(val etag: String, val expiresAt: Long, val contentType: String, val bytes: ByteArray) {
    fun isFresh(now: Long): Boolean = artworkIsFresh(expiresAt, now)

    fun isValid() = etag.matches(artworkEtag) && expiresAt >= 0 &&
        contentType in ARTWORK_CONTENT_TYPES && bytes.size in 1..MAX_ARTWORK_BYTES &&
        etag == artworkEtagFor(bytes)
}

internal fun artworkIsFresh(expiresAt: Long, now: Long): Boolean =
    expiresAt > now && expiresAt - now <= ARTWORK_MAX_AGE_MILLIS

internal class TvArtworkDiskCache(private val directory: File, private val maxBytes: Long = 64L * 1024 * 1024) {
    companion object {
        private const val ENTRY_FORMAT = 1
        private val diskLock = Any()
    }

    fun read(key: String): TvArtworkEntry? = synchronized(diskLock) {
        val file = file(key)
        try {
            if (!file.isFile) return null
            if (file.length() > MAX_ARTWORK_BYTES + 512) throw IOException("无效的封面缓存大小")
            val entry = DataInputStream(file.inputStream().buffered()).use {
                if (it.readInt() != ENTRY_FORMAT) throw IOException("未知的封面缓存格式")
                val etag = it.readUTF()
                val expiresAt = it.readLong()
                val contentType = it.readUTF()
                val length = it.readInt()
                if (length !in 1..MAX_ARTWORK_BYTES) throw IOException("无效的封面缓存大小")
                val bytes = ByteArray(length)
                it.readFully(bytes)
                if (it.read() != -1) throw IOException("无效的封面缓存内容")
                TvArtworkEntry(etag, expiresAt, contentType, bytes)
            }
            if (!entry.isValid()) throw IOException("封面缓存校验失败")
            file.setLastModified(System.currentTimeMillis())
            entry
        } catch (_: IOException) {
            file.delete()
            null
        }
    }

    fun write(key: String, entry: TvArtworkEntry, publish: (temporary: File, destination: File) -> Unit) =
        synchronized(diskLock) {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val size = entry.bytes.size + 512L
            if (size > maxBytes) return
            val destination = file(key)
            val existing = directory.listFiles() ?: return
            existing.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            val entries = existing.filter { it.name.endsWith(".art") }
            var used = entries.sumOf { it.length() }
            if (used + size > maxBytes) {
                for (file in entries.sortedBy { it.lastModified() }) {
                    val length = file.length()
                    if (file.delete()) used -= length
                    if (used + size <= maxBytes) break
                }
            }
            if (used + size > maxBytes) return
            try {
                val temporary = File.createTempFile("artwork-", ".tmp", directory)
                try {
                    DataOutputStream(temporary.outputStream().buffered()).use {
                        it.writeInt(ENTRY_FORMAT)
                        it.writeUTF(entry.etag)
                        it.writeLong(entry.expiresAt)
                        it.writeUTF(entry.contentType)
                        it.writeInt(entry.bytes.size)
                        it.write(entry.bytes)
                    }
                    publish(temporary, destination)
                } finally {
                    temporary.delete()
                }
            } catch (_: IOException) {
                // A disposable cache must not prevent displaying a network image.
            }
        }

    fun remove(key: String, discard: (File) -> Unit) = synchronized(diskLock) { discard(file(key)) }

    private fun file(key: String): File {
        require(key.matches(artworkKey))
        return File(directory, "$key.art")
    }
}
