-- Version 4: routines (instructions that run on a schedule, every run in the routine's own chat) and compaction.

create table if not exists routines (
    id            uuid primary key,
    title         text not null,
    instructions  text not null,
    schedule      text not null,                 -- 5-field cron, read in the routine's timezone
    timezone      text not null,
    session_id    uuid references sessions(id) on delete set null,   -- the one chat every run goes to
    engine        text not null,
    mode          text,
    config        jsonb not null default '{}',
    auto_approve  boolean not null default false,
    enabled       boolean not null default true,
    version       int not null default 1,        -- goes up when the title, instructions or schedule change
    next_run_at   timestamptz,
    last_run_at   timestamptz,
    created_by    text not null default 'owner', -- owner | agent
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
create index if not exists routines_due on routines (next_run_at) where enabled;

-- Every version of a routine's title, instructions and schedule, so a change can be read back or undone.
create table if not exists routine_versions (
    routine_id    uuid not null references routines(id) on delete cascade,
    version       int not null,
    title         text not null,
    instructions  text not null,
    schedule      text not null,
    timezone      text not null,
    changed_by    text not null default 'owner',
    note          text,
    created_at    timestamptz not null default now(),
    primary key (routine_id, version)
);

create table if not exists routine_runs (
    id            uuid primary key,
    routine_id    uuid not null references routines(id) on delete cascade,
    session_id    uuid references sessions(id) on delete set null,
    message_id    uuid,                           -- the message that carries the run into the chat
    trigger       text not null,                  -- schedule | manual
    version       int not null,
    scheduled_for timestamptz,
    status        text not null default 'queued', -- queued | running | done | error | cancelled | skipped
    summary       text,
    started_at    timestamptz,
    finished_at   timestamptz,
    created_at    timestamptz not null default now()
);
create index if not exists routine_runs_routine on routine_runs (routine_id, created_at desc);
create index if not exists routine_runs_message on routine_runs (message_id);

-- Messages Krewd sends on its own: a routine run or a compaction. Null means the owner wrote it.
alter table messages add column if not exists purpose text;
alter table messages add column if not exists meta jsonb;

alter table sessions add column if not exists commands jsonb;  -- slash commands the agent advertises
alter table sessions add column if not exists carry text;      -- summary handed to the next fresh agent session
alter table engines add column if not exists commands jsonb;
