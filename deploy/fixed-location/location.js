// Krewd location: answers the page's Geolocation API with the location the owner applied in the Krewd app.
// A home server has no GPS or Wi-Fi, and Brave on Linux has no system location source, so without this every
// location request on the agents' Brave waits forever.
// Runs in the page's own world before any page script. The location is there at once from current.js, which the
// server writes when the owner taps Apply. bridge.js also sends the one in the extension's storage as a
// "krewd-location" event (a JSON string, or null when none is set, in which case requests go to the browser's
// own location API), which is how a newly applied location reaches pages that are already open.
(() => {
  const G = globalThis.Geolocation && Geolocation.prototype;
  if (!G) return; // not a secure context: the page has no location API anyway
  const native = { get: G.getCurrentPosition, watch: G.watchPosition, clear: G.clearWatch };

  // current.js (run just before this file) holds the applied location, so pages have it from their first
  // instant. The storage (through bridge.js) is a second source, and brings a newly applied one to open pages.
  const fixed = globalThis.__krewdLocation;
  try { delete globalThis.__krewdLocation; } catch (_) { /* not there */ }
  let loc; // undefined: not known yet; null: none set; else {lat, lon, accuracy}
  const waiting = [];
  const whenKnown = (fn) => (loc === undefined ? waiting.push(fn) : fn());
  const settle = (next) => {
    loc = next;
    waiting.splice(0).forEach((fn) => fn());
  };
  const valid = (v) => v && [v.lat, v.lon, v.accuracy].every(Number.isFinite)
    && Math.abs(v.lat) <= 90 && Math.abs(v.lon) <= 180 && v.accuracy > 0;
  if (valid(fixed)) loc = { lat: fixed.lat, lon: fixed.lon, accuracy: fixed.accuracy };

  const data = (target, values) => {
    for (const [k, v] of Object.entries(values)) Object.defineProperty(target, k, { value: v, enumerable: true });
    return target;
  };
  const position = () => {
    const plain = { latitude: loc.lat, longitude: loc.lon, accuracy: loc.accuracy,
      altitude: null, altitudeAccuracy: null, heading: null, speed: null };
    const coords = data(Object.create(GeolocationCoordinates.prototype), plain);
    Object.defineProperty(coords, "toJSON", { value: () => ({ ...plain }) });
    const pos = data(Object.create(GeolocationPosition.prototype), { coords, timestamp: Date.now() });
    Object.defineProperty(pos, "toJSON", { value: () => ({ coords: coords.toJSON(), timestamp: pos.timestamp }) });
    return pos;
  };
  const failure = (code, message) => data(Object.create(GeolocationPositionError.prototype), {
    code, message, PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 });

  // Every request ends: with the location as soon as it is known (also when it arrives late, after Brave's own
  // location API was asked), with Brave's own answer when no location is set, or with an error at the page's
  // timeout (15 s when it gives none). Brave on a home server has no location source, and a request that never
  // ends locks pages such as a web attendance check-in until they are reloaded.
  const pending = new Set(); // one-shot requests not answered yet
  const request = (self, success, error, options) => {
    const req = { success, error, done: false, nativeId: null };
    const finish = (fn, value) => {
      if (req.done) return;
      req.done = true;
      pending.delete(req);
      clearTimeout(req.timer);
      if (req.nativeId !== null) native.clear.call(self, req.nativeId);
      if (typeof fn === "function") setTimeout(() => fn(value), 0);
    };
    req.answer = () => finish(success, position());
    pending.add(req);
    const ms = options && Number.isFinite(options.timeout) ? Math.max(0, options.timeout) : 15000;
    req.timer = setTimeout(() => finish(error, failure(3, "Timeout expired")), ms);
    whenKnown(() => {
      if (req.done) return;
      if (loc) return req.answer();
      // No location set: Brave's own API, through a watch so it can be stopped when the location arrives.
      req.nativeId = native.watch.call(self, (p) => finish(success, p), (e) => finish(error, e), options);
    });
  };

  let nextWatch = 0;
  const watches = new Map(); // our id -> {success, nativeId}
  const methods = {
    getCurrentPosition(success, error, options) {
      request(this, success, error, options);
    },
    watchPosition(success, error, options) {
      const id = ++nextWatch;
      const watch = { success, nativeId: null };
      watches.set(id, watch);
      whenKnown(() => {
        if (!watches.has(id)) return;
        if (loc) { if (typeof success === "function") setTimeout(() => success(position()), 0); }
        else watch.nativeId = native.watch.call(this, success, error, options);
      });
      return id;
    },
    clearWatch(id) {
      const watch = watches.get(id);
      watches.delete(id);
      if (watch && watch.nativeId !== null) native.clear.call(this, watch.nativeId);
    },
  };
  for (const [name, fn] of Object.entries(methods)) {
    Object.defineProperty(G, name, { value: fn, writable: true, configurable: true, enumerable: true });
  }

  document.addEventListener("krewd-location", (event) => {
    let next;
    try { next = JSON.parse(event.detail); } catch (_) { return; }
    if (next !== null && !valid(next)) return;
    if (loc && next === null) return; // a late "none" never takes back a location this page already has
    const known = loc !== undefined; // the first answer reaches waiting watches through settle() already
    settle(next);
    if (!loc) return;
    // The location reaches requests still waiting (also those handed to Brave's own API) and watching pages.
    for (const req of [...pending]) req.answer();
    for (const watch of watches.values()) {
      if (!known && watch.nativeId === null) continue;
      if (watch.nativeId !== null) { native.clear.call(navigator.geolocation, watch.nativeId); watch.nativeId = null; }
      if (typeof watch.success === "function") setTimeout(() => watch.success(position()), 0);
    }
  });
  // Ask the bridge until it answers: its storage read can be slow while a heavy page loads. After 3 s without an
  // answer, requests go to Brave's own API, and the location still reaches them when it comes.
  const ask = () => document.dispatchEvent(new CustomEvent("krewd-location-ask"));
  ask();
  let asks = 0;
  const again = setInterval(() => { if ((loc !== undefined && loc !== null) || ++asks > 60) clearInterval(again); else ask(); }, 500);
  setTimeout(() => { if (loc === undefined) settle(null); }, 3000);

  // Pages often ask first whether location is allowed; with a location set, say it is.
  const P = globalThis.Permissions && Permissions.prototype;
  if (P && P.query) {
    const query = P.query;
    Object.defineProperty(P, "query", {
      value: function (descriptor) {
        if (!descriptor || descriptor.name !== "geolocation") return query.apply(this, arguments);
        const args = arguments;
        return new Promise((resolve) => whenKnown(resolve)).then(() => (loc
          ? { name: "geolocation", state: "granted", onchange: null,
              addEventListener() {}, removeEventListener() {}, dispatchEvent() { return true; } }
          : query.apply(this, args)));
      },
      writable: true, configurable: true, enumerable: true,
    });
  }
})();
