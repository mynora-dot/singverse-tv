package com.mynora.singverse.tv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** Retains the existing AES/GCM token format on API 23+, including its key alias. */
internal class TvCredentialCipher(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val legacy: Boolean = Build.VERSION.SDK_INT < 23,
    private val keyAlias: String = "singverse-device",
) {
    fun decrypt(value: String): String {
        val wrapped = value.startsWith(LEGACY_PREFIX)
        val bytes = Base64.decode(value.removePrefix(LEGACY_PREFIX), Base64.NO_WRAP)
        require(bytes.size >= 12 + 16) { "设备凭证已损坏，请重新授权" }
        val key = when {
            wrapped -> legacyKey(create = false)
            Build.VERSION.SDK_INT >= 23 -> ModernKey.get(keyAlias, create = false)
            else -> error("设备凭证需要重新授权")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (legacy) {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, legacyKey(create = true), GCMParameterSpec(128, iv))
        } else if (Build.VERSION.SDK_INT >= 23) {
            cipher.init(Cipher.ENCRYPT_MODE, ModernKey.get(keyAlias, create = true))
        } else error("设备凭证需要重新授权")
        check(cipher.iv.size == 12)
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return (if (legacy) LEGACY_PREFIX else "") + encoded
    }

    @Suppress("DEPRECATION")
    private fun legacyKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "$keyAlias-legacy-rsa"
        val wrapped = preferences.getString(WRAPPED_KEY, null)
        if (wrapped != null) {
            val restored = runCatching {
                val privateKey = store.getKey(alias, null) as PrivateKey
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsa.init(Cipher.DECRYPT_MODE, privateKey)
                val bytes = rsa.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
                require(bytes.size == 32)
                SecretKeySpec(bytes, "AES")
            }
            restored.getOrNull()?.let { return it }
            if (!create) throw IllegalStateException("设备凭证密钥不可用，请重新授权", restored.exceptionOrNull())
            // Only a new explicit authorization may replace an unusable wrapping key.
            store.deleteEntry(alias)
        }
        check(create) { "设备凭证密钥不存在，请重新授权" }
        if (!store.containsAlias(alias)) {
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            val spec = KeyPairGeneratorSpec.Builder(context).setAlias(alias).setKeySize(2048)
                .setSubject(X500Principal("CN=SingVerse TV")).setSerialNumber(BigInteger.ONE)
                .setStartDate(start).setEndDate(end).build()
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, store.getCertificate(alias).publicKey)
        val encoded = Base64.encodeToString(rsa.doFinal(key.encoded), Base64.NO_WRAP)
        check(preferences.edit().putString(WRAPPED_KEY, encoded).commit()) { "无法保存设备凭证密钥" }
        return key
    }

    @RequiresApi(23)
    private object ModernKey {
        fun get(alias: String, create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            check(create) { "设备凭证密钥不存在，请重新授权" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }

    companion object {
        private const val LEGACY_PREFIX = "rsa-aes-gcm:"
        private const val WRAPPED_KEY = "wrapped-device-key"
    }
}
