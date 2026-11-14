-- Version 5: several triggers per routine, follow-ups the agent sets (bounded retries), and routine notes.

-- A routine runs when any of its triggers is due. A cron trigger repeats; a once trigger is a follow-up the
-- agent asked for during a run ("try again in 5 minutes"): it fires once, as the next attempt of its parent.
create table if not exists routine_triggers (
    id            uuid primary key,
    routine_id    uuid not null references routines(id) on delete cascade,
    name          text not null,
    kind          text not null default 'cron',     -- cron | once
    schedule      text,                             -- cron triggers: 5-field cron in the routine's timezone
    enabled       boolean not null default true,
    max_attempts  int not null default 1,           -- cron triggers: how many tries a firing gets in all
    next_run_at   timestamptz,
    parent_id     uuid references routine_triggers(id) on delete cascade,   -- once triggers
    attempt       int,                              -- once triggers: which attempt this is
    reason        text,                             -- once triggers: why the agent asked for it
    position      int not null default 0,
    created_at    timestamptz not null default now()
);
create unique index if not exists routine_triggers_name on routine_triggers (routine_id, name) where kind = 'cron';
create index if not exists routine_triggers_due on routine_triggers (next_run_at) where enabled;

-- Every routine so far had one schedule: it becomes the trigger "main".
insert into routine_triggers (id, routine_id, name, kind, schedule, next_run_at)
select gen_random_uuid(), r.id, 'main', 'cron', r.schedule, r.next_run_at from routines r
where not exists (select 1 from routine_triggers t where t.routine_id = r.id);

alter table routines add column if not exists notes text not null default '';
alter table routine_versions add column if not exists triggers jsonb;
alter table routine_runs add column if not exists trigger_names text[];
alter table routine_runs add column if not exists attempt int not null default 1;
alter table routine_runs add column if not exists max_attempts int not null default 1;
