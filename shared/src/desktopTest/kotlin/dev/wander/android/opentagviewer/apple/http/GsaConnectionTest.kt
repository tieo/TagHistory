package io.github.tieo.taghistory.apple.http

import io.github.tieo.taghistory.apple.gsa.GSA_CONNECTION
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Requests carrying [GSA_CONNECTION] each open their own connection, on the
 * engines the app (OkHttp) and the server (CIO) use. Checked against a local
 * server that keeps every connection alive and counts them.
 */
class GsaConnectionTest {

    private val server = ServerSocket(0)
    private val connections = AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        val output = it.getOutputStream()
                        while (true) {
                            val requestLine = input.readLine() ?: break
                            if (requestLine.isEmpty()) continue
                            var length = 0
                            var close = false
                            while (true) {
                                val header = input.readLine() ?: break
                                if (header.isEmpty()) break
                                val (name, value) = header.split(":", limit = 2).map(String::trim)
                                if (name.equals("Content-Length", true)) length = value.toInt()
                                if (name.equals("Connection", true) && value.equals("close", true)) close = true
                            }
                            repeat(length) { input.read() }
                            val reply = if (close) "Connection: close\r\n" else "Connection: keep-alive\r\n"
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n$reply\r\nok".toByteArray())
                            output.flush()
                            if (close) break
                        }
                    }
                }
            }
        }
    }

    @AfterTest
    fun stop() = server.close()

    private fun connectionsFor(engine: HttpClientEngineFactory<*>, headers: Map<String, String>): Int = runBlocking {
        val transport = KtorHttpTransport(HttpClient(engine))
        repeat(3) {
            transport.execute(HttpRequest("POST", "http://127.0.0.1:${server.localPort}/grandslam", headers, "x".encodeToByteArray()))
        }
        connections.get()
    }

    @Test
    fun okhttp_opens_a_connection_per_gsa_request() {
        assertEquals(3, connectionsFor(OkHttp, mapOf(GSA_CONNECTION)))
    }

    @Test
    fun cio_opens_a_connection_per_gsa_request() {
        assertEquals(3, connectionsFor(CIO, mapOf(GSA_CONNECTION)))
    }

    @Test
    fun without_the_header_okhttp_reuses_the_connection() {
        assertEquals(1, connectionsFor(OkHttp, emptyMap()))
    }
}
