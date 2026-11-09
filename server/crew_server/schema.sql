-- Crew schema, version 1. Applied once by db.migrate(); later versions append their own blocks.

create table if not exists sessions (
    id              uuid primary key,
    engine          text not null,
    agent_session   text,                 -- the agent's own ACP session id
    title           text not null default '',
    title_locked    boolean not null default false,
    status          text not null default 'idle',   -- idle | running | waiting | error
    mode            text,                 -- ACP mode id the user picked
    modes           jsonb,                -- the agent's advertised modes
    auto_approve    boolean not null default false,
    usage           jsonb,
    last_text       text not null default '',
    pinned          boolean not null default false,
    archived        boolean not null default false,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- Durable, append-only timeline. Clients replay it by seq, so a phone can disconnect any time.
create table if not exists events (
    seq         bigserial primary key,
    session_id  uuid not null references sessions(id) on delete cascade,
    kind        text not null,
    payload     jsonb not null,
    created_at  timestamptz not null default now()
);
create index if not exists events_session_seq on events (session_id, seq);

create table if not exists approvals (
    id          uuid primary key,
    session_id  uuid not null references sessions(id) on delete cascade,
    tool        jsonb not null,
    options     jsonb not null,
    status      text not null default 'pending',  -- pending | approved | denied | cancelled | expired
    option_id   text,
    decided_by  text,                             -- user | auto | system
    created_at  timestamptz not null default now(),
    decided_at  timestamptz
);
create index if not exists approvals_pending on approvals (status) where status = 'pending';
