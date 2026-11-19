package com.mynora.singverse.tv

import java.io.File
import java.net.URI
import java.security.MessageDigest

internal const val TV_RELEASE_PATH = "/api/v1/client-releases/tv"
internal const val TV_APK_PATH = "$TV_RELEASE_PATH/apk"
internal const val MAX_APK_SIZE = 268435456L
internal const val UPDATE_CHECK_INTERVAL = 24 * 60 * 60 * 1000L
internal val TV_RELEASE_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

internal fun preferredUpdateAbi(supported: List<String>): String? = supported.firstOrNull { it in TV_RELEASE_ABIS }

internal data class TvRelease(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val minSdk: Int,
    val abis: List<String>,
    val size: Long,
    val sha256: String,
    val certificateSha256: String,
    val notes: String,
    val url: String,
) {
    init {
        require(versionCode > 0 && versionName.isNotBlank()) { "安装包版本信息无效" }
        require(packageName == "com.mynora.singverse.tv") { "安装包不是 SingVerse TV" }
        require(minSdk >= 21 && size in 1..MAX_APK_SIZE) { "安装包系统要求或大小无效" }
        require(sha256.matches(Regex("[a-f0-9]{64}")) && certificateSha256.matches(Regex("[a-f0-9]{64}"))) { "安装包摘要或证书信息无效" }
        require(url == TV_APK_PATH) { "安装包地址必须来自当前 NAS" }
    }
    fun compatible(sdk: Int, supportedAbis: List<String>): Boolean =
        sdk >= minSdk && (abis.isEmpty() || "universal" in abis || abis.any { it in supportedAbis })
}

internal fun updateOrigin(address: String): String {
    val uri = URI(address)
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/"))
    val host = uri.host.lowercase().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
    val port = if (uri.port == -1 || uri.port == if (uri.scheme == "https") 443 else 80) "" else ":${uri.port}"
    return "${uri.scheme}://$host$port"
}
internal fun updatePreferenceKey(address: String): String = MessageDigest.getInstance("SHA-256")
    .digest(updateOrigin(address).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun updateCheckDue(now: Long, lastCheck: Long): Boolean =
    lastCheck == 0L || now < lastCheck || now - lastCheck >= UPDATE_CHECK_INTERVAL
internal fun shouldOfferUpdate(version: Long, installed: Long, skipped: Long, manual: Boolean): Boolean =
    version > installed && (manual || version != skipped)
internal fun deferUpdatePrompt(playerState: String): Boolean =
    playerState in setOf("PREPARING", "BUFFERING", "PLAYING", "PAUSED")

/** Recheck cached bytes immediately before handing a package to Android. */
internal fun verifyUpdateFile(file: File, release: TvRelease, isCurrent: () -> Boolean) {
    check(isCurrent()) { "NAS 连接已更换，请重新下载" }
    require(file.isFile && file.length() == release.size) { "安装包大小已改变，请重新下载" }
    val digest = MessageDigest.getInstance("SHA-256")
    var length = 0L
    file.inputStream().use { input ->
        val bytes = ByteArray(65536)
        while (true) {
            check(isCurrent()) { "NAS 连接已更换，请重新下载" }
            val count = input.read(bytes)
            if (count < 0) break
            length += count
            require(length <= release.size) { "安装包大小已改变，请重新下载" }
            digest.update(bytes, 0, count)
        }
    }
    require(length == release.size && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包摘要已改变，请重新下载" }
    check(isCurrent()) { "NAS 连接已更换，请重新下载" }
}
