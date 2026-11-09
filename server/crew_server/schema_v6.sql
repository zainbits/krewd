-- Version 6: one chat, several agents. The chat's current agent lives on sessions (engine, agent_session,
-- mode, config...); each agent the chat used before keeps its own session and settings here, with the last
-- event of the chat it saw, so switching back resumes its own session and only hears what it missed.
create table if not exists session_agents (
    session_id      uuid not null references sessions(id) on delete cascade,
    engine          text not null,
    agent_session   text,
    mode            text,
    config_wanted   jsonb not null default '{}',
    config_options  jsonb,
    modes           jsonb,
    agent           jsonb,
    commands        jsonb,
    last_seq        bigint not null default 0,
    updated_at      timestamptz not null default now(),
    primary key (session_id, engine)
);
