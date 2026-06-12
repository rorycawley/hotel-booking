# Hotel Booking — Event Modeling → Deciders → Vertical Slices in Clojure

Patterns implemented from:
- thinkbeforecoding.com — *Functional Event Sourcing Decider* (Chassaing, 2021)
- ismaelcelis.com — *The Decide, Evolve, React pattern* (Celis, 2024)
- transactional outbox + atomic UPDATE-RETURNING claim, command-id
  idempotency cache, PM recovery via deterministic dispatched-command-ids,
  envelope-encrypted PII + document blobs (GDPR Art. 17 crypto-shredding),
  multi-party approval as a first-class process manager — all in code,
  all behaviour-tested, all documented in
  [`docs/fault-tolerance.md`](docs/fault-tolerance.md) and ADRs 0001–0008.

## Step 1: The event model (requirements)

Each numbered column is one **vertical slice**:

```
 UI (task)            Command             Event                 Read model / Reactor
──────────────────────────────────────────────────────────────────────────────────
1 [Book this room]──► BookRoom ─────────► RoomBooked ─┐
2 [Cancel booking]──► CancelBooking ────► BookingCancelled
3 [Retire room]   ──► DecommissionRoom ─► RoomDecommissioned   (TERMINAL)
4                                          (all events)──────► Available Rooms view
5                                          RoomBooked ───────► announce  ─► RabbitMQ
6                                          RoomBooked ───────► confirm   ─► Email
7 [Move guest]    ──► (PROCESS MANAGER) MoveRequested ► BookRoom ► CancelBooking ► done/compensate
```

Slice 7 is a **process** run by a **process manager**: an intent that
spans two streams can never be one atomic command, so it is choreographed
as `command → events → reactor → next command`. The PM
(`slices/move_guest/process.clj`) is itself a decider with ITS OWN stream
(`move-<id>`) and FSM — the same pattern as a room, one level up:

```
:not-started ─RequestMove─► :booking-new-room ─NewRoomBooked─► :cancelling-old-room
                                  │ NewRoomFailed                    │ OldRoomCancelled ─► :completed
                                  ▼                                  │ OldRoomFailed
                               :failed ◄── Compensated ── :compensating
```

Its reactor emits the next domain command as data with
`:on-success`/`:on-failure` continuation commands; the effect interpreter
records every step's outcome back on the PM stream. New room is booked
FIRST (a failed step 1 changes nothing); a failed step 2 is COMPENSATED
by re-cancelling the new room. Eventual consistency by design, full audit
trail on the `move-` stream, no distributed transaction.

## Step 2: The room stream is an explicit finite state machine

`components/booking/src/hotel/booking/room/fsm.clj` — explicit states, not boolean flags:

```
               RoomBooked              BookingCancelled
 :available ──────────────► :booked ──────────────────► :available
     │
     │ RoomDecommissioned
     ▼
 :decommissioned   ◄── TERMINAL: stream archivable, no command ever again
```

## Step 3: Each slice's core is a Decider (the 7 elements)

Commands, Events, State are plain maps, plus (`components/booking/src/hotel/booking/decider.clj`):

```clojure
{:initial-state s
 :decide        (fn [state command] -> {:events [..]} | {:error ..})  ; PURE
 :evolve        (fn [state event]   -> state)                        ; PURE
 :terminal?     (fn [state]         -> bool)}                        ; PURE
```

`decider/decide` folds history through `evolve`, refuses terminal streams,
then calls the slice's `decide`. `decider/handle` is the one generic impure
step: read stream → pure decide → append (optimistic concurrency).

Two notes on the pattern: **several slices share one aggregate** — book,
cancel and decommission each compose their own decider (own command
schema + own `decide`) over the ONE shared room kernel
(`initial-state`/`evolve`/`terminal?`), which is what keeps them
consistent. And **the FSM is a choice, not a decree**: model state as a
named-status machine when the lifecycle is modal (usually true, and it
avoids the boolean-flag trap), but accumulative state guarded by
invariants — a set of reserved seats, a running balance — is equally a
valid decider state when that is the truthful model. Process managers
are the exception that proves it: they are ALWAYS FSMs, because a
protocol's steps are states by definition.

