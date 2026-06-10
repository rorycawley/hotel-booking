-- Event store: one append-only table.
-- UNIQUE (stream_id, version) gives optimistic concurrency.
create table if not exists events (
  global_position bigserial primary key,
  stream_id       text    not null,
  version         bigint  not null,
  payload         jsonb   not null,
  unique (stream_id, version)
);
