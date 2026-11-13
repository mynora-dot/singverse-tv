package com.mynora.singverse.tv

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire format shared with the mobile client; the QR is a short-lived capability. */
internal object TvPairingProtocol {
    private val random = SecureRandom()
    fun randomHex(bytes: Int): String = hex(ByteArray(bytes).also(random::nextBytes))
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(value: String, bytes: Int? = null): ByteArray {
        require(value.length in 2..16384 && value.length % 2 == 0 && value.matches(Regex("[0-9a-f]+")) &&
            (bytes == null || value.length == bytes * 2)) { "配对数据无效，请重新扫码" }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun privateHost(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 || it != it.toIntOrNull().toString() }) return false
        val numbers = parts.map(String::toInt)
        return numbers[0] == 10 || numbers[0] == 172 && numbers[1] in 16..31 || numbers[0] == 192 && numbers[1] == 168
    }
    fun seal(key: ByteArray, session: String, operation: String, clear: ByteArray, iv: ByteArray = ByteArray(12).also(random::nextBytes)): Pair<String, String> {
        require(key.size == 32 && iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("singverse:tv-login:v1:$session:$operation".toByteArray(Charsets.UTF_8))
        return hex(iv) to hex(cipher.doFinal(clear))
    }
    fun open(key: ByteArray, session: String, operation: String, nonce: String, ciphertext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unhex(nonce, 12)))
        cipher.updateAAD("singverse:tv-login:v1:$session:$operation".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(unhex(ciphertext))
    }
}

internal class TvPairingCode(val endpoint: String, val session: String, val key: ByteArray) {
    fun encode(): String = "singverse://tv-login?v=1&endpoint=${URLEncoder.encode(endpoint, "UTF-8")}&session=$session&key=${TvPairingProtocol.hex(key)}"
    // Do not use a data class: its generated toString would expose the QR secret.
    companion object {
        fun parse(value: String): TvPairingCode {
            require(value.length <= 1024) { "二维码无效，请重新扫码" }
            val uri = URI(value)
            require(uri.scheme == "singverse" && uri.rawAuthority == "tv-login" && uri.path.orEmpty().isEmpty() && uri.rawFragment == null)
            val entries = uri.rawQuery.orEmpty().split('&').map { part ->
                val fields = part.split('=', limit = 2)
                require(fields.size == 2)
                URLDecoder.decode(fields[0], "UTF-8") to URLDecoder.decode(fields[1], "UTF-8")
            }
            require(entries.size == 4 && entries.map { it.first }.toSet() == setOf("v", "endpoint", "session", "key"))
            val values = entries.toMap()
            require(values["v"] == "1" && values.getValue("session").matches(Regex("[0-9a-f]{32}")))
            val endpoint = URI(values.getValue("endpoint"))
            require(endpoint.scheme == "http" && TvPairingProtocol.privateHost(endpoint.host.orEmpty()) && endpoint.port in 1..65535 &&
                endpoint.rawUserInfo == null && endpoint.path.orEmpty().isEmpty() && endpoint.rawQuery == null && endpoint.rawFragment == null) {
                "请扫描同一网络内的 SingVerse 电视登录码"
            }
            return TvPairingCode(endpoint.toString(), values.getValue("session"), TvPairingProtocol.unhex(values.getValue("key"), 32))
        }
    }
}
