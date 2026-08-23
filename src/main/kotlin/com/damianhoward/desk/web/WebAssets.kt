package com.damianhoward.desk.web

import java.nio.charset.StandardCharsets

/**
 * The static shell (`src/main/resources/web`), read once from the classpath at startup.
 *
 * [tradingJs] is separate from [appJs] because they are different jobs: `app.js` is the shell —
 * tabs, clock, chrome, and nothing that knows a domain — while `trading.js` renders one service's
 * snapshot. Folding them together would put ledger vocabulary in the file that switches tabs.
 */
class WebAssets private constructor(
    val indexHtml: String,
    val appCss: String,
    val appJs: String,
    val tradingJs: String,
    val privacyHtml: String,
) {
    companion object {
        fun load(): WebAssets =
            WebAssets(
                read("/web/index.html"),
                read("/web/app.css"),
                read("/web/app.js"),
                read("/web/trading.js"),
                read("/web/privacy.html"),
            )

        private fun read(path: String): String =
            (WebAssets::class.java.getResourceAsStream(path) ?: error("missing resource: $path"))
                .use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
