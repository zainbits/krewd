// Krewd fixed location. run-browser.sh copies this file to location.js and fills in the numbers from
// KREWD_BROWSER_LOCATION. A home server has no GPS or Wi-Fi, and Brave on Linux has no system location
// source, so without this every location request on the agents' Brave waits forever.
// Runs in the page's own world before any page script and answers the standard Geolocation API.
(() => {
  const LAT = __LAT__, LON = __LON__, ACC = __ACC__;
  const G = globalThis.Geolocation && Geolocation.prototype;
  if (!G) return; // not a secure context: the page has no location API anyway

  const data = (target, values) => {
    for (const [k, v] of Object.entries(values)) Object.defineProperty(target, k, { value: v, enumerable: true });
    return target;
  };
  const position = () => {
    const coords = data(Object.create(GeolocationCoordinates.prototype), {
      latitude: LAT, longitude: LON, accuracy: ACC,
      altitude: null, altitudeAccuracy: null, heading: null, speed: null,
    });
    Object.defineProperty(coords, "toJSON", { value: () => ({ latitude: LAT, longitude: LON, accuracy: ACC,
      altitude: null, altitudeAccuracy: null, heading: null, speed: null }) });
    const pos = data(Object.create(GeolocationPosition.prototype), { coords, timestamp: Date.now() });
    Object.defineProperty(pos, "toJSON", { value: () => ({ coords: coords.toJSON(), timestamp: pos.timestamp }) });
    return pos;
  };
  const answer = (success) => { if (typeof success === "function") setTimeout(() => success(position()), 0); };

  let nextWatch = 0;
  const watches = new Set();
  const methods = {
    getCurrentPosition(success, _error, _options) { answer(success); },
    watchPosition(success, _error, _options) {
      const id = ++nextWatch;
      watches.add(id);
      answer(success);
      return id;
    },
    clearWatch(id) { watches.delete(id); },
  };
  for (const [name, fn] of Object.entries(methods)) {
    Object.defineProperty(G, name, { value: fn, writable: true, configurable: true, enumerable: true });
  }

  // Pages often ask first whether location is allowed; say it is, so they go on to request it.
  const P = globalThis.Permissions && Permissions.prototype;
  if (P && P.query) {
    const query = P.query;
    Object.defineProperty(P, "query", {
      value: function (descriptor) {
        if (descriptor && descriptor.name === "geolocation") {
          return Promise.resolve({ name: "geolocation", state: "granted", onchange: null,
            addEventListener() {}, removeEventListener() {}, dispatchEvent() { return true; } });
        }
        return query.apply(this, arguments);
      },
      writable: true, configurable: true, enumerable: true,
    });
  }
})();
