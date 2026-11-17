// Krewd location: the Krewd server opens this page in a background tab (over DevTools), reads its answer from
// the title and closes it. set.html#read reports the location; set.html#<JSON> applies one first, where JSON
// is {lat, lon, accuracy}, or null to clear it. The server does this only when the owner applies a location in
// the app (and once when Brave starts, to put back that same location if the profile lost it).
// The title becomes "krewd-location:" + {"ok": true, "location": ...} or {"ok": false}.
// Web pages cannot open or frame this page: it is not a web-accessible resource.
(async () => {
  const report = (answer) => { document.title = "krewd-location:" + JSON.stringify(answer); };
  try {
    const fragment = decodeURIComponent(location.hash.slice(1));
    if (fragment !== "read") {
      const value = JSON.parse(fragment);
      if (value === null) {
        await chrome.storage.local.remove("location");
      } else {
        const { lat, lon, accuracy } = value;
        if (![lat, lon, accuracy].every(Number.isFinite) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || accuracy <= 0) {
          throw new Error("bad location");
        }
        await chrome.storage.local.set({ location: { lat, lon, accuracy } });
      }
    }
    const { location: stored } = await chrome.storage.local.get("location");
    report({ ok: true, location: stored ?? null });
  } catch (_) {
    report({ ok: false });
  }
})();
