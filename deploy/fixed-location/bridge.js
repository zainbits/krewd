// Krewd location: hands the applied location from the extension's storage to location.js in the page's world.
// Runs in the extension's isolated world, the only one that can read chrome.storage.
(() => {
  let current; // undefined until storage answers
  const send = () => document.dispatchEvent(new CustomEvent("krewd-location", { detail: JSON.stringify(current) }));
  const take = (value) => {
    current = value && typeof value === "object" ? { lat: value.lat, lon: value.lon, accuracy: value.accuracy } : null;
    send();
  };
  // A failed read is tried again (5 times, 300 ms apart) before the page hears "none": with "none" it would ask
  // Brave's own location API, which a home server cannot answer.
  const read = (tries) => chrome.storage.local.get("location").then(
    ({ location }) => take(location),
    () => (tries > 0 ? setTimeout(() => read(tries - 1), 300) : take(null)),
  );
  read(5);
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && "location" in changes) take(changes.location.newValue);
  });
  document.addEventListener("krewd-location-ask", () => { if (current !== undefined) send(); });
})();
