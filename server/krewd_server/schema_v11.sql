-- Version 11: which chat owns each tab of the agents' browser, kept across restarts.
-- An agent claims a tab when browser-harness first touches it in a call (or the watcher links a tab that navigates);
-- while that chat's turn runs, the phone only watches the tab and other chats are refused it.
create table if not exists browser_tabs (
    tab_id text primary key,
    session_id uuid not null references sessions(id) on delete cascade,
    updated_at timestamptz not null default now()
);
create index if not exists browser_tabs_session on browser_tabs (session_id);
