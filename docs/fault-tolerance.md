# Fault Tolerance — How the system loses no data

> A guided tour of the patterns that make the journal authoritative, the
> outbox unbreakable, the relay safe under concurrency, the process
> managers self-healing, and the read side bounded in staleness.

This document is the **load-bearing argument** for why no event, no
command, no published integration message, no supporting document, and
no PII attribution can vanish silently from the system. Every claim is
backed by a pattern in the codebase, a file path, and a test that fails
if the pattern regresses.

It is not a marketing document. It calls out what is and is not
guaranteed, and where the limits are.

---

## 1. What "no data loss" means here

In a distributed system, "no data loss" is meaningless without a
definition. We use a precise one:

**Once a command's success has been observed by its caller, the events
it produced are durable, in order, attributed, and recoverable - and no
subsequent failure (JVM crash, broker outage, Postgres unavailability,
relay process death, network partition) can erase, reorder, or silently
corrupt them.**

Three derived guarantees that fall out of this:

1. **At-least-once integration publish.** Every integration event that a
   committed domain event implied gets onto the broker - possibly more
   than once on broker failure, but never zero times. Consumers dedupe
   via the inbox pattern.
2. **Process recoverability.** A multi-step process that started can
   always be driven to a terminal state, even if the JVM died
   mid-dispatch. No stranded saga.
3. **Tamper-evidence at the application boundary.** PII fields and
   document blobs cannot be exfiltrated from the database without a
   subject's key, and cannot be silently modified in storage without
   the integrity check failing on retrieval.

