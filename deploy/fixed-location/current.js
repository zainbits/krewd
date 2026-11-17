// Krewd location: the location the owner applied in the Krewd app, so location.js has it the instant a page
// starts (no wait for the extension's storage). Brave loads a copy of this extension from Krewd's config folder
// (run-browser.sh), where the server keeps this file holding the current location; Brave reads it when it starts.
// This copy in the repo holds none: never put a real location here.
globalThis.__krewdLocation = null;