## Step 4: React is pure too

Reactors (`slices/*/react.clj`) take an event and return **effect data**:
`{:effect/type :notify-booking-confirmed ...}` / `{:effect/type :publish ...}`.
`components/booking/src/hotel/booking/effects.clj` partitions those
effects into the transactional bucket (`:publish` → outbox row) and
the post-commit bucket (notify, `:dispatch-command` for process-manager
continuations), then interprets the latter after the events commit. So:
decide PURE → evolve PURE → react PURE → publish effects go to the
outbox in the same TX → post-commit effects run last.

## Step 5: Folders scream the model, the hexagon, AND the modules

The workspace is a **Polylith modular monolith** (Simon Brown via
Polylith; full guide in [`docs/polylith.md`](docs/polylith.md), decision
in [`docs/adr/0004`](docs/adr/0004-polylith-modular-monolith.md)).
Modules are *bricks*; each brick's ONLY public face is its `interface`
namespace, and `clojure -M:poly check` fails the build on any violation -
encapsulation enforced, not hoped for.

```
components/                          ◄── the MODULES (bricks)
  booking/                             the capability - INSIDE the hexagon
    src/hotel/booking/
      interface.clj                    DRIVING PORT: every use case the app offers
      decider.clj effects.clj app.clj  generic runner, effect interpreter, wiring
      recovery.clj pii.clj             PM recovery sweep, PII encrypt/decrypt seam
      upcasters.clj                    schema-version forward migration (ADR-0008-adj.)
      room/fsm.clj room/events.clj     the stream's FSM + event schemas
      contracts.clj                    the published language (integration events)
      slices/<use-case>/               ONE FOLDER PER SLICE (the event model!)
    test/hotel/booking/                pure GWT + flow + ordering + property tests
  event-store/                         driven port: append-only journal +
    src/hotel/event_store/             outbox + processed_commands + inbox +
      interface.clj protocol.clj       projection checkpoints + room_view, all on
      in_memory.clj postgres.clj       ONE Postgres datasource (in-memory mirror
      migrations.clj projector.clj     for tests). Migration runner + projector.
      resources/.../migrations/V*.sql  versioned schema migrations (V1 … V4)
  outbox-relay/                        background worker draining outbox → broker
                                       with publisher confirms, backoff, dead letter
  problems/                            ProblemSink port (in-memory + stderr-JSON)
                                       for structured operational-failure recording
  subject-keys/                        per-data-subject AES-256-GCM keys
                                       (GDPR-shreddable: destroy key = erase data)
  document-store/                      blob storage: MinIO/S3 + in-memory adapter
  ids/                                 IdSource port: random in prod, deterministic
                                       in tests (replay-equality property)
  integration/   rabbitmq + in-memory  cross-process publisher (DOMAIN language)
  notifications/ twilio  + recording   guest notification port
  clock/         system + deterministic + interval algebra (ADR-0001)
  system/                              THE CONFIGURATOR: sees every interface,
                                       injects impls via Component
bases/rest-api/                      ◄── HTTP entry: task-based routes, /health,
                                       /ready, /metrics, POST /documents
projects/hotel-system/               ◄── THE deployable: uberjar via build.clj
development/src/user.clj             ◄── one REPL over every brick
Dockerfile                           ◄── two-stage image of the one unit
docs/adr/                            ADRs 0001–0008 · docs/fault-tolerance.md
                                     · docs/polylith.md
```

Build and run the one deployable unit:

```bash
clojure -M:poly check                          # enforce the boundaries
clojure -X:dev:test                            # fast suite, ~3 s, zero I/O
clojure -X:dev:test :excludes '[]'             # full suite, Testcontainers boots
                                               # Postgres + RabbitMQ + MinIO
cd projects/hotel-system && clojure -T:build uber
docker build -t hotel-system . && docker run -p 3000:3000 \
  -e DATABASE_URL=... -e RABBITMQ_URI=... \
  -e SENDGRID_API_KEY=... -e FROM_EMAIL=... \
  hotel-system
```

