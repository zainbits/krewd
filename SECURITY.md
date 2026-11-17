# Security policy

Krewd runs AI agents with a terminal and a browser on your machine, so security reports matter a lot to us.

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private vulnerability reporting instead: the
**Security** tab of this repository, then **Report a vulnerability**. Include the version (`VERSION`
or `GET /api/health`), what an attacker needs (network position, a paired phone, local access) and
the steps to reproduce it.

You will get an answer within 7 days. Fixes go into the next release, and we credit reporters who
want it.

## What is in scope

- The Krewd server: authentication, phone pairing and sessions, the presence check, sign-in requests
  (`krewd secret`), TLS and its pinning, the HTTP API, files and attachments, the browser bridge.
- The Android app: storage of keys and settings, the connect link, certificate pinning, the secure
  sign-in sheet.
- The deploy scripts and the systemd units.

Out of scope: the agents themselves (Claude Code, Codex and others), browser-harness, and what an
agent you allowed to act does with that permission. Report those to their own projects.

## How Krewd protects you

- Phones pair only with an owner key: the owner's FIDO security key or their phone passkey (synced by the
  phone's password manager, so whoever controls that account can use it; the security key alone is the
  stronger choice). Each phone proves itself with a key kept in
  its secure hardware, and sensitive requests also need the owner's fingerprint.
- Every network address is HTTPS with Krewd's own certificate, which the app pins. Plain HTTP is
  served only on loopback.
- Gateway keys and passwords never come back from the server and are never logged. Passwords for
  sign-in requests go from the phone straight into the page; the agent never sees them.
- All config lives in `~/.config/krewd/` with mode 600 files. Never commit or share it.
