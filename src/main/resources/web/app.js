/*
 * Trading desk shell controller. The desk owns the chrome and switches tabs. A tab is either a
 * service's own front end in an iframe (the order book, which is a standalone site in its own
 * right) or a view this shell renders itself from the service's JSON — see trading.js. Every tab
 * stays mounted and only visibility toggles, so a tab's SSE stream keeps running while you're
 * looking at another.
 */
(function () {
  "use strict";

  var TABS = ["orderbook", "trading"];
  var LABELS = { orderbook: "order book", trading: "trading" };
  var STORAGE_KEY = "desk.tab";

  var tabs = Array.prototype.slice.call(document.querySelectorAll(".tab"));
  // Both kinds of tab body: an embedded iframe and a natively rendered section.
  var panes = Array.prototype.slice.call(
    document.querySelectorAll("[data-frame]"),
  );
  // Status-bar items that describe one tab rather than the desk as a whole.
  var scoped = Array.prototype.slice.call(
    document.querySelectorAll(".statusbar [data-for]"),
  );
  var activeLabel = document.getElementById("active-label");
  var activeTab = null;

  function showScoped(item) {
    item.hidden =
      item.dataset.for !== activeTab || item.dataset.suppressed === "1";
  }

  function activate(name) {
    if (TABS.indexOf(name) === -1) return;
    activeTab = name;
    tabs.forEach(function (t) {
      var on = t.dataset.tab === name;
      t.classList.toggle("active", on);
      t.setAttribute("aria-selected", on ? "true" : "false");
    });
    panes.forEach(function (f) {
      f.classList.toggle("active", f.dataset.frame === name);
    });
    // A scoped item belongs to one tab; some also suppress themselves on their own condition
    // (the dead-letter flag, while its count is zero), so both tests apply.
    scoped.forEach(showScoped);
    if (activeLabel) activeLabel.textContent = LABELS[name];
    try {
      localStorage.setItem(STORAGE_KEY, name);
      var url = new URL(window.location.href);
      url.searchParams.set("tab", name);
      window.history.replaceState(null, "", url);
    } catch {
      /* storage or history unavailable — tab still switches */
    }
  }

  tabs.forEach(function (t) {
    t.addEventListener("click", function () {
      activate(t.dataset.tab);
    });
  });

  // Number keys switch tabs while the chrome has focus. Keystrokes inside an embedded tab stay
  // with that app (iframe events don't reach the parent), which is what a trader typing wants.
  document.addEventListener("keydown", function (e) {
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    var index = ["1", "2"].indexOf(e.key);
    if (index !== -1) activate(TABS[index]);
  });

  // The chrome's connection light goes live once any tab has reached its service — an embedded
  // frame loading, or a natively rendered view opening its stream.
  var connected = false;
  function markLive() {
    if (connected) return;
    connected = true;
    ["conn", "conn2"].forEach(function (id) {
      var dot = document.getElementById(id);
      if (dot) dot.classList.add("live");
    });
    ["connlbl", "connlbl2"].forEach(function (id) {
      var label = document.getElementById(id);
      if (label) label.textContent = "live";
    });
  }
  panes.forEach(function (f) {
    if (f.tagName === "IFRAME") f.addEventListener("load", markLive);
  });

  // Desk clock, HH:MM:SS local time.
  var clock = document.getElementById("clock");
  function tick() {
    var d = new Date();
    var p = function (n) {
      return String(n).padStart(2, "0");
    };
    clock.textContent =
      p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }
  tick();
  setInterval(tick, 1000);

  // Restore the last tab: ?tab= wins, then the stored choice, then the default (order book).
  var requested = null;
  try {
    requested =
      new URL(window.location.href).searchParams.get("tab") ||
      localStorage.getItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
  activate(
    requested && TABS.indexOf(requested) !== -1 ? requested : "orderbook",
  );

  // The seam a natively rendered view uses: report that it reached its service, and suppress a
  // status item its own state says is not worth a column. Tab state stays private to this file —
  // a view says what it knows, and the shell decides what that means for what is on screen.
  window.deskShell = {
    markLive: markLive,
    suppress: function (id, suppressed) {
      var item = document.getElementById(id);
      if (!item) return;
      item.dataset.suppressed = suppressed ? "1" : "0";
      showScoped(item);
    },
  };
})();
