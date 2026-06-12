(ns hotel.event-store.protocol
  "DRIVEN PORTS owned by this brick. All of them sit on the SAME journal
   substrate (one Postgres datasource in prod, one set of atoms in tests),
   because the enterprise patterns they encode are only correct when they
   share a transaction:

     EventStore         - the append-only journal
     ProcessedCommands  - command idempotency (caches per-command result)
     Outbox             - transactional outbox (durable publish queue)
     Inbox              - consumer-side dedupe for inbound messages
     RoomView           - persistent CQRS read model + checkpoint

   The CRITICAL operation is `transactional-append!` on EventStore: it
   writes events + outbox-messages + processed-command row atomically. If
   the broker is dead, every other write still commits and the outbox
   relay catches up later; if the relay's publish fails, the broker still
   has nothing - so we never end up with one without the other.

   Each protocol is implemented by ONE record per adapter (postgres,
   in-memory). Keeping them separate by responsibility lets callers depend
   only on what they need.")

;; --- the journal --------------------------------------------------------

(defprotocol EventStore
  (read-stream    [this stream-id]
    "All events for one stream, in order.")
  (read-all       [this]
    "All events in global order (feeds read models / automations).")
  (read-after     [this position limit]
    "Events with global_position > `position`, up to `limit`. The cursor
     a projector uses to advance.")
  (stream-ids-with-prefix [this prefix]
    "Distinct stream-ids whose id starts with `prefix`. Used by process-
     manager recovery to enumerate non-terminal processes for sweeping.")
  (transactional-append! [this op]
    "ATOMIC. `op` is a map:
       {:stream-id        \"...\"   ;; nil means \"no events, just record command\"
        :expected-version N
        :events           [{:event/id u :correlation-id u :causation-id u? ..}]
        :outbox           [{:message-id u :correlation-id u :causation-id u? :topic .. :payload ..}]
        :command-record   {:command-id u :command-type kw :result map}}  ;; or nil
     One TX. Throws on concurrency conflict. Returns the op unchanged on
     success (events/outbox carry their assigned ids)."))

;; --- enterprise patterns on the same substrate --------------------------

(defprotocol ProcessedCommands
  (command-result [this command-id]
    "Cached result for an already-processed command-id, or nil. The
     idempotency check the shell makes BEFORE doing any work."))

(defprotocol Outbox
  (claim-outbox-messages [this limit]
    "Up to `limit` outbox rows that are pending AND due. The claim is
     ATOMIC: each returned row is stamped with claimed_at in the same
     statement that selects it, so two concurrent calls receive
     DISJOINT sets. Stale claims (10 min) are reclaimed, so a relay
     that crashed mid-publish doesn't strand its rows. Caller MUST mark
     each row published or failed.")
  (mark-outbox-published! [this id]
    "Stamps `published_at = now()`. Idempotent.")
  (mark-outbox-failed! [this id error backoff-ms]
    "Bumps attempts, records the last error, defers next_attempt_at by
     backoff-ms. If attempts reaches max_attempts the row is moved to
     `dead_letter_outbox` and removed from `outbox`.")
  (dead-letter-messages [this limit]
    "Recent dead-lettered rows (operator inspection).")
  (outbox-depth [this]
    "Count of pending outbox rows. The decider uses this for BACKPRESSURE:
     once the relay falls behind by a configured threshold, new commands
     are rejected so the outbox cannot grow unbounded."))

(defprotocol Inbox
  (record-inbound! [this message]
    "Tries to record an inbound message. Returns :recorded when the
     message should be processed now: either first delivery, or a
     redelivery after a previous attempt failed before mark-processed.
     Returns :already-seen once the message-id has been processed.
     `message` = {:message-id u :topic .. :payload ..}")
  (mark-inbound-processed! [this message-id]
    "Marks the inbound row as processed."))

(defprotocol RoomView
  (read-room-view [this]
    "Whole view as a map room-id -> {:status :guest-name :guest-email :check-in :check-out}.")
  (advance-room-view! [this projection-name evolve-fn batch-size]
    "Reads up to `batch-size` events past the projector's checkpoint,
     folds them through `evolve-fn`, writes view rows + new checkpoint in
     ONE TX. Returns the number of events applied (0 means caught up).")
  (projection-lag [this projection-name]
    "Count of events past the named projection's checkpoint. 0 means the
     view is current; high lag means the projector is behind and queries
     will see stale data until it catches up."))
