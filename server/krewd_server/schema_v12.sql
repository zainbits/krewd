-- Version 12: skip the next firings of a trigger ("skip today", "skip the next 3 runs").
-- While skip_runs > 0, a firing of the trigger is recorded as a skipped run and the count goes down by one.
alter table routine_triggers add column if not exists skip_runs int not null default 0;
