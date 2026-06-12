-- ============================================================================
-- One schema, one journal-shaped database. All tables here are append-only
-- (insert + selective updates only on bookkeeping columns) - never UPDATE the
-- truth of a row. The shared datasource lets the booking-side TX cover
-- events + outbox + processed_commands atomically (the transactional outbox
-- pattern: events and the message-to-publish commit together or not at all).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Events: append-only journal. UNIQUE (stream_id, version) -> optimistic
-- concurrency. event_id / correlation_id / causation_id are first-class
-- columns so the shell can recover them without parsing JSON.
-- ----------------------------------------------------------------------------
create table if not exists events (
  global_position bigserial primary key,
  stream_id       text       not null,
  version         bigint     not null,
  event_id        uuid       not null unique,
  correlation_id  uuid       not null,
  causation_id    uuid,
  payload         jsonb      not null,
  unique (stream_id, version)
);
create index if not exists events_correlation_idx on events (correlation_id);

-- ----------------------------------------------------------------------------
-- Transactional outbox. A row is INSERTED in the SAME TX as the events that
-- caused it. A separate relay reads pending rows, publishes them to the
-- broker, then sets published_at. published_at is the ONLY mutable column.
-- ----------------------------------------------------------------------------
create table if not exists outbox (
  id              bigserial primary key,
  message_id      uuid       not null unique,
  correlation_id  uuid       not null,
  causation_id    uuid,
  topic           text       not null,
  payload         jsonb      not null,
  enqueued_at     timestamptz not null default now(),
  published_at    timestamptz,
  attempts        int        not null default 0,
  last_error      text
);
create index if not exists outbox_pending_idx
  on outbox (id) where published_at is null;

-- ----------------------------------------------------------------------------
-- Command idempotency. A command_id is recorded with its result. Retries
-- with the same id return the cached result without re-running the decider.
-- ----------------------------------------------------------------------------
create table if not exists processed_commands (
  command_id      uuid        primary key,
  command_type    text        not null,
  result          jsonb       not null,
  processed_at    timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- Inbox: messages we've CONSUMED from external systems. Dedupes redelivery
-- by message_id. processed_at flips once handled.
-- ----------------------------------------------------------------------------
create table if not exists inbox (
  message_id      uuid        primary key,
  topic           text        not null,
  payload         jsonb       not null,
  received_at     timestamptz not null default now(),
  processed_at    timestamptz
);

-- ----------------------------------------------------------------------------
-- Projection checkpoints: each projector remembers the last global_position
-- it has applied. Replay = TRUNCATE the view + delete checkpoint.
-- ----------------------------------------------------------------------------
create table if not exists projection_checkpoints (
  projection_name text        primary key,
  last_position   bigint      not null default 0,
  updated_at      timestamptz not null default now()
);

-- ----------------------------------------------------------------------------
-- Room view: persistent CQRS read model for "which rooms are available".
-- Rebuilt by replay; queries read this table, never the event log.
-- ----------------------------------------------------------------------------
create table if not exists room_view (
  room_id     text primary key,
  status      text not null,
  guest_name  text,
  guest_email text,
  check_in    text,
  check_out   text
);
