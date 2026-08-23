package com.damianhoward.desk.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * HTTP transport for the trading desk. Serves the shell UI (`/`, `/app.css`, `/app.js`) and the
 * Trading tab's renderer (`/trading.js`), and hands everything under a service tab prefix
 * (`/orderbook`, `/trading`) to the [Gateway], which proxies it to the matching upstream. The two
 * tabs reach their services differently on purpose: the order book is a standalone site and is
 * embedded whole, while the ledger serves JSON only and is drawn here. Plumbing either way —
 * routing here, proxying in the gateway, rendering in the browser. `/healthz` proves the desk
 * process answers; `/readyz` (see [Readiness])
 * proves its upstreams are reachable, so a deploy whose tabs would 502 reads as not-ready.
 * `/metrics` publishes process-level facts only and never that upstream probe: see [ProcessMetrics]
 * for why a check worth running per probe is the wrong thing to run per scrape. JDK
 * [HttpServer] on a request pool capped at [maxPoolThreads]; each proxied SSE stream pins one pool
 * thread for its connection's lifetime, and requests beyond the cap are refused at the connection
 * rather than queued.
 */
class DeskServer(
    private val assets: WebAssets,
    private val gateway: Gateway,
    private val port: Int,
    private val readiness: Readiness? = null,
    private val maxPoolThreads: Int = 64,
    /**
     * Loopback by default: this server is meant to be reached through the reverse proxy that
     * terminates TLS, applies the security headers and writes the access log, never directly.
     * Binding every interface — which `InetSocketAddress(port)` alone does — makes that
     * guarantee depend on a firewall rule being right somewhere else. Appended last so existing
     * positional call sites are unaffected.
     */
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val processMetrics: ProcessMetrics = ProcessMetrics(),
) {
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    /** Binds and starts serving; requesting port 0 binds an ephemeral port (see [boundPort]). */
    fun start() {
        server = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
        // Cached-pool reuse and keep-alive but with a hard thread ceiling: SSE streams hold their
        // pool thread, so an unbounded pool lets slow-reading clients grow memory without limit.
        // No work queue — a request queued behind saturated SSE streams would wait forever, so
        // saturation refuses the new connection instead.
        executor =
            ThreadPoolExecutor(0, maxPoolThreads, 60L, TimeUnit.SECONDS, SynchronousQueue()) {
                Thread(it).apply { isDaemon = true }
            }
        server.executor = executor
        server.createContext("/", ::route)
        server.start()
        println("Trading desk listening on :$boundPort")
    }

    /** The port actually bound — differs from the requested one when 0 (ephemeral) was asked for. */
    val boundPort: Int get() = server.address.port

    /**
     * Stops accepting connections and shuts down the request pool this server created. Safe on a
     * server that never started: the shutdown hook runs whatever happened, and a failed bind would
     * otherwise raise an uninitialised-property error that buries the bind error underneath it.
     */
    fun stop() {
        if (!::server.isInitialized) return
        server.stop(0)
        executor.shutdownNow()
    }

    private fun route(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            when {
                path == "/healthz" -> get(exchange) { respond(exchange, 200, "text/plain", "ok") }
                path == "/readyz" -> get(exchange) { ready(exchange) }
                path == "/metrics" -> get(exchange) { respond(exchange, 200, ProcessMetrics.CONTENT_TYPE, processMetrics.render()) }
                path == "/" -> get(exchange) { respond(exchange, 200, "text/html; charset=utf-8", assets.indexHtml) }
                path == "/privacy" -> get(exchange) { respond(exchange, 200, "text/html; charset=utf-8", assets.privacyHtml) }
                path == "/app.css" -> get(exchange) { respond(exchange, 200, "text/css; charset=utf-8", assets.appCss) }
                path == "/app.js" -> get(exchange) { respond(exchange, 200, "text/javascript; charset=utf-8", assets.appJs) }
                // Matched before the gateway, and safe to be: the tab prefix is `/trading` or
                // `/trading/…`, so `/trading.js` was never a proxied path.
                path == "/trading.js" -> get(exchange) { respond(exchange, 200, "text/javascript; charset=utf-8", assets.tradingJs) }
                gateway.handles(path) -> gateway.forward(exchange)
                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: Exception) {
            // Anything unexpected must still answer the request — without this the connection
            // just closes with no status line. The stack goes to stderr -> journalctl.
            e.printStackTrace()
            runCatching { respond(exchange, 500, "application/json", """{"error":"internal error"}""") }
        }
    }

    // Liveness says the desk process answers; this says its upstreams are reachable. With no
    // readiness wired (a bare test server), it is a plain ready — there is nothing to probe.
    private fun ready(exchange: HttpExchange) {
        val probe = readiness?.probe()
        if (probe == null) {
            respond(exchange, 200, "application/json", """{"ready":true}""")
        } else {
            respond(exchange, if (probe.ready) 200 else 503, "application/json", probe.json)
        }
    }

    // HEAD rides every GET route: the handler runs identically and respond() suppresses the body,
    // so the status and headers a HEAD probe sees are the ones the GET would have produced.
    private inline fun get(
        exchange: HttpExchange,
        handler: () -> Unit,
    ) {
        if (exchange.requestMethod == "GET" || exchange.requestMethod == "HEAD") {
            handler()
        } else {
            exchange.responseHeaders.add("Allow", "GET, HEAD")
            respond(exchange, 405, "text/plain", "method not allowed")
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        if (exchange.requestMethod == "HEAD") {
            // Headers only: -1 tells the JDK server no body follows, which is the one length it
            // accepts on a HEAD without logging a warning (it drops Content-Length either way).
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

fun main() {
    val port = (System.getenv("PORT") ?: "8084").toInt()
    val upstreams = Upstreams.fromEnv(System.getenv())
    // Follow no redirects and cap the connect wait so an upstream that's down fails fast to a 502
    // rather than hanging the tab. No request timeout — the proxied SSE streams are open-ended.
    val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    // Readiness probes each upstream's /healthz on the same client, with a per-request timeout so a
    // hung upstream fails closed rather than stalling the probe. /healthz (not /readyz) is what
    // every service exposes and is the reachability signal the desk needs — can it serve this tab.
    val readiness =
        Readiness(upstreams) { base ->
            try {
                val probe =
                    HttpRequest
                        .newBuilder(base.resolve("/healthz"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build()
                client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
            } catch (_: Exception) {
                false
            }
        }
    val server = DeskServer(WebAssets.load(), ReverseProxy(upstreams, client), port, readiness)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start()
}