The MinIO adapter is wired into `prod-system` and takes
`:minio-endpoint`/`-access-key`/`-secret-key`/`-bucket`, but
`bases/rest-api/.../main.clj` does not pass them yet — add the
corresponding `MINIO_*` env-var reads to `main` before deploying the
document-upload feature to production.

## Why this shape: architecture is a bet on future change

The first architectural question is not "Hexagonal, Onion, or Clean?" -
those answer the SECONDARY question (keeping technology out of the
domain). The primary question is **"where should change be contained?"**
Coupling is the cost of change; the goal is that valuable business
changes are cheap, safe, and LOCAL. So the order of operations here is:

> **Event Modeling and DDD discover the units (capability → slice).
> The hexagon protects each unit from technology. In that order.**

**The honeycomb rule** - because "unit" is scale-free: the hexagon can
be DRAWN at any scale (every slice here individually passes the test:
pure core, thin shell), but it is ENFORCED only at the capability.
Slices sharing a stream must share the FSM and `evolve` - divergent
private ideas of `:booked` would corrupt shared state - so a capability
is a HONEYCOMB: hexagonal cells with shared walls (the FSM, the ports,
the language). Drawing is free; enforcement (interface namespaces,
`poly check`) has a cost, so it sits where languages, streams, and
change rhythms actually diverge: the brick boundary. Promotion test: a
slice wanting its own language, own streams, and event-only contact
with its neighbours was never a slice - it is a capability announcing
itself, i.e. the next brick.

**Machinery is selective - a ladder, not a uniform** (full rationale in
[`docs/adr/0002`](docs/adr/0002-domain-sliced-architecture-selective-machinery.md)):

| Rung | Shape | Example here |
|---|---|---|
| 0 | pure function | `clock` interval algebra, `room/fsm` |
| 1 | projection + query | `available_rooms` - no decider, no schema, no own port |
| 2 | pure reactor | `send_confirmation`, `announce_booking` |
| 3 | decider slice | `book_room` - a real invariant under concurrency |
| 4 | process manager | `move_guest` - cross-stream coordination |

A concern enters at the lowest rung that holds its requirements and
climbs only when forced; behaviour tests survive the climb. Inside one
brick, slices may be implemented as lightly as they earn (even direct
SQL for a read model) - bounded by the shared-stream rule above and by
the write side always going through the event store.

The test of modularity is not tidy folders - a layered codebase looks
organised while one business change edits six folders. The test is
local reasoning, local change, local tests, narrow interfaces: adding a
use case here touches one slice folder plus two one-line registrations.

## How to read this hexagonally (the rules the layout enforces)

**1. Inside vs. outside is the whole game.** Everything inside the `booking`
brick is the application. Port bricks' `interface` namespaces are the pins;
their impl namespaces are replaceable technology. If you learn one thing:
adapters sit entirely outside the application - the dependency arrows
only ever point inward (cross-brick requires may target only `interface` namespaces -
`clojure -M:poly check` fails the build otherwise).

**2. Ports are pins on a chip.** The application is a component, like an
integrated circuit. `hotel.booking.interface` is its input pins - the
complete list of use cases it offers, one namespace, one page. The
port bricks' interfaces (event-store, integration, notifications,
clock, problems, subject-keys, document-store, ids) are its output
pins - the complete list of needs it has. Open those two places and
you know everything this application can do and everything it depends
on.

**3. Interface ownership is asymmetric (the bit that trips people up).**
- *Driving side*: the application DEFINES and IMPLEMENTS the API
  (slice handlers behind `api.clj`); driving adapters merely CALL it.
- *Driven side*: the application still DEFINES the interface - in its own
  domain language - but adapters must IMPLEMENT it. The app dictates the
  vocabulary; technology translates. `hotel.notifications.interface` says
  "confirm this booking for this guest"; the Twilio adapter, outside,
  turns that into a subject line and prose. Swap email for SMS and the
  application doesn't change one line.

**4. The test suite is the first user of the application** (Cockburn,
crediting Ron Jeffries). The flow tests call the same driving port HTTP
does, against test adapters written BEFORE the production ones. Every
driven port has at least two adapters - test and production - held to one
shared contract test, so swapping them cannot change behaviour.

