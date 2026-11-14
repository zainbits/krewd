-- Version 7: the owner's phones (each with its own keys, added with the owner's security key) and the app's
-- settings kept on the server, so a new phone starts where the old one was.
create table if not exists devices (
    id            uuid primary key,
    name          text not null,
    device_key    bytea not null,       -- the phone's key for sessions (P-256 SubjectPublicKeyInfo, DER)
    presence_key  bytea not null,       -- the phone's key that needs the owner's fingerprint or screen lock
    proof         jsonb not null,       -- the security key's signature over this phone's keys (see security.py)
    created_at    timestamptz not null default now(),
    last_seen     timestamptz,
    revoked_at    timestamptz
);

create table if not exists app_prefs (
    key         text primary key,
    value       jsonb not null,
    updated_at  timestamptz not null default now()
);
