-- Actor identity (gap #2) AND event-payload version (gap #6).
-- Together because both alter events and we ship them as one wave.
--
-- actor_id: who performed the command that produced this event. NULL
--   tolerated for legacy rows from before the column existed - going
--   forward every accepted command MUST carry one. The auth layer
--   enforces presence; the column doesn't, so we don't break replay.
--
-- payload_v: the version of the event payload's SCHEMA. Lets the
--   read-side run an upcaster registry to bring older payloads forward
--   to the version the current decider understands. Default 1 covers
--   every event already in the log.
alter table events
  add column if not exists actor_id  text,
  add column if not exists payload_v int not null default 1;

create index if not exists events_actor_idx on events (actor_id);
