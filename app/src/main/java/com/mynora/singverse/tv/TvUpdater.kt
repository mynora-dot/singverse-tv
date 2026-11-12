package com.mynora.singverse.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class TvUpdater(private val context: Context, private val client: TvClient) {
    fun release(value: JSONObject): TvRelease? {
        if (!value.optBoolean("available")) return null
        val abis = value.getJSONArray("abis")
        return TvRelease(value.getLong("versionCode"), value.getString("versionName"), value.getString("packageName"),
            value.getInt("minSdk"), (0 until abis.length()).map { abis.getString(it) }, value.getLong("size"),
            value.getString("sha256"), value.getString("certificateSha256"), value.optString("notes"), value.getString("url"))
    }
    @Suppress("DEPRECATION")
    private val signatureFlags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun code(info: android.content.pm.PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    val installedVersion: Long get() = code(context.packageManager.getPackageInfo(context.packageName, 0))
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    fun compatible(release: TvRelease): Boolean = release.compatible(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

    suspend fun download(release: TvRelease, generation: Long, progress: (Long) -> Unit): File {
        val address = client.base
        val directory = File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
        val file = File.createTempFile("singverse-tv-${release.versionCode}-", ".apk", directory)
        return TvUpdateDownload.download(address, release, file, { client.connectionGeneration == generation && client.base == address }, progress) { validate(it, release) }
    }
    @Suppress("DEPRECATION")
    private fun validate(file: File, release: TvRelease) {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags) ?: error("安装包格式无效")
        val installed = context.packageManager.getPackageInfo(context.packageName, signatureFlags)
        require(archive.packageName == context.packageName && archive.packageName == release.packageName) { "安装包不是 SingVerse TV" }
        require(code(archive) == release.versionCode && code(archive) > code(installed) && archive.versionName == release.versionName) { "安装包版本无效或不是更新版本" }
        require(compatible(release)) { "此安装包不支持当前电视系统或架构" }
        if (Build.VERSION.SDK_INT >= 24) require(archive.applicationInfo?.minSdkVersion == release.minSdk) { "安装包最低系统信息不一致" }
        val candidate = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val current = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        fun fingerprints(signatures: Array<android.content.pm.Signature>?): Set<String> = signatures.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
        val certificates = fingerprints(candidate)
        require(certificates == setOf(release.certificateSha256) && certificates == fingerprints(current)) { "签名与当前应用不一致，拒绝安装" }
    }
    /** Returns false while the system installation permission screen is open. */
    suspend fun install(file: File, release: TvRelease, generation: Long): Boolean {
        val address = client.base
        fun current() = client.connectionGeneration == generation && client.base == address
        withContext(Dispatchers.IO) {
            try {
                verifyUpdateFile(file, release, ::current)
                validate(file, release)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
        check(current()) { "NAS 连接已更换，请重新检查更新" }
        require(file.isFile) { "安装包已失效，请重新下载" }
        if (!canInstall()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
