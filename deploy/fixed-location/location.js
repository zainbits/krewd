// Krewd location: answers the page's Geolocation API with the location the owner applied in the Krewd app.
// A home server has no GPS or Wi-Fi, and Brave on Linux has no system location source, so without this every
// location request on the agents' Brave waits forever.
// Runs in the page's own world before any page script. The location comes from bridge.js (the extension's
// storage, which the server sets only when the owner taps Apply) as a "krewd-location" event: a JSON string,
// or null when none is set, in which case requests go to the browser's own location API.
(() => {
  const G = globalThis.Geolocation && Geolocation.prototype;
  if (!G) return; // not a secure context: the page has no location API anyway
  const native = { get: G.getCurrentPosition, watch: G.watchPosition, clear: G.clearWatch };

  let loc; // undefined: not known yet; null: none set; else {lat, lon, accuracy}
  const waiting = [];
  const whenKnown = (fn) => (loc === undefined ? waiting.push(fn) : fn());
  const settle = (next) => {
    loc = next;
    waiting.splice(0).forEach((fn) => fn());
  };
  const valid = (v) => v && [v.lat, v.lon, v.accuracy].every(Number.isFinite)
    && Math.abs(v.lat) <= 90 && Math.abs(v.lon) <= 180 && v.accuracy > 0;

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
  const answer = (success) => { if (typeof success === "function") setTimeout(() => success(position()), 0); };

  let nextWatch = 0;
  const watches = new Map(); // our id -> {success, nativeId}
  const methods = {
    getCurrentPosition(success, error, options) {
      whenKnown(() => (loc ? answer(success) : native.get.call(this, success, error, options)));
    },
    watchPosition(success, error, options) {
      const id = ++nextWatch;
      const watch = { success, nativeId: null };
      watches.set(id, watch);
      whenKnown(() => {
        if (!watches.has(id)) return;
        if (loc) answer(success);
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
    const changed = loc !== undefined;
    settle(next);
    // A newly applied location also reaches pages that are watching the position.
    if (changed && loc) for (const watch of watches.values()) if (watch.nativeId === null) answer(watch.success);
  });
  document.dispatchEvent(new CustomEvent("krewd-location-ask"));
  // If the extension's storage never answers, do not hold the page's requests forever.
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
