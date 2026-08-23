# Deploying the trading desk

The desk shares a box with the order book, the risk view and the analytics service, and
reverse-proxies two upstreams under one origin at `desk.damianhoward.com`: the order book on the
same box, and position-ledger on the other. risk-engine is not a tab — the Risk tab was removed on
2026-07-17 and it stays live standalone at `risk.damianhoward.com`.

## Automated deploy

`.github/workflows/deploy.yml` runs on merge to `main` (or `workflow_dispatch`): it builds and
tests the distribution, then asks the box for a release over SSH against a pinned host key, with
the bundle on stdin. The release unpacks into `/srv/trading-desk/releases/<commit>` and
`/srv/trading-desk/current` moves onto it with a symlink rename; the box restarts the service and
requires a `/readyz` 200 that still holds 20 seconds later — the first probe after a restart reports the upstreams unhealthy while
they warm up. A release that fails that gate is rolled back to its predecessor.

Because that gate reads the upstreams, the desk's deploy depends on the rest of the estate being
quiet. Observed on 2026-07-26: five services were merged at once, orderbook restarted inside the
desk's 20-second hold window, readiness did not hold, and the desk correctly rolled back to its
previous release — `readiness did not hold` then `rollback healthy after 5 attempt(s)`. Nothing
was wrong with the release. Re-run the deploy once the other services have settled; deploy the
desk last when shipping several at once.
Secrets: `DEPLOY_SSH_KEY` (the box-1 `oracle_orderbook` key), `DEPLOY_HOST`, `DEPLOY_USER`.

## One-time host setup

1. **DNS** — a Cloudflare `desk.damianhoward.com` A record to the box, **DNS only / grey**
   (proxied breaks Caddy's ACME challenge).
2. **Caddy** — one operator step, not automatic. The host's Caddy configuration is
   version-controlled in the private infrastructure repository as one whole file per box, and
   applied by a script there that validates and backs up before reloading. A deploy of this
   service does not touch it, deliberately: a bad Caddyfile takes every site on the box down at
   once, which should not be reachable as a side effect of shipping one service. The desk adds no
   publicly reachable port; it binds loopback and is served through the proxy.
3. **Upstreams** — the loopback defaults cover the services sharing this box. position-ledger is on
   the other box, and the desk reaches it over its ordinary public TLS hostname rather than across
   the private network. Both work; the public route was chosen because the private one costs a
   standing ingress rule between the two boxes, and an always-open path is a poor trade for saving
   one TLS handshake on a proxy hop. The override lives in an environment file on the box.
4. **systemd** — the unit is host configuration, owned outside this repository and applied by an
   operator, because a unit file is a request to run anything as anyone and a deploy account able
   to install one holds root by another name. `JAVA_OPTS=-Xmx96m` (the desk is a proxy plus static
   assets — check `free -m` before adding it, as box 1 already runs three JVMs).

## Memory note

Box 1 is a ~1 GB Always-Free AMD micro already committing ~512 MB of heap across three JVMs.
`-Xmx96m` sizes the desk tight to fit; if `free -m` shows pressure, trim one of the existing heaps
or add swap. A tactical compromise of the 1 GB box, not the design.
