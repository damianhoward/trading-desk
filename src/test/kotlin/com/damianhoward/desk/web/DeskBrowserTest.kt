package com.damianhoward.desk.web

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * The desk in a headless Chromium over a real [DeskServer]: tab switching, the embedded order book
 * and the trading view drawn from the ledger's stream. [DeskServerTest] pins routing; this pins
 * `app.js` and `trading.js` turning what the upstreams send into a working screen.
 *
 * The gateway stands in for both upstreams. The order book is a page to embed, so it answers with
 * one; the ledger is a stream, so it sends one snapshot frame and holds the connection open, as
 * the real stream does between fills.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeskBrowserTest {
    private class FakeUpstreams : Gateway {
        @Volatile var deadLetters = 0
        private val release = CountDownLatch(1)

        override fun handles(path: String): Boolean = path.startsWith("/orderbook") || path.startsWith("/trading")

        override fun forward(exchange: HttpExchange) {
            if (exchange.requestURI.path == "/trading/api/stream") stream(exchange) else embed(exchange)
        }

        fun releaseStreams() = release.countDown()

        private fun embed(exchange: HttpExchange) {
            val html = "<!doctype html><html><body><p id=\"embedded\">order book</p></body></html>"
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private fun stream(exchange: HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write("data: ${snapshot(deadLetters)}\n\n".toByteArray(StandardCharsets.UTF_8))
                body.flush()
                release.await(30, TimeUnit.SECONDS)
            }
        }
    }

    private lateinit var upstreams: FakeUpstreams
    private lateinit var server: DeskServer
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    @BeforeAll
    fun launch() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun close() {
        browser.close()
        playwright.close()
    }

    @BeforeEach
    fun start() {
        upstreams = FakeUpstreams()
        server = DeskServer(WebAssets.load(), upstreams, port = 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        upstreams.releaseStreams()
        server.stop()
    }

    private fun open(query: String = ""): Page {
        val page = browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 900)).newPage()
        page.navigate("http://127.0.0.1:${server.boundPort}/$query")
        return page
    }

    private fun tab(
        page: Page,
        name: String,
    ) = page.locator(".tab[data-tab=$name]")

    @Test
    fun `opens on the order book, embedded, and goes live when it loads`() {
        val page = open()
        assertThat(tab(page, "orderbook")).hasAttribute("aria-selected", "true")
        assertThat(page.frameLocator("iframe[data-frame=orderbook]").locator("#embedded")).hasText("order book")
        assertThat(page.locator("#connlbl")).hasText("live")
        assertThat(page.locator("#active-label")).hasText("order book")
    }

    @Test
    fun `the trading tab draws the ledger's stream`() {
        val page = open()
        tab(page, "trading").click()

        assertThat(tab(page, "trading")).hasAttribute("aria-selected", "true")
        assertThat(page).hasURL(Pattern.compile("tab=trading"))
        assertThat(page.locator("#stream")).hasText("live")
        assertThat(page.locator("#st-instruments")).hasText("2")
        assertThat(page.locator("#st-valuation")).hasText("-6,500.00")
        assertThat(page.locator("#positions tbody tr")).hasCount(2)
        // AAPL is short past its position limit; MSFT is inside both limits.
        assertThat(page.locator("#exposure tbody tr").filter(Locator.FilterOptions().setHasText("AAPL"))).containsText("BREACH")
        assertThat(page.locator("#exposure tbody tr").filter(Locator.FilterOptions().setHasText("MSFT"))).containsText("OK")
        assertThat(page.locator("#sync")).hasText("in sync")
    }

    @Test
    fun `number keys switch tabs, and the choice survives a reload`() {
        val page = open()
        page.keyboard().press("2")
        assertThat(tab(page, "trading")).hasAttribute("aria-selected", "true")

        page.reload()
        assertThat(tab(page, "trading")).hasAttribute("aria-selected", "true")

        page.keyboard().press("1")
        assertThat(tab(page, "orderbook")).hasAttribute("aria-selected", "true")
    }

    @Test
    fun `dead letters stay out of the status bar while there are none`() {
        val page = open("?tab=trading")
        assertThat(page.locator("#updates")).hasText("1")
        assertThat(page.locator("#dlt-item")).isHidden()
    }

    @Test
    fun `dead letters show in the status bar, and only on the trading tab`() {
        upstreams.deadLetters = 3
        val page = open("?tab=trading")
        assertThat(page.locator("#dlt")).hasText("3")
        assertThat(page.locator("#dlt-item")).isVisible()

        tab(page, "orderbook").click()
        assertThat(page.locator("#dlt-item")).isHidden()
    }

    private companion object {
        // Shaped like the ledger's /api/stream frame; values chosen so every assertion above has
        // one right answer. AAPL: short 20 against a max position of 10. MSFT: long 5, inside both.
        fun snapshot(deadLetters: Int): String =
            """
            {"v":2,
             "positions":[
               {"symbol":"AAPL","quantity":-20,"lastPrice":300.0,"lastTimeMillis":${System.currentTimeMillis()}},
               {"symbol":"MSFT","quantity":5,"lastPrice":100.0,"lastTimeMillis":${System.currentTimeMillis()}}],
             "book":{"valuation":-6500.0,"grossNotional":6500.0,"dayPnl":0,
               "symbols":[${report("AAPL", -6000.0)},${report("MSFT", -500.0)}]},
             "exposure":{"maxPosition":10,"maxNotional":10000,"malformed":0,"breaches":1,
               "progress":{"offset":7,"fillTs":1},
               "symbols":[
                 {"symbol":"AAPL","netQuantity":-20,"lastPrice":300.0,"notional":6000.0,
                  "positionUtilisation":2.0,"notionalUtilisation":0.6,"breached":true},
                 {"symbol":"MSFT","netQuantity":5,"lastPrice":100.0,"notional":500.0,
                  "positionUtilisation":0.5,"notionalUtilisation":0.05,"breached":false}],
               "events":[]},
             "sync":{"positions":{"offset":7,"fillTs":1},"exposure":{"offset":7,"fillTs":1},
               "coherent":true,"duplicatesDropped":0,"deadLetters":$deadLetters}}
            """.trimIndent().replace("\n", "")

        fun report(
            symbol: String,
            valuation: Double,
        ): String =
            """
            {"symbol":"$symbol","openPrice":null,"report":{"valuation":$valuation,
              "greeks":{"delta":1.0,"gamma":0.0,"vega":0.0,"theta":0.0,"rho":0.0},
              "confidence":0.99,
              "var":{"parametric":{"valueAtRisk":10.0,"expectedShortfall":12.0},
                     "historical":{"valueAtRisk":9.0,"expectedShortfall":11.0}}}}
            """.trimIndent()
    }
}
