-- Version 13: voice input. The agent session that has been given Krewd's voice rules (in front of the first voice
-- message it got), so a fresh agent session (compact, switch) gets them again with its first voice message.
alter table sessions add column if not exists voice_rules_for text;
