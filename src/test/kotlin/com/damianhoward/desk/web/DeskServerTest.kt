package com.damianhoward.desk.web

import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Loopback tests over a real [DeskServer]: static routes serve the shell, tab prefixes delegate. */
class DeskServerTest {
    // Records what it was asked to proxy and answers with a marker, so the routing is what's tested.
    private class RecordingGateway : Gateway {
        var forwarded: String? = null

        override fun handles(path: String): Boolean = path.startsWith("/orderbook") || path.startsWith("/trading")

        override fun forward(exchange: HttpExchange) {
            forwarded = exchange.requestURI.path
            val bytes = "proxied:${exchange.requestURI.path}".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private val gateway = RecordingGateway()
    private lateinit var server: DeskServer
    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        server = DeskServer(WebAssets.load(), gateway, port = 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    private fun request(
        method: String,
        path: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:${server.boundPort}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getAt(
        port: Int,
        path: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `healthz responds ok`() {
        val response = request("GET", "/healthz")
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
    }

    @Test
    fun `readyz is a plain ready when no readiness is wired`() {
        val response = request("GET", "/readyz")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains(""""ready":true"""))
    }

    @Test
    fun `readyz is 200 when every upstream is reachable`() {
        val upstreams = Upstreams(URI("http://ob"), URI("http://ts"))
        val ready = DeskServer(WebAssets.load(), gateway, port = 0, readiness = Readiness(upstreams) { true })
        ready.start()
        try {
            val response = getAt(ready.boundPort, "/readyz")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains(""""ready":true"""))
        } finally {
            ready.stop()
        }
    }

    @Test
    fun `readyz is 503 and names the upstream when one is unreachable`() {
        val upstreams = Upstreams(URI("http://ob"), URI("http://ts"))
        val server = DeskServer(WebAssets.load(), gateway, port = 0, readiness = Readiness(upstreams) { it == upstreams.orderbook })
        server.start()
        try {
            val response = getAt(server.boundPort, "/readyz")
            assertEquals(503, response.statusCode())
            assertTrue(response.body().contains(""""trading":{"ok":false}"""))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `serves the privacy notice`() {
        val response = request("GET", "/privacy")
        assertEquals(200, response.statusCode())
        assertEquals("text/html; charset=utf-8", response.headers().firstValue("Content-Type").get())
        assertTrue(response.body().contains("Privacy"))
    }

    @Test
    fun `serves the shell with content types`() {
        assertTrue(request("GET", "/").body().contains("TRADING DESK"))
        assertEquals("text/css; charset=utf-8", request("GET", "/app.css").headers().firstValue("Content-Type").get())
        assertEquals("text/javascript; charset=utf-8", request("GET", "/app.js").headers().firstValue("Content-Type").get())
        assertEquals("text/javascript; charset=utf-8", request("GET", "/trading.js").headers().firstValue("Content-Type").get())
    }

    // `/trading` is a tab prefix and `/trading.js` is a shell asset. Only a whole path segment
    // counts as the prefix, so the asset must be served here rather than proxied to the ledger.
    @Test
    fun `the trading renderer is served by the shell, not proxied to the ledger`() {
        val response = request("GET", "/trading.js")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("/trading/api/stream"))
        assertNull(gateway.forwarded, "the shell asset should never reach the gateway")
    }

    @Test
    fun `a tab-prefixed path is delegated to the gateway`() {
        val response = request("GET", "/orderbook/api/AAPL/stream")
        assertEquals(200, response.statusCode())
        assertEquals("proxied:/orderbook/api/AAPL/stream", response.body())
        assertEquals("/orderbook/api/AAPL/stream", gateway.forwarded)
    }

    @Test
    fun `an unknown path is a 404`() {
        assertEquals(404, request("GET", "/nope").statusCode())
    }

    @Test
    fun `the shell routes reject non-GET methods`() {
        val response = request("POST", "/")
        assertEquals(405, response.statusCode())
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").get())
    }

    @Test
    fun `HEAD answers every shell route with the GET's status and headers, minus the body`() {
        for (path in listOf("/", "/healthz", "/readyz", "/privacy", "/app.css", "/app.js", "/trading.js")) {
            val head = request("HEAD", path)
            assertEquals(request("GET", path).statusCode(), head.statusCode(), path)
            assertEquals("", head.body(), path)
        }
        assertEquals("text/html; charset=utf-8", request("HEAD", "/").headers().firstValue("Content-Type").get())
    }

    // Rejection happens before any handler runs, so the refused connection closes with no HTTP
    // status line — the client sees a connection-level failure, which is the documented contract.
    @Test
    fun `requests beyond the thread cap are refused rather than queued`() {
        val started = CountDownLatch(2)
        val hold = CountDownLatch(1)
        val holdingGateway =
            object : Gateway {
                override fun handles(path: String): Boolean = true

                override fun forward(exchange: HttpExchange) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.flush()
                    started.countDown()
                    hold.await()
                    exchange.responseBody.close()
                }
            }
        val saturated = DeskServer(WebAssets.load(), holdingGateway, port = 0, maxPoolThreads = 2)
        saturated.start()
        try {
            val streams =
                (1..2).map {
                    val connection =
                        URI(
                            "http://localhost:${saturated.boundPort}/orderbook/stream-$it",
                        ).toURL().openConnection() as HttpURLConnection
                    assertEquals(200, connection.responseCode)
                    connection
                }
            assertTrue(started.await(5, TimeUnit.SECONDS), "both held streams should be pinning pool threads")
            val request = HttpRequest.newBuilder(URI("http://localhost:${saturated.boundPort}/healthz")).GET().build()
            assertThrows(IOException::class.java) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            streams.forEach { it.disconnect() }
        } finally {
            hold.countDown()
            saturated.stop()
        }
    }

    @Test
    fun `an unexpected exception from the gateway still answers with a 500`() {
        val throwingGateway =
            object : Gateway {
                override fun handles(path: String): Boolean = true

                override fun forward(exchange: HttpExchange): Unit = throw IllegalStateException("boom")
            }
        val throwingServer = DeskServer(WebAssets.load(), throwingGateway, port = 0)
        throwingServer.start()
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://localhost:${throwingServer.boundPort}/orderbook/anything"))
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(500, response.statusCode())
            assertTrue(response.body().contains("internal error"))
        } finally {
            throwingServer.stop()
        }
    }
}
