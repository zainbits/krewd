-- Version 3: durable messages with delivery state, steering support per engine, the agent's browser tab.

-- Every message the owner sends. A message stays 'queued' until its turn starts ('delivered', how 'started')
-- or the agent confirms it was steered into the running turn ('delivered', how 'steered'). Queued messages
-- survive a restart. client_id makes a resend from the phone idempotent.
create table if not exists messages (
    id            uuid primary key,
    session_id    uuid not null references sessions(id) on delete cascade,
    client_id     text,
    event_seq     bigint,
    text          text not null default '',
    attachments   jsonb not null default '[]',
    tabs          jsonb not null default '[]',
    delivery      text not null default 'queue',    -- steer | queue | interrupt: what the owner asked for
    state         text not null default 'queued',   -- queued | steering | delivered | cancelled
    delivered_how text,                             -- started | steered
    priority      int not null default 1,           -- 0 for "interrupt and send"
    created_at    timestamptz not null default now(),
    delivered_at  timestamptz
);
create unique index if not exists messages_client on messages (session_id, client_id) where client_id is not null;
create index if not exists messages_queued on messages (session_id, priority, created_at) where state = 'queued';

alter table engines add column if not exists steering boolean;
alter table sessions add column if not exists browser_tab jsonb;   -- {id, url, title}: the tab the agent last used
