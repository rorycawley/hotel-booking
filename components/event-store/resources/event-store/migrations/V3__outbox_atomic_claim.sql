-- Atomic claim semantics. The previous SELECT-FOR-UPDATE-SKIP-LOCKED
-- only locked rows for the duration of the SELECT statement (autocommit
-- mode), so concurrent relays could and did get the SAME rows back -
-- the comment in postgres.clj even acknowledged it. With claimed_at, a
-- single UPDATE-with-RETURNING atomically marks rows in flight; a row
-- stays excluded from future claims until mark-published clears
-- published_at or mark-failed clears claimed_at + defers next_attempt_at.
--
-- A relay that died mid-publish leaves claimed_at set forever; the
-- 10-minute staleness rule in the claim query reclaims those rows so
-- the broker still sees them (at-least-once).

alter table outbox
  add column if not exists claimed_at timestamptz;

-- Partial index for the hot path: pending + due + not claimed.
drop index if exists outbox_pending_due_idx;
create index if not exists outbox_claimable_idx
  on outbox (id)
  where published_at is null and claimed_at is null;
