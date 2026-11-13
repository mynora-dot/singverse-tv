package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class TvPairingProtocolTest {
    private val session = "a".repeat(32)
    private val key = ByteArray(32) { it.toByte() }
    private val code get() = TvPairingCode("http://192.168.1.8:45678", session, key)

    @Test fun qrRoundTripNeedsNoNasAddressOrAccount() {
        val qr = code.encode()
        val parsed = TvPairingCode.parse(qr)
        assertEquals(code.endpoint, parsed.endpoint)
        assertEquals(session, parsed.session)
        assertArrayEquals(key, parsed.key)
        assertFalse(qr.contains("token")); assertFalse(qr.contains("password"))
    }

    @Test fun qrRejectsPublicLoopbackNamesRedirectsAndAmbiguousFields() {
        val qr = code.encode()
        for (candidate in listOf(
            qr.replace("192.168.1.8", "8.8.8.8"), qr.replace("192.168.1.8", "127.0.0.1"),
            qr.replace("192.168.1.8", "nas.local"), qr.replace("192.168.1.8", "192.168.001.8"),
            qr + "&key=" + "0".repeat(64), qr.replace("v=1", "v=2"), qr + "#secret",
            qr.replace("45678", "0"), qr.replace("http%3A", "https%3A"),
            qr.replace("%3A45678", "%3A45678%2Fadmin"),
        )) {
            assertThrows(candidate, IllegalArgumentException::class.java) { TvPairingCode.parse(candidate) }
        }
    }

    @Test fun credentialsAreEncryptedAndBoundToSessionDirectionAndOperation() {
        val clear = "http://192.168.1.20:18080:fixture-only-tv-token".toByteArray()
        val sealed = TvPairingProtocol.seal(key, session, "connect-request", clear)
        assertFalse(sealed.second.contains("fixture"))
        assertArrayEquals(clear, TvPairingProtocol.open(key, session, "connect-request", sealed.first, sealed.second))
        for ((secret, target, operation) in listOf(
            Triple(ByteArray(32), session, "connect-request"),
            Triple(key, "b".repeat(32), "connect-request"),
            Triple(key, session, "connect-response"),
            Triple(key, session, "inspect-request"),
        )) assertThrows(Exception::class.java) { TvPairingProtocol.open(secret, target, operation, sealed.first, sealed.second) }
        val altered = sealed.second.dropLast(1) + if (sealed.second.last() == '0') "1" else "0"
        assertThrows(Exception::class.java) { TvPairingProtocol.open(key, session, "connect-request", sealed.first, altered) }
    }

    @Test fun freshNoncesPreventIdenticalPackages() {
        val first = TvPairingProtocol.seal(key, session, "inspect-request", byteArrayOf(1))
        val second = TvPairingProtocol.seal(key, session, "inspect-request", byteArrayOf(1))
        assertNotEquals(first.first, second.first)
        assertNotEquals(first.second, second.second)
    }
}
