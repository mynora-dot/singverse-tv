package com.mynora.singverse.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvImageTransportTest {
    @Test fun imageBytesRemainBinaryAndOversizedResponsesAreRejected() = runBlocking<Unit> {
        for (size in listOf(2_000_000, 2_000_001)) {
            ServerSocket(0).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val bytes = ByteArray(size) { (it % 256).toByte() }
                try {
                    val response = executor.submit {
                        server.accept().use { socket ->
                            socket.soTimeout = 3000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val output = socket.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    }
                    val address = "http://127.0.0.1:${server.localPort}"
                    if (size == 2_000_000) {
                        assertArrayEquals(bytes, TvImageTransport.read(address, "fixture", "/api/v1/tv/library/songs/1/cover"))
                    } else {
                        val failure = runCatching { TvImageTransport.read(address, "fixture", "/api/v1/tv/library/songs/1/cover") }.exceptionOrNull()
                        assertTrue(failure is IOException)
                        assertEquals("服务响应过大", failure?.message)
                    }
                    response.get(3, TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun leavingTheLastImageConsumerClosesAStalledSocket() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val disconnected = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray())
                        socket.getOutputStream().flush(); started.countDown()
                        input.read() == -1
                    }
                }
                val requests = TvImageRequests<ByteArray> { null }
                val job = launch(Dispatchers.Default) {
                    requests.load("cover") { TvImageTransport.read("http://127.0.0.1:${server.localPort}", "fixture", "/api/v1/tv/library/songs/1/cover") }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                withTimeout(1500) { job.cancelAndJoin() }
                assertTrue(disconnected.get(3, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun imageRedirectDoesNotSendCredentialsToAnotherServer() = runBlocking<Unit> {
        ServerSocket(0).use { server -> ServerSocket(0).use { external ->
            external.soTimeout = 400
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response = executor.submit {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${external.localPort}/stolen\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
                assertNull(TvImageTransport.read("http://127.0.0.1:${server.localPort}", "fixture", "/api/v1/tv/library/songs/1/cover"))
                response.get(3, TimeUnit.SECONDS)
                assertThrows(SocketTimeoutException::class.java) { external.accept().close() }
            } finally { executor.shutdownNow() }
        } }
    }
}
