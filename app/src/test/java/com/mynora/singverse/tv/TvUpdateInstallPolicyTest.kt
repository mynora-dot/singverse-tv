package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class TvUpdateInstallPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "verified update cache".toByteArray()
    private val release = TvRelease(1001, "0.1.1", "com.mynora.singverse.tv", 23, listOf("universal"),
        payload.size.toLong(), MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        "a".repeat(64), "", TV_APK_PATH)

    @Test fun unchangedCacheCanBeVerifiedAgainAfterPermissionReturns() {
        val file = temporary.newFile().apply { writeBytes(payload) }
        verifyUpdateFile(file, release) { true }
        verifyUpdateFile(file, release) { true }
        assertArrayEquals(payload, file.readBytes())
    }
    @Test fun modifiedOrTruncatedCacheCannotBeInstalledAfterTheFirstVerification() {
        val file = temporary.newFile().apply { writeBytes(payload) }
        verifyUpdateFile(file, release) { true }
        file.writeBytes(ByteArray(payload.size) { 'x'.code.toByte() })
        assertThrows(IllegalArgumentException::class.java) { verifyUpdateFile(file, release) { true } }
        file.writeBytes(payload.copyOf(payload.size - 1))
        assertThrows(IllegalArgumentException::class.java) { verifyUpdateFile(file, release) { true } }
    }
    @Test fun changingNasDuringFinalVerificationRejectsThePackage() {
        val file = temporary.newFile().apply { writeBytes(payload) }
        var checks = 0
        assertThrows(IllegalStateException::class.java) { verifyUpdateFile(file, release) { ++checks < 3 } }
    }
}
