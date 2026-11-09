-- Version 2: harness, model and effort. ACP config options are agent-defined, so they are stored as
-- the agent advertised them; config_wanted holds the user's picks, re-applied after an agent restart.

alter table sessions add column if not exists agent jsonb;              -- agentInfo: name, title, version
alter table sessions add column if not exists config_options jsonb;     -- normalised ACP configOptions
alter table sessions add column if not exists config_wanted jsonb not null default '{}';
alter table sessions add column if not exists model_used text;          -- the model the agent reports it ran

-- What each engine offers, so a new task can show its choices before it has a session.
create table if not exists engines (
    id              text primary key,
    agent           jsonb,
    config_options  jsonb,
    modes           jsonb,
    updated_at      timestamptz not null default now()
);
