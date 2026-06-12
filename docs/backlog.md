# Backlog

Architectural ideas with real value that we haven't acted on yet. Each
entry names the problem, sketches the shape, and lists the trigger
that would justify the work. Done items kept in the "Shipped" section
below with a pointer at where they landed, so future readers don't
re-discover problems already solved.

---

## Open — Authorization

**Problem.** REST endpoints accept any caller. There's no auth at all
— anyone reaching `bases/rest-api` can issue any command. The
`:actor-id` field is already plumbed through every event end-to-end
(see ADR-0007), but the REST layer reads it from the `X-Actor-Id`
header without verification. The seam is ready; the gate isn't.

**Shape.** Two layers, deliberately split:

- **Enforcement** lives in `bases/rest-api`: token validation
  (OAuth2/OIDC against a JWKS endpoint), the policy-evaluation
  mechanism, the check at the request boundary. Knows *nothing* about
  specific permissions. Sets `:actor-id` from the verified `sub`
  claim, not from a client-supplied header.
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

---

## Shipped

These were on the backlog and have since landed. Kept here as a
ledger so future contributors can verify the trigger fired and the
shape that earned the change.

### Outbox for integration events — **shipped**

What landed: `outbox` table written in the same Postgres transaction
as `events` (V1 migration), atomic UPDATE-RETURNING claim with
`claimed_at` + 10-minute stale reclaim (V3), exponential backoff +
dead-letter after `max_attempts` (V2). Background worker in the
`outbox-relay` brick drains it with publisher confirms + a
basic.return listener. Headline integration test
(`outbox-relay/test/.../integration_test.clj`) kills RabbitMQ,
writes a booking, restarts the broker, drains the relay, asserts the
message lands — proves the durability claim.

Full account: [`docs/fault-tolerance.md`](fault-tolerance.md).

### Maintained read models for queries — **shipped**

What landed: `room_view` table plus `projection_checkpoints`; the
`event-store/projector.clj` Component drains events past the
checkpoint in the background, the query layer does one bounded
sync-catch-up batch then reads the table. Projection lag past a
threshold is recorded to `ProblemSink` so staleness is observable,
not silent. The pure projection fn
(`available_rooms/projection.clj`) doubles as the rebuild routine —
drop the view, restart, it recomputes from the journal.

### Idempotency key on integration contracts — **shipped (different shape)**

Did not add `:message-id` to the contract payload directly; instead,
outbox rows carry a `message_id` UUID that the RabbitMQ adapter sets
as the AMQP `message-id` header on publish. Consumers dedupe by that
header (the inbox pattern). The contract payload stays minimal — fewer
required fields downstream, same dedup property at the broker layer.
The inbox pattern itself is in `event-store/protocol.clj`
(`record-inbound!` / `handle-inbound!`) and tested in `contract.clj`.

If a future requirement says a downstream system insists on the
message-id INSIDE the payload (not the header), that's a contract v2
bump — additive only, follows the ADR-0004 contract policy.
