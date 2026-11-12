package com.mynora.singverse.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvUpdateDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "signed APK fixture".toByteArray()
    private fun release(sha: String = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }) =
        TvRelease(1001, "0.1.1", "com.mynora.singverse.tv", 23, emptyList(), payload.size.toLong(), sha, "b".repeat(64), "", TV_APK_PATH)

    @Test fun validatesTheCompleteDownloadAndReportsProgress() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val headers = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        val lines = mutableListOf<String>(); val reader = socket.getInputStream().bufferedReader()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray() + payload)
                        lines
                    }
                }
                val file = File(temporary.root, "update.apk"); var progress = 0L; var validated = false
                TvUpdateDownload.download("http://127.0.0.1:${server.localPort}", release(), file, { true }, { progress = it }) {
                    assertArrayEquals(payload, it.readBytes()); validated = true
                }
                assertTrue(validated); assertEquals(payload.size.toLong(), progress)
                assertTrue(headers.get(3, TimeUnit.SECONDS).first().contains(TV_APK_PATH))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun checksumFailureRemovesTheUnverifiedPackage() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(); while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray() + payload)
                    }
                }
                val file = File(temporary.root, "invalid.apk")
                val result = runCatching { TvUpdateDownload.download("http://127.0.0.1:${server.localPort}", release("0".repeat(64)), file, { true }, {}) { fail("未经校验的包不能送去安装") } }
                assertTrue(result.isFailure); assertFalse(file.exists()); response.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun changingNasDuringDownloadDiscardsTheOldPackage() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(); while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray() + payload)
                    }
                }
                val current = java.util.concurrent.atomic.AtomicBoolean(true)
                val file = File(temporary.root, "old-nas.apk")
                val result = runCatching {
                    TvUpdateDownload.download("http://127.0.0.1:${server.localPort}", release(), file,
                        { current.get() }, { current.set(false) }) { fail("旧 NAS 的包不能送去安装") }
                }
                assertTrue(result.isFailure); assertFalse(file.exists()); response.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun cancellationClosesTheSocketAndRemovesPartialFiles() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val started = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
            try {
                val disconnected = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader(); while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\n\r\nx".toByteArray())
                        socket.getOutputStream().flush(); started.countDown()
                        try { reader.read() == -1 } catch (_: java.net.SocketException) { true }
                    }
                }
                val file = File(temporary.root, "partial.apk")
                val job = launch(Dispatchers.Default) { TvUpdateDownload.download("http://127.0.0.1:${server.localPort}", release(), file, { true }, {}) {} }
                assertTrue(started.await(3, TimeUnit.SECONDS)); withTimeout(1500) { job.cancelAndJoin() }
                assertTrue(disconnected.get(3, TimeUnit.SECONDS)); assertFalse(file.exists())
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun redirectsNeverDownloadFromAnotherOrigin() = runBlocking<Unit> {
        ServerSocket(0).use { server -> ServerSocket(0).use { other ->
            other.soTimeout = 400
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(); while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${other.localPort}/apk\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
                val file = File(temporary.root, "redirect.apk")
                assertTrue(runCatching { TvUpdateDownload.download("http://127.0.0.1:${server.localPort}", release(), file, { true }, {}) {} }.isFailure)
                assertFalse(file.exists()); response.get(3, TimeUnit.SECONDS)
                assertThrows(SocketTimeoutException::class.java) { other.accept().close() }
            } finally { executor.shutdownNow() }
        } }
    }
}
