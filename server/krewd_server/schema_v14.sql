-- Version 14: push notifications (FCM), alert levels and the owner's "Needs you" items.

-- The phones' push tokens (FCM registration tokens). A token is dropped when FCM says it is gone, when its phone is
-- removed, or after 60 days without the app checking in.
create table if not exists push_tokens (
    token         text primary key,
    device_id     uuid references devices(id) on delete cascade,  -- null: registered with the setup or local token
    platform      text not null default 'fcm',
    app_version   text,
    created_at    timestamptz not null default now(),
    last_seen_at  timestamptz not null default now(),
    last_error    text
);

-- Things that need the owner: added by agents (`krewd needs add`) or by Krewd itself (a routine run that failed or
-- waits). One card per key: the same key again updates the card instead of adding one.
create table if not exists needs_items (
    id               uuid primary key,
    key              text not null unique,
    title            text not null,
    source           text not null default '',
    link             text,
    due_at           timestamptz,
    level            text not null default 'action' check (level in ('info', 'action', 'urgent', 'critical')),
    suggested_level  text check (suggested_level in ('info', 'action', 'urgent', 'critical')),
    status           text not null default 'open' check (status in ('open', 'done', 'dismissed')),
    snoozed_until    timestamptz,
    session_id       uuid references sessions(id) on delete set null,
    routine_id       uuid references routines(id) on delete set null,
    created_by       text not null default 'agent' check (created_by in ('agent', 'krewd', 'owner')),
    seen_count       int not null default 1,
    reminded_at      timestamptz,   -- the "due soon" push went out
    snooze_pushed    boolean not null default true,  -- false while a snooze waits to end with a push
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    closed_at        timestamptz
);
create index if not exists needs_items_open on needs_items (status, due_at);

-- Every notification Krewd sent (or showed as a card only, for info). A push carries only its id; the phone fetches
-- the rest from here. Kept 14 days.
create table if not exists alerts (
    id           uuid primary key,
    kind         text not null,     -- approval | secret | proposal | error | finished | run_done | run_failed | run_waiting | needs | test
    level        text not null check (level in ('info', 'action', 'urgent', 'critical')),
    title        text not null,
    body         text not null default '',
    session_id   uuid references sessions(id) on delete set null,
    needs_id     uuid references needs_items(id) on delete cascade,
    ref_id       text,              -- the approval, sign-in request or proposal it is about
    pushed       int not null default 0,    -- phones the push was accepted for
    push_error   text,
    created_at   timestamptz not null default now(),
    acked_at     timestamptz        -- the owner opened it (or tapped Got it)
);
create index if not exists alerts_created on alerts (created_at);

-- The owner's own rule: a failed run of this routine rings as a Critical alarm (a time-bound job, like a check-in).
alter table routines add column if not exists alarm_on_fail boolean not null default false;

-- Alert and needs events for no particular chat (an item added from a terminal) have no session.
alter table events alter column session_id drop not null;
