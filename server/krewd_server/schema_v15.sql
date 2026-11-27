-- Version 15: a chat can use a person's own Brave (`krewd browser use NAME`); null is the agents' usual one (main).
alter table sessions add column if not exists browser text;
