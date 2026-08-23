package com.damianhoward.desk.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebAssetsTest {
    @Test
    fun `loads the shell assets from the classpath`() {
        val assets = WebAssets.load()
        assertTrue(assets.indexHtml.contains("TRADING DESK"), "index should carry the desk brand")
        assertTrue(assets.indexHtml.contains("/orderbook/?embed=1"), "index should embed the order book tab")
        assertTrue(assets.appCss.contains("--brand: #14b8a6"), "css should carry the shared brand token")
        assertTrue(assets.appJs.contains("activate"), "js should carry the tab controller")
        assertTrue(assets.tradingJs.contains("/trading/api/stream"), "trading view should stream through the gateway")
    }

    /**
     * The shell and the Trading view are wired to each other by name across two files, so a rename
     * on one side would otherwise be found only in a browser. These are the two names that cross.
     */
    @Test
    fun `the trading view and the shell agree on the seam between them`() {
        val assets = WebAssets.load()
        assertTrue(assets.appJs.contains("window.deskShell"), "shell should publish the view seam")
        assertTrue(assets.tradingJs.contains("window.deskShell.markLive"), "view should report reaching its service")
        assertTrue(assets.tradingJs.contains("window.deskShell.suppress"), "view should hand status suppression to the shell")
    }

    /** The Trading tab is rendered here, so its markup has to exist for [tradingJs] to fill in. */
    @Test
    fun `the index carries the trading view rather than embedding it`() {
        val assets = WebAssets.load()
        assertTrue(assets.indexHtml.contains("""data-frame="trading""""), "index should hold the trading pane")
        assertTrue(assets.indexHtml.contains("""id="report""""), "trading pane should hold the risk report target")
        assertTrue(!assets.indexHtml.contains("/trading/?embed=1"), "the trading tab is no longer an iframe")
    }
}
