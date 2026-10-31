/*
 * Added to Plan's own page by XaliChat. On the pages that show a plugin's card (a player's plugins on one server, a
 * server's or the network's plugins, all of them or XaliChat's alone) it puts the log, framed from /xalichat/, into
 * XaliChat's card: that player's entries on a player's page, that server's on a server's. A right click on an entry there can open it in its server's
 * log, which is the same frame on that server's plugins page with the entry focused.
 *
 * Nothing here is secret and the log checks who is asking, so a user without access sees no frame at all.
 */
(function () {
  "use strict";
  var BASE = "/xalichat/";
  var CARD = "XaliChat";
  var PLUGIN = "xalichat";
  var allowed = null;
  var servers = null;
  var mounting = false;

  function json(url) {
    return fetch(url, { credentials: "same-origin", cache: "no-store" }).then(function (res) {
      if (!res.ok) throw new Error(url + " answered " + res.status);
      return res.json();
    });
  }

  // Whether this user may see the log, asked once: the log answers 403 to anyone who may not.
  function permitted() {
    if (!allowed) allowed = fetch(BASE + "log?kinds=c&limit=1", { credentials: "same-origin", cache: "no-store" }).then(function (r) { return r.ok; }, function () { return false; });
    return allowed;
  }

  // Plan's servers with their uuids and names, from the same answer Plan's own pages use.
  function planServers() {
    if (!servers) {
      servers = json("/v1/networkMetadata").then(function (data) { return data.servers || []; }, function () {
        return json("/v1/metadata").then(function (m) { return [{ serverUUID: m.serverUUID, serverName: m.serverName, proxy: !!m.isProxy }]; }, function () { return []; });
      });
    }
    return servers;
  }

  // Whether a path segment names this plugin, as on the pages of one plugin (/server/<uuid>/plugins/XaliChat).
  function isOurs(segment) {
    try { return decodeURIComponent(segment).toLowerCase() === PLUGIN; } catch (e) { return false; }
  }

  // What the page being shown is about, or null for a page that has no plugin cards.
  function scope() {
    var path = location.pathname;
    var m = path.match(/^\/player\/([^/]+)\/plugins\/([^/]+)/);
    if (m) return { player: decodeURIComponent(m[1]), serverName: decodeURIComponent(m[2]) };
    m = path.match(/^\/server\/([^/]+)\/plugins(-overview|\/([^/]+))\/?$/);
    if (m && (m[2] === "-overview" || isOurs(m[3]))) return { serverUuid: decodeURIComponent(m[1]) };
    m = path.match(/^\/network\/plugins(-overview|\/([^/]+))\/?$/);
    if (m && (m[1] === "-overview" || isOurs(m[2]))) return {};
    return null;
  }

  // The element of XaliChat's card the frame goes in, or null while the card isn't there. Plan 5.9 marks the card with
  // the plugin's name; 5.8 doesn't, and shows a plugin's own page inside #extension-<name>, so those are found by
  // their heading.
  function cardBody() {
    var card = document.getElementById(CARD);
    if (card && card.classList.contains("extension-wrapper")) return { card: card, body: card.querySelector(".collapse") || card.querySelector(".card") };
    var alone = document.getElementById("extension-" + CARD);
    if (alone) return { card: alone, body: alone.querySelector(".card") };
    var wrappers = document.querySelectorAll(".extension-wrapper");
    for (var i = 0; i < wrappers.length; i++) {
      var heading = wrappers[i].querySelector(".card-header h6");
      if (heading && heading.textContent.trim() === CARD) return { card: wrappers[i], body: wrappers[i].querySelector(".card") };
    }
    return null;
  }

  function frameUrl(scope, serverName, focus) {
    var url = BASE + "?embed=1";
    if (scope.player) url += "&player=" + encodeURIComponent(scope.player);
    if (serverName) url += "&server=" + encodeURIComponent(serverName);
    if (focus) url += "&focus=" + encodeURIComponent(focus);
    return url;
  }

  async function mount() {
    if (mounting) return;
    var where = scope();
    if (!where) return;
    var found = cardBody();
    if (!found || !found.body) return;
    var card = found.card;
    var body = found.body;
    var identity = location.pathname + location.search;
    var existing = body.querySelector(":scope > .xalichat-embed");
    if (existing && existing.dataset.identity === identity) return;

    mounting = true;
    try {
      if (!(await permitted())) return;
      var serverName = where.serverName;
      if (where.serverUuid) {
        var found = (await planServers()).find(function (s) { return s.serverUUID === where.serverUuid; });
        // A proxy has no log of its own: it shows every server's.
        serverName = found && !found.proxy ? found.serverName : undefined;
      }
      if (!document.body.contains(card)) return;
      var focus = new URLSearchParams(location.search).get("xalichat");
      if (existing) existing.remove();
      var box = document.createElement("div");
      box.className = "xalichat-embed";
      box.dataset.identity = identity;
      box.style.cssText = "padding: 0 1rem 1rem;";
      var frame = document.createElement("iframe");
      frame.title = "XaliChat log";
      frame.src = frameUrl(where, serverName, focus);
      frame.style.cssText = "width: 100%; height: 540px; border: 1px solid var(--color-cards-border); border-radius: .35rem; background: var(--color-cards-background);";
      box.appendChild(frame);
      body.appendChild(box);
      // Plan lays its cards out in columns by their height, so it is told this one grew.
      setTimeout(function () { window.dispatchEvent(new Event("resize")); }, 50);
      if (focus) card.scrollIntoView({ block: "start" });
    } finally {
      mounting = false;
    }
  }

  // An entry was opened in its server's log from a frame.
  window.addEventListener("message", async function (event) {
    var data = event.data;
    if (event.origin !== location.origin || !data || data.xalichat !== "open" || typeof data.key !== "string") return;
    var name = typeof data.server === "string" ? data.server.toLowerCase() : "";
    var target = (await planServers()).find(function (s) { return !s.proxy && s.serverName.toLowerCase() === name; });
    location.assign(target
      ? "/server/" + encodeURIComponent(target.serverUUID) + "/plugins-overview?xalichat=" + encodeURIComponent(data.key) + "#" + CARD
      : BASE + "?focus=" + encodeURIComponent(data.key));
  });

  var queued = false;
  function schedule() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () { queued = false; mount(); });
  }
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  schedule();
})();