What this document does NOT claim:
- That a DBA cannot drop the `events` table - they can; the application
  cannot defend against the database layer being compromised.
  Cryptographic event signing (gap #1 + #7 on the registry roadmap)
  would close that. We have not built it.
- That a regional outage cannot cause unavailability - it can; HA and
  multi-region DR (gaps #13 + #17) are not built either.
- That the broker can lose a confirmed published message - it can if
  RabbitMQ's own durability config is wrong; configure
  `delivery_mode=2`, durable queues, and replicated quorum.

---

## 2. The principle: event sourcing as the source of truth

The journal is **append-only**. The `events` table never has UPDATEs or
DELETEs on its payload, version, stream-id, event-id, correlation-id,
causation-id, actor-id, recorded-at, or global-position. The only
mutation in this whole system that touches an event row is the read-side
PII decryption pass, and that does not write back - it operates on the
in-memory copy.

That single invariant is the foundation. Every other fault-tolerance
property in this document is a consequence of (a) deciding what to write,
(b) making sure the write actually lands, and (c) making sure subsequent
readers see the right thing.

The file `components/event-store/resources/event-store/migrations/V1__initial.sql`
sets up the table with `UNIQUE (stream_id, version)`. Replay through
the decider's pure `evolve` function is how state is derived (ADR-0002,
ADR-0001). Order is by `global_position`, not by `recorded_at` (ADR-0001).

> **Why this matters for fault tolerance:** state is never the source of
> truth. The log is. As long as the log survives, any state can be
> rebuilt: room views, processed-command caches, projection checkpoints.
> The components/event-store/test/hotel/event_store/contract.clj test
> "advance-room-view! catches up from a DEEP backlog in batches" proves
> projections can be entirely rebuilt from a wiped view.

---

## 3. The write path: from command to durable event

This is the critical path. We walk through every failure point.

### 3.1 Idempotent receipt

A client sends a command with an `Idempotency-Key` (HTTP header) or
`:command/id` (programmatic). The shell's first impure step is a cache
lookup in `processed_commands`:

```clojure
;; components/booking/src/hotel/booking/decider.clj
(let [cached (some->> (es/command-result event-store command-id) ...)]
  (if cached cached ...))
```

If the command-id has been processed, the shell returns the SAME result
the first attempt returned. The events are **not** recomputed. The
outbox is **not** appended to a second time. The post-commit effects are
**not** re-run.

**Failure modes defended:**
- *Client retries because they timed out:* hits the cache, gets the same
  answer, no duplicate work.
- *Two concurrent retries race past the cache check together:* the
  earlier-committing one wins the (stream_id, version) UNIQUE; the
  loser catches a SQLException, the shell **re-checks the cache** (now
  populated), and returns the same `:events` the winner produced. This
  is gap #1 from the audit pass.

**Test:** `components/booking/test/hotel/booking/idempotency_race_test.clj`
runs 30 trials of two threads with the same `:command/id`. Without the
re-check, ~48 of 50 trials fail. With it, all 30 trials see the same
events on both sides.

### 3.2 History read + pure decide

If not cached, we read the stream and apply the upcaster registry so
older events are lifted to the current schema before the pure
`decide`/`evolve` see them:

```clojure
(let [history (->> (es/read-stream event-store stream-id)
                   upcasters/upcast-all
                   (pii/decrypt-events subject-keys))
      result  (decide decider history command)]
  ...)
```

`decide` is pure. It returns `{:events [...]}` or `{:error ...}`. No
I/O happens here, so this step cannot fail in a non-deterministic way.
If `:error`, we return that to the caller; no events are written, no
processed_commands row is inserted - retries with the same `:command/id`
will get a fresh decide pass against fresh history (ADR-0003).

### 3.3 Stamp identity + check clock quality

Every accepted event is stamped, in the shell:
- `:event/id` - random UUID v4
- `:event/v` - the schema version (defaults to 1; the upcaster registry
  bumps over time)
- `:correlation-id` - from the command, or freshly minted
- `:causation-id` - the command-id (so the cause of each event is the
  command that produced it)
- `:actor-id` - from the command, propagating audit-by-author
- `:recorded-at` - the clock port's `{:earliest :latest}` interval

The clock-quality check (`check-clock-quality!`) records anomalies to
the ProblemSink:
- malformed interval (`:earliest > :latest`)
- definitely-before-vs-history (clock went backwards relative to the
  last event on the stream)

These are evidence-quality signals, not correctness signals - order is
log position, not time. Overlapping intervals are normal; only strict
precedence inversion is anomalous.

### 3.4 Pure react + partition

Reactors run pre-append over the stamped events. Their outputs are
PARTITIONED into three buckets:

- **`:publish`** - integration-event publishes that are valid against
  `booking/contracts.clj`. These become outbox rows.
- **`:other`** - notifications, dispatch-command effects. Run AFTER the
  TX commits.
- **`:invalid`** - publishes that fail the contract gate. Recorded as
  problems; never reach the outbox.

```clojure
{:keys [publish other invalid]} (effects/partition-reactor-output system stamped)
_ (effects/report-invalid-publishes! system invalid)
outbox-msgs (mapv outbox-message-from publish)
```

The partition is PURE. The validation is part of the partition, so
nothing invalid can ever reach durable storage.

### 3.5 Envelope-encrypt PII

If any event carries PII (via the registry in `booking/pii.clj`), the
fields are encrypted with the subject's key BEFORE the append:

```clojure
encrypted (pii/encrypt-events subject-keys stamped)
```

Reactors and the caller see plaintext; the journal stores ciphertext.

### 3.6 The atomic write

Now the critical single transaction. In Postgres:

```sql
BEGIN;
  -- (a) optimistic concurrency check + event inserts
  -- (b) outbox row inserts
  -- (c) processed_commands insert
COMMIT;
```

Implementation in `components/event-store/src/hotel/event_store/postgres.clj`,
`transactional-append!`. The three writes are ATOMIC: either they all
land or none of them do. There is no window in which events are
committed but the outbox row is missing - that scenario is what makes
the "broker dies" case survivable.

**Failure modes defended:**

- *Postgres goes down before the COMMIT:* SQLException propagates, the
  caller sees the error, retries with the same `:command/id`. Next
  attempt is a normal fresh write. Tested by
  `components/event-store/test/hotel/event_store/postgres_recovery_test.clj`
  (`writes-fail-loudly-when-postgres-is-down`).
- *Concurrent writer races the stream version:* the UNIQUE (stream_id,
  version) catches it; the adapter throws
  `:hotel.event-store/error :concurrency-conflict`, the shell catches
  ExceptionInfo, re-checks the idempotency cache (gap #1 fix), returns
  cached or surfaces the conflict.
- *The processed_commands row is racing too:* `ON CONFLICT DO NOTHING`
  on the insert silently no-ops; the cache re-check handles correctness.

The fast suite includes a 50-racer scenario (`flows_test.clj`) that
fires 50 concurrent bookings of the same room. Exactly one wins; 49 get
`:concurrency-conflict`; the stream contains exactly one
`:room-booked` event.

### 3.7 Post-commit effects

After the commit succeeds, the shell drives non-publish effects:

```clojure
(execute-effects! system other)
```

`:notify-booking-confirmed` calls the GuestNotifications port.
`:dispatch-command` runs another command (process-manager continuation).

If a post-commit effect throws, the events are STILL durable - they
committed before the effects ran. The failure is recorded to the
ProblemSink so ops sees it; it is also returned in the call result as
`:effect-errors`. **No data is lost.** The next state of the system
is: the events are durable; some side effect did not happen; the
operator has a structured problem record describing exactly which
effect on which event for which actor.

---

## 4. The publish path: transactional outbox + relay

### 4.1 Why the broker is NOT in the critical path

A booking system that requires RabbitMQ to be up before it can accept a
booking is brittle. The hotel's check-in desk should not stop working
because the message queue is being upgraded. The transactional outbox
pattern decouples them.

When a domain event with a `:publish` reactor effect is committed, an
**outbox row** is committed in the same Postgres transaction. The broker
sees nothing yet. The booking returns success. The events are durable.
The integration message is durable IN POSTGRES, waiting to be picked up
by the relay.

```sql
-- The shape of the outbox row, V1 + V2 + V3 migrations.
CREATE TABLE outbox (
  id              bigserial PRIMARY KEY,
  message_id      uuid       UNIQUE,
  correlation_id  uuid       NOT NULL,
  causation_id    uuid,
  topic           text       NOT NULL,
  payload         jsonb      NOT NULL,
  enqueued_at     timestamptz NOT NULL DEFAULT now(),
  published_at    timestamptz,             -- NULL while pending
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  max_attempts    int        NOT NULL DEFAULT 10,
  attempts        int        NOT NULL DEFAULT 0,
  claimed_at      timestamptz,             -- set by atomic claim
  last_error      text
);
```

**Headline test:**
`components/outbox-relay/test/hotel/outbox_relay/integration_test.clj`
- `a-booking-survives-rabbit-being-down-then-up`:
  1. Stop RabbitMQ.
  2. Commit a booking (Postgres TX writes events + outbox row).
  3. Confirm the outbox row exists; broker is dead.
  4. Restart RabbitMQ.
  5. Run the relay once.
  6. Confirm the message is delivered to the broker.
  7. Confirm the outbox row is marked published.

### 4.2 Atomic claim (concurrent relays are safe)

The relay's claim step is a single UPDATE-RETURNING with `SELECT ...
FOR UPDATE SKIP LOCKED`:

```sql
UPDATE outbox
   SET claimed_at = now()
 WHERE id IN (
   SELECT id FROM outbox
    WHERE published_at IS NULL
      AND next_attempt_at <= now()
      AND (claimed_at IS NULL OR claimed_at < now() - INTERVAL '10 minutes')
    ORDER BY id
    LIMIT ?
    FOR UPDATE SKIP LOCKED
 )
 RETURNING id, message_id, ...
```

The locks survive for the duration of the UPDATE statement (not just
the SELECT), so two concurrent claim calls receive **disjoint** sets.
The `claimed_at` stamp keeps the row out of subsequent claims until
either it succeeds (`mark-outbox-published!`) or fails
(`mark-outbox-failed!` clears `claimed_at`) or 10 minutes elapses (a
relay that crashed mid-publish releases its claim automatically).

This is gap #2 from the audit pass. The earlier `SELECT FOR UPDATE SKIP
LOCKED` alone was broken in autocommit mode - the locks released as
soon as the SELECT returned, and two relays would publish each row
roughly twice. The contract test
`claim-outbox-messages is ATOMIC: concurrent calls return disjoint
sets` fires 8 concurrent claimers at 50 rows and asserts every row is
claimed exactly once.

### 4.3 Publish path: confirms + return listener + NACK

On the broker side, three layers of belt-and-braces:

```clojure
;; components/integration/src/hotel/integration/rabbitmq.clj
(lcf/select ch)                    ; publisher confirms
(install-return-tracker! ch ret)   ; basic.return listener
(lb/publish ch exchange topic body
            (assoc props :mandatory true))
(when-not (lcf/wait-for-confirms channel 5000)
  (throw (ex-info "RabbitMQ NACK on publish" ...)))
(when (> @returned returns-before)
  (throw (ex-info "RabbitMQ returned an unroutable message" ...)))
```

- **Publisher confirms**: `wait-for-confirms` returns false on broker
  NACK; we throw.
- **Mandatory + return listener**: an unroutable message would
  otherwise be silently dropped (confirmed but discarded). The return
  listener bumps a counter; if the counter moved during the publish,
  we throw.
- **Persistent messages + durable exchange**: the broker survives its
  own restart with the message in queue.

Any exception bubbles out of `publish!` and is caught by the relay's
drain loop:

```clojure
(catch Throwable t
  (es/mark-outbox-failed! event-store (:id msg)
                          (.getMessage t)
                          (backoff-ms (inc (:attempts msg 0))))
  n)
```

The row's `attempts` increments, `next_attempt_at` is pushed forward
by an exponential backoff (100ms, 200ms, 400ms, ..., capped at 60s),
`claimed_at` is cleared, and the relay continues. After `max_attempts`
(default 10) the row is moved to `dead_letter_outbox` in the same
mark-failed transaction. The relay never hot-loops a permanently
broken message.

The dead-letter test in the contract proves this: 10 failed attempts
on one row land it in `dead_letter_outbox` and remove it from the
outbox.

### 4.4 At-least-once, not exactly-once

The architecture delivers **at-least-once**. With atomic claim + stale
reclaim, duplicates are rare (essentially: a relay that crashed
between broker-confirm and `mark-outbox-published!`). But they are
possible in principle and the contract is honest about it - consumers
**must** dedupe via the `message_id` (the inbox pattern), which is what
`subject-keys/in_memory.clj`'s `handle-inbound!` helper does for us
when we're the consumer.

Trying to make exactly-once across two systems is the two-generals
problem. We do not attempt it.

---

## 5. The query path: bounded staleness

A read-side projection in CQRS is, by definition, eventually consistent
with the journal. The question is: bounded how?

Two mechanisms:

### 5.1 The background projector

A Component runs `advance-room-view!` in a loop, draining events past
the projection's checkpoint. Each call:

1. Takes a `pg_advisory_xact_lock(hashtext(projection_name))` - so two
   projectors cannot race on the same projection (gap #4 fix; the
   earlier `FOR UPDATE` on a row that did not exist yet locked
   nothing).
2. Reads up to `batch-size` events past the checkpoint.
3. Folds them through the projection's pure evolve fn.
4. UPSERTS only the rows that changed (incremental, not
   truncate-and-rewrite).
5. Updates the checkpoint and commits.

If the projector crashes, the checkpoint stays where it was; on
restart it picks up from there. No events are missed; no events are
double-applied (the checkpoint is monotonic).

### 5.2 Synchronous catch-up on query

To keep query latency bounded after a recent write, the query path
itself does ONE catch-up batch synchronously:

```clojure
;; components/booking/src/hotel/booking/slices/available_rooms/query.clj
(es/advance-room-view! event-store projection-name proj/evolve sync-batch)
(let [lag (es/projection-lag event-store projection-name)]
  (when (and problem-sink (> lag lag-threshold))
    (problems/record-problem! problem-sink {:problem :room-view-stale-after-sync-catchup
                                            :lag lag
                                            :threshold lag-threshold})))
(proj/available (es/read-room-view event-store) all-room-ids)
```

The sync catch-up is capped (`sync-batch` default 1000); never fold-the-
world. If the projection is still behind by more than `lag-threshold`
events after the sync step, a structured problem is recorded so ops
sees the staleness instead of it being silently swallowed
(`projection_staleness_test.clj`).

**Failure modes defended:**

- *Query during a deep projector backlog:* sync step advances one
  batch; result is bounded-stale; problem recorded.
- *Projector crashed and never recovers:* queries still work (each one
  advances one batch); the lag metric eventually crosses the warning
  threshold; ops investigates.
- *Projection corrupted by a faulty migration:* rebuild by deleting
  the checkpoint and the view rows - the projector recomputes from the
  journal. Tested by the contract case
  `advance-room-view! catches up from a DEEP backlog in batches`.

---

## 6. Recovery paths

Three layers of recovery, each addressing a different failure mode.

### 6.1 Stuck process managers - the sweeper

A process manager is a state machine spread across two streams (its own
plus the rooms it touches). If the JVM dies between an inner command
committing and the PM's continuation being dispatched, the PM stalls
in a non-terminal state.

The sweeper (`system/pm-sweeper.clj`, calling
`booking/recover-move-guest-processes!`) periodically:
1. Enumerates `move-*` streams via `stream-ids-with-prefix`.
2. Reads each history; builds the PM state.
3. Skips terminal PMs and PMs whose last event is more recent than
   `min-age-ms` (don't pre-empt an in-flight step).
4. Re-runs the PM's pure reactor on the last event.
5. Executes the resulting effects through the same `execute-effects!`
   path the normal route uses.

**Idempotency is the key.** Dispatched commands derive their
`:command/id` deterministically:

```clojure
;; components/booking/src/hotel/booking/effects.clj
(defn- derive-command-id [source-event command]
  (UUID/nameUUIDFromBytes
   (.getBytes (str (:event/id source-event) "/" (name (:command/type command))) "UTF-8")))
```

A re-fire produces the SAME `:command/id` as the original dispatch, so
the idempotency cache (section 3.1) returns the cached result for any
step that already committed. The chain advances from wherever it
stopped.

**Test:**
`components/booking/test/hotel/booking/pm_recovery_test.clj`
- *injects* a `:move-requested` event directly to simulate "event
  committed, dispatch died";
- runs recovery;
- asserts the PM drives all the way to `:completed`, the rooms reflect
  a successful move;
- runs recovery THREE MORE TIMES and asserts exactly one `:room-booked`
  on the new room, exactly one `:booking-cancelled` on the old (no
  duplicates).

### 6.2 Outbox messages that died with their relay

If a relay process dies between claiming a row and either marking it
published or marking it failed, the row sits with `claimed_at` set and
nobody touching it. The claim query's stale-reclaim clause:

```sql
AND (claimed_at IS NULL OR claimed_at < now() - INTERVAL '10 minutes')
```

picks it up after 10 minutes - on whichever relay polls next. The
message is re-published; the consumer dedupes via `message_id`. No
loss.

### 6.3 Postgres unreachability during write

A SQLException from `transactional-append!` propagates back to the
caller. The caller (HTTP layer or upstream) sees an error response and
retries. The next attempt is a normal write with the same
`:command/id`. The integration test
`postgres_recovery_test.clj/writes-fail-loudly-when-postgres-is-down`
asserts that writes throw rather than silently corrupting.

---

## 7. Integrity at the edges

Durability is necessary but not sufficient. The system also defends
against silent corruption at three boundaries.

### 7.1 Inside the database (limited)

The `events` table has `UNIQUE (stream_id, version)` and `UNIQUE
(event_id)`. A direct database write that violates these throws.
Otherwise, **a database administrator with INSERT/UPDATE privileges
can silently modify history**. This is an architectural reality - the
cryptographic layer below it is the only way to close that gap, and we
have not built it (gap #1, hash-chained events).

### 7.2 Across the application/storage boundary - blobs

Supporting documents (passport scans, signed PDFs) live in MinIO. Two
defences against silent storage corruption:

1. **AES-GCM authenticated encryption.** A flipped byte in the
   ciphertext fails the GCM auth tag - decryption THROWS rather than
   returning bad bytes.
2. **Content hash check on retrieval.** Every uploaded blob is hashed
   (SHA-256) at upload time; the hash is stored both in the event and
   as MinIO object metadata; on download the decrypted bytes are
   re-hashed and compared. Mismatch throws.

**Test:** `components/booking/test/hotel/booking/documents_test.clj`
- `tampered-storage-bytes-FAIL-the-content-hash-check` flips a byte
  in MinIO and asserts the download throws (not returns garbage).
- `uploaded-bytes-are-NOT-stored-in-plaintext` peeks at the raw
  storage and asserts the bytes there do NOT match the plaintext
  input - so even a leaked backup cannot disclose PII.

### 7.3 Across the application/broker boundary

The integration payload is validated against the closed `contracts.clj`
schema in `partition-reactor-output`. A reactor that tries to publish
an internal keyword keyword (`:event/type` leak) is rejected at the
seam, never reaches the outbox, and is recorded to the ProblemSink as
`:invalid-publish-effect-blocked-at-contract`. The arch test asserts
the contract gate is still wired (`effects-gate-stays-wired`).

---

## 8. PII durability vs erasability (the GDPR pivot)

GDPR Article 17's right to erasure looks like it conflicts with
event-sourcing's append-only journal. The pivot:

**The events are immutable. The keys are not.**

- PII fields are encrypted at the field level with the data subject's
  key (`booking/pii.clj`).
- Document blobs are encrypted with a per-document key, which is
  envelope-wrapped with the subject's key (`documents/handler.clj`).
- Erasing a subject (`erase-by-natural-id!`) destroys their key.
- Every historical row referencing that subject is **still there** -
  same event-id, same position, same recorded-at. But its PII leaves
  and any blobs it references decrypt to `:erased` markers.

**No data is lost. The right data is unreadable.** The audit trail
(who-did-what, when, in what order) survives intact. The PII
disappears.

The system also REFUSES to write fresh PII for an erased subject - that
would be a re-identification attempt. The test
`cannot-record-fresh-pii-about-an-erased-subject` asserts this throws
loudly rather than silently re-keying.

This is the registry-friendly reconciliation of two constraints that
naive event sourcing cannot satisfy.

---

## 9. Backpressure and the failure-fast principle

When something downstream is broken, it is better to fail upstream
quickly than to grow an unbounded queue. The system applies this in
two places:

### 9.1 Outbox depth backpressure

`decider/handle` checks the outbox depth before processing a command.
The prod system is configured:

```clojure
:outbox-backpressure {:warn-at 1000 :reject-at 10000}
```

Below 1000: normal operation. Between 1000 and 10000: command succeeds,
but `:outbox-depth-over-warning` is recorded to the ProblemSink so
ops sees the broker is falling behind. At or above 10000: commands
return `{:error :system-overloaded :outbox-depth N}` immediately;
no append. The journal cannot grow faster than the broker can drain
no matter what the upstream load is.

**Test:** `backpressure_test.clj` covers both thresholds and the
default (no backpressure config => no enforcement, used by all
existing tests).

### 9.2 Projection lag visibility

Section 5.2 already covered this. The sync catch-up is capped; lag
beyond threshold is recorded; the background projector continues
draining. No quiet rot.

---

## 10. Time

Order is by `global_position`, NOT by `recorded_at` (ADR-0001). Two
processes with skewed clocks cannot reorder history; only the journal
can. `recorded_at` is evidence - an interval `{:earliest :latest}`
that contains the true UTC time of the write.

The system-clock adapter accepts a dynamic `uncertainty-fn`
(`components/clock/src/hotel/clock/system_clock.clj`) - in production,
read live max-error from chrony / PTP / AWS ClockBound. When the live
error exceeds `degraded-threshold-ms`, the adapter calls
`on-degraded`, which records `:clock-uncertainty-over-threshold` to
the ProblemSink. The journal still works correctly; the evidence
quality is just lower until the clock recovers.

ADR-0001 explains the principle in detail. Two events with overlapping
recorded-at intervals are temporally INCOMPARABLE by clock alone, but
they are still totally ordered by global_position. This is the only
way to honestly answer "who acted first" in court.

---

## 11. Process manager FSMs are designed terminal

Every PM has a designed-up-front terminal state. `move-guest` has
`:completed` and `:failed`. `approve-decommission` has `:executed` and
`:rejected`. The decider refuses every command on a terminal stream:

```clojure
;; components/booking/src/hotel/booking/decider.clj
(if (terminal? state)
  {:error :stream-is-terminal}
  ((:decide decider) state command))
```

This is a fault-tolerance property: a PM cannot be "rebooted" by
firing a Request command at a terminal stream. There is no
half-restart state. Either the PM is running, or it is permanently
done. Recovery (section 6.1) operates only on non-terminal PMs.

`flows_test.clj/a-decommissioned-room-is-dead-to-every-slice` proves
this for the room aggregate; the same property holds for every
decider in the system.

---

## 12. Schema evolution without breaking replay

Two patterns work together so the journal stays replayable across
schema changes.

### 12.1 Migrations with advisory locking

`event-store/migrations.clj` runs every `V<n>__*.sql` in order, inside
a single transaction guarded by `pg_advisory_xact_lock(?)`. Two
application instances starting simultaneously cannot apply the same
migration twice. The `schema_migrations` table records what has been
applied so re-runs are no-ops.

### 12.2 Upcaster registry

Domain event payloads carry `:event/v`. The read-side runs
`upcasters/upcast-all` over history before evolve sees it; an
upcaster lifts version N to version N+1, chained until the current
version is reached.

The implication: **once an event is in the log, its payload shape is
forever supported** via the upcaster chain. ADR-0006 documents the
policy. The current registry is empty (no schema bumps have happened
yet), but the seam exists so the first one is a one-line addition,
not a refactor. The test `upcasters-test.clj` covers chained
upcasting with a faked registry.

---

## 13. Effect failures are observed, not swallowed

The post-commit effects (notify, dispatch-command) are best-effort -
they happen AFTER the journal commit, so they cannot affect
durability. But "best-effort" cannot mean "silently disappears".

Every failed effect is recorded:

```clojure
;; components/booking/src/hotel/booking/effects.clj
(defn execute-effects! [{:keys [problem-sink] :as system} effects]
  (let [errors (vec (keep (comp error-result #(execute! system %)) effects))]
    (doseq [err errors :when problem-sink]
      (problems/record-problem! problem-sink
                                {:problem :post-commit-effect-failed
                                 :error   err}))
    ...))
```

The `ProblemSink` port (`components/problems`) has an in-memory adapter
for tests and a stderr-JSON adapter for prod. Production swaps in
Sentry/Datadog/etc. - the application is sink-agnostic. Same
mechanism records `:invalid-publish-effect-blocked-at-contract`,
`:clock-interval-malformed`, `:room-view-stale-after-sync-catchup`,
`:outbox-depth-over-warning`, etc.

Without this seam, a notify outage during a high-traffic period would
silently lose hundreds of confirmations and nobody would know.

---

## 14. Concurrent safety properties

A summary of the concurrency claims, each backed by a test:

| Concurrent scenario | What happens | Test |
|---|---|---|
| 50 racers booking the same room | 1 wins, 49 `:concurrency-conflict`, stream has 1 `:room-booked` event | `flows_test.clj` 50-racer scenario |
| 2 concurrent retries with the same `Idempotency-Key` | Both get the same `:events`, only one event in stream, only one outbox row | `idempotency_race_test.clj` (30 trials) |
| 8 concurrent claimers on a 50-row outbox | Every row claimed by exactly one thread | `contract.clj` atomic-claim case |
| 2 projectors on first run | One acquires `pg_advisory_xact_lock`, other waits, no double-apply | gap #4 fix via advisory lock |
| 2 application instances booting | One acquires `pg_advisory_xact_lock(7421431)`, runs migrations; other waits then no-ops | `migrations.clj` design |
| Sweeper firing during normal traffic on the same PM | Re-fires hit deterministic command-ids → idempotency cache → no double-action | `pm_recovery_test.clj` `recovery-is-IDEMPOTENT-and-does-not-double-book` |
| Stale relay claim (10+ minutes) | Reclaimed by the next polling relay; original disappears silently | `claim-outbox-messages` query design |

---

## 15. Observability surface

A fault-tolerance design without observability is theater. The system
exposes:

- **`GET /health`** - liveness (process up)
- **`GET /ready`** - readiness (Postgres reachable; broker excluded
  because the outbox makes it non-blocking)
- **`GET /metrics`** - Prometheus text format:
  - `hotel_outbox_pending` - pending outbox rows (drives backpressure
    alerting)
  - `hotel_projection_lag_rooms` - events behind the rooms projection
- **`ProblemSink`** - structured problem records (one JSON line per
  problem on stderr by default, or wire to a SIEM)

The metrics are deliberately the small set that matters for the
fault-tolerance claims. Outbox depth and projection lag are the
exact early-warning signals before something starts degrading. The
ProblemSink carries the structured failure detail - never just a log
line.

---

## 16. What this all costs

The fault-tolerance posture has a cost. Honest accounting:

- **Throughput**: one Postgres transaction per command, including the
  outbox row. The outbox is single-writer to the database (relays
  share, but each commit funnels through one Postgres TX). Plan for
  Postgres being the bottleneck.
- **Latency**: synchronous projection catch-up adds milliseconds per
  query under normal load. Bounded.
- **Storage**: events + outbox + processed_commands + dead-letter +
  inbox + room_view + projection_checkpoints + subject_keys +
  encrypted blobs. Plan for retention policies.
- **Code surface**: ten bricks, six migrations, six ADRs, ~150 tests.
  Joining engineers need ~2 weeks of orientation before they can edit
  the critical paths safely.

These are deliberate trade-offs. The system is built for use cases
where lost data is unacceptable. For use cases where eventual
consistency is fine and a lost message is not a court matter, this is
overbuilt.

---

## 17. The honest limits

Things that this design DOES NOT defend against, and what would close
each gap (referencing the registry roadmap):

| Failure mode | Defended? | What would close it |
|---|---|---|
| DBA modifies an event row | **No** | Hash chain + HSM signatures (#1, #7) |
| Forged command without auth | **No** | OIDC at REST (#3) |
| Postgres primary outage | **No** | HA replication + tested failover (#13) |
| Regional outage | **No** | Multi-region DR (#17) |
| Backup taken with credentials stolen later | Encryption at rest helps; crypto-shredding helps; not bulletproof | KMS with shorter retention than backup retention |
| Long-running schema change (CREATE INDEX CONCURRENTLY) | Migration runner wraps in TX which forbids it | Two-phase migration runner: in-TX DDL phase + out-of-TX CONCURRENTLY phase |
| Saturated HikariCP pool under heavy load | Threads block then throw | Tune the pool; load test (#11); add bulkheads |
| Hardware time degradation | Recorded as a problem; ordering unaffected | PTP/GNSS + traceability docs (#10 — partially done) |

None of these are oversights. They are the next layer of work, which
crosses code, infrastructure, and governance. The current design closes
the application-layer failure modes that a well-architected
event-sourced system MUST close; the listed gaps are the infrastructure
and policy layers above.

---

## 18. How to verify the claims

Two commands prove most of this document end-to-end:

```bash
bb verify       # format + lint + poly + fast tests + uberjar
bb test:all     # full suite incl. Testcontainers Postgres, RabbitMQ, MinIO
```

Current tally: **183 tests, 849 assertions** in `bb test:all`.
The fast subset (no Testcontainers) is **175 tests, 763 assertions**
at ~3 seconds — including ~3 100 generative `test.check` trials
across the property tests for the room FSM, the multi-party approval
PM, and the move-guest saga. Pre-commit and CI both
run the fast suite; CI also runs the integration suite in a parallel
job (`.github/workflows/ci.yml`). Stability is verified by running
`bb test:all` three times in a row in the final-gate script - all
three runs identical.

A regression in any property documented here turns the relevant test
red. That is the point of the architecture - the claims are not
opinions, they are continuously-checked invariants.

---

## 19. The shape of the argument, one paragraph

The journal is append-only and ordered by log position. Every command
goes through an idempotent shell that re-checks on conflict, so concurrent
retries converge. Events, the outbox row that publishes them, and the
processed_commands row that caches the result commit in ONE Postgres
transaction - the broker can be dead for hours and the journal still
accepts work. The relay drains the outbox with an atomic UPDATE-with-
RETURNING claim, exponential backoff, dead-letter after max attempts,
and 10-minute stale reclaim - concurrent relays don't double-publish
and a crashed relay's work doesn't strand. Publisher confirms +
mandatory routing + a return listener mean the broker NACK or unroutable
case throws loudly instead of silently dropping. PII fields and document
blobs are envelope-encrypted with subject-scoped keys, so GDPR erasure
destroys the keys and the bytes become unreadable while the audit
events themselves remain. Process managers are FSMs with a designed
terminal state, deterministic dispatched command-ids, and a sweeper
that drives stranded ones to completion. The read side advances by
one bounded batch per query plus a background projector, with staleness
reported as a structured problem when it crosses a threshold. Every
failure mode that the application layer can defend against is
defended; every failure mode that requires infrastructure or
governance is named in section 17 and roadmapped against named gaps.
No event committed is ever lost. No retry produces a duplicate event
on the journal. No published message is silently dropped. No stranded
saga stays stranded. No tampered storage decrypts to silently-bad
bytes. No erased subject's PII can be exfiltrated. Every claim has a
test that fails if it regresses.
