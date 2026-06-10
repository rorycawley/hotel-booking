# Backlog

Architectural ideas with real value that we haven't acted on yet. Each
entry names the problem, sketches the shape, and lists the trigger
that would justify the work. Strike through or delete as items land.

## Outbox for integration events

**Problem.** `effects.clj` appends domain events first, then runs the
`:publish` effect as a separate side step. If the publish fails (queue
down, network blip, broker restart), the domain events are committed
but the integration event is lost. The current code records the
failure on `:effect-errors` but the inconsistency stays — there's no
replay path, and consumers can never recover the missed event.

**Shape.** Write the integration event payload into an `outbox` table
inside the *same* Postgres transaction as `append-events!`. A separate
worker drains the outbox to RabbitMQ at-least-once and deletes rows on
success. The in-memory event-store gets the same shape with an atomic
`conj` onto an outbox atom for the test adapter. The `:publish` effect
disappears from the active effect set; reactors that today emit
`:publish` instead enqueue into the outbox at append time.

**Trigger.** When at least one real external consumer subscribes to
the integration topics. Today the only subscribers are the recording
test adapter and the in-memory broker, so losing a publish doesn't
matter — there's nothing on the other end to miss it.

**Code anchors.** `components/event-store/src/hotel/event_store/postgres.clj`,
`components/booking/src/hotel/booking/effects.clj`,
`components/integration/`.

## Authorization

**Problem.** REST endpoints accept any caller. There's no auth at all
— anyone reaching `bases/rest-api` can issue any command.

**Shape.** Two layers, deliberately split:

- **Enforcement** lives in `bases/rest-api`: token validation, the
  policy-evaluation mechanism, the check at the request boundary.
  Knows *nothing* about specific permissions.
- **Meaning** lives in each slice: a permission keyword (e.g.
  `:booking/book-room`, `:booking/decommission-room`) declared
  privately next to the handler. The slice checks for its own
  permission at the slice-handler entry, before `decider/handle`.

The handler signature gains a `:claims` (or similar) argument carried
through from the REST layer. No shared `permissions` namespace. No
central "Authorization Service" encoding business rules. The shape
mirrors how the slices already own their commands and events: the
slice owns its permission too.

**Trigger.** First requirement that says "X can do Y, Z cannot." As
soon as it lands, do it — adding this *after* a centralised model has
crept in is much harder than getting the shape right on day one.

**Code anchors.** `bases/rest-api/.../routes.clj`,
`components/booking/src/hotel/booking/slices/*/handler.clj`.

## Idempotency key on integration contracts

**Problem.** Contract payloads in `hotel.booking.contracts` carry
`:type`, `:version`, and domain fields, but no stable message
identifier. A retried publish — from the future outbox worker above,
from a RabbitMQ redelivery, or from a consumer re-reading the queue
— gives downstream code no way to dedup. They'd act on the same
logical event twice.

**Shape.** Add `:message-id` to each contract schema. Derive it
deterministically from the source domain event so it's stable across
retries — the natural source is the event's stream-id plus the event
store's global position (both already exist; the position would need
to surface into the event the reactor sees). Stamp at the same place
`effects.clj` already calls `contracts/valid?`. The reactor stays
pure.

**Trigger.** Before the first real external consumer ships. Once a
contract is "out in the world", adding a required field is a version
bump (`room-booked-v2`), which is significantly more work than getting
it on `v1` now.

**Code anchors.** `components/booking/src/hotel/booking/contracts.clj`,
`components/booking/src/hotel/booking/effects.clj`.

## Maintained read models for queries

**Problem.** `available-rooms/query.clj` calls `(es/read-all event-store)`
and folds every event through the projection on every query. Correct
and pure, but linear in the total event count — as the log grows,
every read gets slower. The same will be true of any future query
slice that needs to fold over the full log.

**Shape.** Introduce a maintained read-model store. A subscriber
listens to event-store appends and updates a denormalized table (e.g.
`available_rooms_view`); the query reads directly from that table.
Eventual consistency between log and read model is the trade. The pure
projection isn't deleted — it becomes the rebuild routine, used to
populate the view from scratch or after a read-model schema change.
This is also the prerequisite for any later read-replica deployment
(separate read DB), if that's ever wanted.

**Trigger.** When `available-rooms` (or any other replay-based query)
crosses an acceptable latency threshold under realistic event volume.
Today the volumes don't warrant it; the current shape is correct, just
not scalable indefinitely.

**Code anchors.** `components/booking/src/hotel/booking/slices/available_rooms/`,
`components/event-store/`.