**5. The configurator wires it at startup.** the `system` brick
is the one place allowed to know every interface: it injects concrete adapters
into the ports (constructor-style DI via Component). Want Postgres
instead of memory? Change the configurator. Nothing inside the hexagon
ever knows which adapter it got.

**6. Two driving ports we acknowledge but keep minimal:** configuration
(here: which system-map the configurator builds) and administration
(in an event-sourced system: replay streams, rebuild projections - our
projections fold on demand, so there is nothing to administer yet).

## Domain events vs. integration events (two species, one boundary)

Both are "facts about the past", but they live on opposite sides of the
hexagon and follow opposite rules:

| | Domain events (`room/events.clj`) | Integration events (`integration/contracts.clj`) |
|---|---|---|
| Audience | this context only (deciders, projections, reactors) | other systems, over RabbitMQ |
| Language | internal, rich, keyword-typed (`:room-booked`) | published, consumer-neutral, string-typed (`"RoomBooked"`) |
| Contents | everything we know (guest, PII, process tags) | the minimum consumers need - never PII |
| Schema | open maps, free to evolve with the model | `{:closed true}` + explicit `:version`, additive evolution only |
| Stability | cheap to change (we own every consumer) | a CONTRACT - breaking it breaks strangers |

The rule that follows: **never publish a domain event**. The
`announce-booking` reactor is the translation boundary - domain fact in,
versioned contract out - and its tests enforce both directions: the
payload must validate against the closed contract schema, and internal
structure must FAIL it. Storage-side translation is symmetric: the
Postgres adapter turns domain events into JSON and back, so neither the
broker nor the database ever dictates the domain's shape.

**This gap is closed.** `:publish` effects are partitioned out at the
pure-react step and written to an `outbox` table in the SAME Postgres
transaction as the events. A background relay (`outbox-relay` brick)
drains the outbox to RabbitMQ with publisher confirms + a return
listener + atomic UPDATE-RETURNING claim (safe under concurrent
relays). The translation boundary above is unchanged; only the delivery
mechanism is now durable. Full account in
[`docs/fault-tolerance.md`](docs/fault-tolerance.md).

## Time: recorded as evidence, never trusted for order

For systems where "who did something first" must survive court scrutiny
(a land registry differentiating applications at millisecond level), the
crucial insight is that synchronized clocks alone CANNOT answer it:
every node's clock has an uncertainty bound, and two timestamps whose
bounds overlap are temporally incomparable, however precise the sync.

So this codebase splits the problem the way Spanner/TrueTime does:

1. **Time is a driven port** (`ports/driven/clock.clj`) returning an
   UNCERTAINTY INTERVAL `{:earliest ms :latest ms}` guaranteed to contain
   true UTC - never a bare instant. Degraded sync widens intervals in the
   recorded data instead of silently lying. The port also carries the
   pure interval algebra: `definitely-before?` (a court-safe claim) and
   `overlapping?` (incomparable by clock alone).
2. **The shell stamps; the core never sees a clock.** `decider/handle`
   adds `:recorded-at` to every accepted event. `decide`/`evolve` stay
   pure and deterministic.
3. **Legal order = the event store's `global_position`** - the order in
   which the authority ACCEPTED commands, decided by a single
   serialization point with zero clock dependency. The intervals are
   evidence mapping that order onto UTC, not the ordering mechanism.
   `components/booking/test/hotel/booking/ordering_test.clj` proves the
   whole argument: two bookings whose intervals overlap (undecidable by
   clock) are still totally ordered by the log.

Where NTP/PTP fit: BELOW the port, as adapter + operations concern.
The deterministic test adapter is settable; the production adapter wraps
the host clock with an error bound - statically configured here, but a
real deployment reads the LIVE bound from its sync daemon (chrony / PTP
servo / AWS ClockBound). Pick infrastructure for the bound you must
defend: NTP over WAN ~ milliseconds; PTP with a GNSS grandmaster and
hardware timestamping ~ microseconds. And learn from MiFID II RTS 25,
the regulatory template for defensible timestamps: it mandates not just
accuracy (100 microseconds to 1 ms, depending on the system) but a DOCUMENTED,
auditable traceability chain to UTC with monitored divergence - precision
without traceability evidence is worthless in front of a regulator.

