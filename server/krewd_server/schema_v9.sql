-- Version 9: chats that refer to and work for each other.
-- A side session is an ordinary chat with a parent. With `returns` on (a sub-agent), each reply it finishes goes back
-- to the parent; with it off (a side session), nothing goes back and the parent reads it when asked.
alter table sessions add column if not exists parent_id uuid references sessions(id) on delete set null;
alter table sessions add column if not exists returns boolean not null default false;
create index if not exists sessions_parent on sessions (parent_id) where parent_id is not null;

-- Chats the owner attached to a message with @: [{id, title, relation}].
alter table messages add column if not exists chats jsonb not null default '[]';
-- A reply owed: the chat that gets the reply of the turn this message ran in (or was steered into), and when that
-- reply (or a note saying why there is none) was sent there. Not a foreign key: a deleted chat simply gets nothing.
alter table messages add column if not exists return_to uuid;
alter table messages add column if not exists returned_at timestamptz;
create index if not exists messages_owed on messages (return_to) where return_to is not null and returned_at is null;

-- What an agent proposes and only the owner starts: a new side session or sub-agent (kind `new`), or a message to
-- another chat (kind `prompt`). Pending until the owner sends or dismisses it; void when its target chat is deleted.
create table if not exists proposals (
    id          uuid primary key,
    session_id  uuid not null references sessions(id) on delete cascade,  -- the chat whose agent proposed it
    kind        text not null check (kind in ('new', 'prompt')),
    target_id   uuid references sessions(id) on delete set null,          -- kind prompt: the chat to message
    title       text not null default '',
    text        text not null,
    engine      text,
    returns     boolean not null default false,
    status      text not null default 'pending' check (status in ('pending', 'sent', 'dismissed', 'void')),
    result_id   uuid,                                                     -- the chat it started or messaged
    created_at  timestamptz not null default now(),
    decided_at  timestamptz
);
create index if not exists proposals_pending on proposals (created_at) where status = 'pending';
