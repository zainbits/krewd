-- Version 10: every routine run in its own chat.
-- A run chat is made for one run (and its follow-up attempts). The next run of the routine deletes it, unless the
-- run failed, waits for the owner, or the owner wrote in it, pinned it or is still using it.
alter table sessions add column if not exists run_chat boolean not null default false;
-- What a run reported about itself (`krewd routines result`): failed | waiting. Null: it did the job.
alter table routine_runs add column if not exists result text;
alter table routine_runs add column if not exists result_reason text;