If multi-node, multi-region writes ever make a single log impossible,
the next tools up are TrueTime-style commit-wait (only claim an order
when intervals cannot overlap, paying latency for certainty) or hybrid
logical clocks - but exhaust the single-serialization-point option
first; it is simpler and legally cleaner.

**This is a recorded decision**: see
[`docs/adr/0001-order-by-log-position-not-by-clock.md`](docs/adr/0001-order-by-log-position-not-by-clock.md)
for the full rationale, accepted costs, rejected alternatives, and ten
industry precedents. The shortest version of the evidence: land
registries themselves already work this way (HM Land Registry gives an
application priority from the moment it is RECEIVED, not processed -
order of lodgement at the authority); exchanges match by sequencer
position and keep MiFID-grade timestamps only for the audit trail;
Stripe refuses to guarantee event order and points consumers at
authoritative state; Wise exports a per-resource sequence ID and says to
trust it "regardless of timestamp values"; Kafka, Bitcoin, Certificate
Transparency and git all order by position in a log or chain, never by
clock. Centuries of ledgers and court dockets did the same. We are
computerizing a tradition, not inventing one.

## Step 6: Tests = behaviours, fast suite has zero I/O

```
clojure -X:dev:test                     # ~175 fast tests, ~3 seconds
clojure -X:dev:test :excludes '[]'      # ~183 tests incl. Testcontainers
```

Shape of the suite:

```
components/booking/test/                pure GWT + flow + ordering tests
  *_properties_test.clj                 22 generative property tests over the
                                        room FSM, the approval PM, the
                                        move-guest saga (~3 100 trials per run)
components/event-store/test/contract.clj   ONE behaviour spec
  in_memory_test.clj                       runs the contract in-memory (fast)
  postgres_test.clj                        ^:integration: same contract vs
                                           real Postgres in a Testcontainer
components/document-store/test/contract.clj   blob-store contract; in-memory
  minio_test.clj                              + MinIO Testcontainer adapter
components/outbox-relay/test/integration_test.clj   ^:integration: kill Rabbit,
                                                    write events + outbox, restart,
                                                    drain, assert the message lands
```

Tests assert behaviour (events out, effects out, what a guest sees,
what a regulator can read from the audit log) — never implementation
(no mocks, no call counts, no SQL strings). The architecture-test
suite enforces the structural rules — see
[`docs/architecture-tests.md`](docs/architecture-tests.md).

## Step 7: Component + Malli

- **Component** (`system.clj`): `in-memory-system` / `prod-system` are
  `component/system-map`s. Postgres and RabbitMQ adapters implement
  `Lifecycle` — connections open on `start`, close on `stop`. In-memory
  adapters need no lifecycle (Component's default no-op applies).
- **Malli**: each decider carries a `:command-schema`; `decider/decide`
  rejects malformed commands with a humanized `:explain` *before* any
  domain logic runs. `room/events.clj` is a `:multi` schema over
  `:event/type`; a test proves deciders only ever emit valid events.

## Step 8: Drive everything from the REPL

```
clojure -M:dev
```

```clojure
(start!)                                   ; in-memory system, instant
(run!* api/book-room! {:room-id "102"
                       :guest {:name "Ada" :email "ada@example.com"}
                       :check-in "2026-07-01" :check-out "2026-07-03"})
(api/available-rooms the-system)            ; => ["101" "103"]
@(:sent (:guest-notifications the-system))  ; the confirmation "email"
(system/flush! the-system)                  ; drain the outbox to the broker
@(:published (:publisher the-system))       ; the integration event
(reset)                                     ; reload changed code, restart
```

The pure core needs no system at all — call `decider/decide` with a vector
of events and a command, straight from the editor. `dev/user.clj` has a
full `(comment ...)` walkthrough, including poking the FSM by hand and
running the test suite in-REPL.
