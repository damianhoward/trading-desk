# trading-desk

[![CI](https://github.com/damianhoward/trading-desk/actions/workflows/ci.yml/badge.svg)](https://github.com/damianhoward/trading-desk/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damianhoward/trading-desk/actions/workflows/codeql.yml/badge.svg)](https://github.com/damianhoward/trading-desk/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/damianhoward/trading-desk/graph/badge.svg)](https://codecov.io/gh/damianhoward/trading-desk)

One trading workspace over two services. The desk presents the live order book and the trading
screen — positions, risk, and PnL off the fill stream — as tabs in a single browser page:
one chrome, one status bar, one origin.

Live at **[desk.damianhoward.com](https://desk.damianhoward.com)**.

## What it is

A composing gateway. The desk owns no domain logic: [`orderbook`](https://github.com/damianhoward/orderbook)
and [`position-ledger`](https://github.com/damianhoward/position-ledger) stay standalone, independently
deployed services. This module serves the shell UI and reverse-proxies each service under a tab
prefix, so the browser talks to one origin while each backend runs untouched. The trading tab's
risk numbers come from the [`risk-engine`](https://github.com/damianhoward/risk-engine) library,
which also runs standalone at [risk.damianhoward.com](https://risk.damianhoward.com).

```
Browser ──▶ desk.damianhoward.com
             ├─ /                     shell: topbar + tab bar + status
             ├─ /trading.js           the Trading tab's renderer, served here
             ├─ /orderbook/**  ─▶  order book service   (live book, SSE — embedded whole)
             └─ /trading/**    ─▶  position-ledger        (JSON + SSE — drawn by the desk)
```

The two tabs reach their services differently, because the services are different. The order book
is a standalone public site with its own front end, so the desk embeds it in an iframe with its
chrome hidden (`?embed=1`) and adds nothing. The trading service serves JSON and an SSE stream and
no HTML at all, so the desk draws it: `trading.js` renders the snapshot that `/trading/api/stream`
pushes. Both tabs stay mounted, so a tab's live stream keeps running while the other is in view.

Which one a service gets follows from whether its front end has a second audience. The order book's
does — it is a demo people are sent a link to — and duplicating its renderer here would leave two
copies of one screen to keep in step. The ledger's does not: nobody visits it directly, so its
screen belongs where it is looked at, and the service is left owning the ledger rather than also
owning a page.

Embedding still buys the things it bought before, and only for the tab that uses it: orderbook stays
independently deployed with its own release cycle, a keystroke stays with the app being typed into
rather than reaching the shell, and a child that fails renders a broken tab instead of a broken desk.
Proxying is what makes it one origin either way, which is what lets `frame-ancestors` stay `'self'`
rather than being opened up to a second hostname.

## Routing and streaming

`ReverseProxy` matches a tab prefix, strips it, and forwards the request to the resolved upstream —
`/orderbook/api/AAPL/stream` becomes `/api/AAPL/stream` against the order book service. The response
body is streamed chunk-by-chunk with a flush after each write, so an upstream `text/event-stream`
reaches the browser as frames are produced rather than being buffered until the connection closes.
An upstream that can't be reached maps to a `502` before any bytes are sent; a client that hangs up
mid-stream ends the copy.

Upstream bases come from the environment (`ORDERBOOK_UPSTREAM` / `TRADING_UPSTREAM`), defaulting
to the box-local ports, so the same artifact runs against loopback in a test and box-local (or
cross-box) URLs in production.

## Build and run

```bash
./gradlew --no-daemon spotlessCheck   # ktlint + Prettier (web assets, YAML, Markdown)
./gradlew --no-daemon clean build     # tests, 90% coverage gate, and packaging — what CI runs
./gradlew installDist && PORT=8084 build/install/trading-desk/bin/trading-desk
```

With the two services running on their default ports, open `http://localhost:8084`.

## Tests

`ReverseProxy` is exercised over a loopback `HttpServer` upstream — prefix rewrite, query and body
forwarding, `502` on an unreachable upstream, and SSE frames delivered as they are produced (an
upstream gate holds the second frame until the first has arrived downstream, so buffering would
fail the test). `DeskServer` routing and `Upstreams` resolution are covered directly.
