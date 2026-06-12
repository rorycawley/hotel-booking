-- Per-row exponential backoff so a permanently-broken message does not
-- hot-loop the relay. After max_attempts the row is moved to dead_letter
-- and stops being polled - ops has to intervene to retry or discard.
alter table outbox
  add column if not exists next_attempt_at timestamptz not null default now(),
  add column if not exists max_attempts    int         not null default 10;

-- Pending = published_at is null AND we have not asked it to wait yet.
-- Index the pending+due rows together so the polling query is cheap.
drop index if exists outbox_pending_idx;
create index if not exists outbox_pending_due_idx
  on outbox (next_attempt_at) where published_at is null;

create table if not exists dead_letter_outbox (
  id              bigserial primary key,
  outbox_id       bigint      not null,
  message_id      uuid        not null unique,
  correlation_id  uuid        not null,
  causation_id    uuid,
  topic           text        not null,
  payload         jsonb       not null,
  attempts        int         not null,
  last_error      text,
  dead_lettered_at timestamptz not null default now()
);
