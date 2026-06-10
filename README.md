# Hotel Booking — Event Modeling → Deciders → Vertical Slices in Clojure

Patterns implemented from:
- thinkbeforecoding.com — *Functional Event Sourcing Decider* (Chassaing, 2021)
- ismaelcelis.com — *The Decide, Evolve, React pattern* (Celis, 2024)

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

`src/booking/room/fsm.clj` — explicit states, not boolean flags:

```
               RoomBooked              BookingCancelled
 :available ──────────────► :booked ──────────────────► :available
     │
     │ RoomDecommissioned
     ▼
 :decommissioned   ◄── TERMINAL: stream archivable, no command ever again
```

## Step 3: Each slice's core is a Decider (the 7 elements)

Commands, Events, State are plain maps, plus (`src/booking/decider.clj`):

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
`{:effect/type :send-email ...}` / `{:effect/type :publish ...}`.
`src/booking/effects.clj` is the single interpreter that turns effect data
into PORT calls - or, for the `:dispatch-command` effect, into the NEXT
step of a process (which is itself reacted to, recursively). So: decide PURE → evolve PURE → react PURE → effects at the edge.

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
      interface.clj                    DRIVING PORT: use cases (handle + react)
      decider.clj effects.clj app.clj  generic runner, effect interpreter, wiring
      room/fsm.clj room/events.clj     the stream's FSM + event schemas
      contracts.clj                    the published language (integration events)
      slices/<use-case>/               ONE FOLDER PER SLICE (the event model!)
    test/hotel/booking/                pure GWT + flow + ordering tests
  event-store/                         a DRIVEN PORT as a brick:
    src/hotel/event_store/             interface.clj = the port,
      interface.clj protocol.clj       in_memory.clj + postgres.clj = the adapters
      in_memory.clj postgres.clj       (contract test holds both to one behaviour)
  integration/   rabbitmq + in-memory  same shape
  notifications/ twilio  + recording   same shape, DOMAIN language
  clock/         system + deterministic + interval algebra (ADR-0001)
  system/                              THE CONFIGURATOR: sees every interface,
                                       injects impls via Component
bases/rest-api/                      ◄── the entry point (task-based HTTP + main)
projects/hotel-system/               ◄── THE deployable: uberjar via build.clj
development/src/user.clj             ◄── one REPL over every brick
Dockerfile                           ◄── two-stage image of the one unit
docs/adr/                            ADRs 0001-0004 · docs/polylith.md
```

Build and run the one deployable unit:

```bash
clojure -M:poly check                          # enforce the boundaries
clojure -X:dev:test                            # fast suite, zero I/O
cd projects/hotel-system && clojure -T:build uber
docker build -t hotel-system . && docker run -p 3000:3000 \
  -e DATABASE_URL=... -e RABBITMQ_URI=... \
  -e SENDGRID_API_KEY=... -e FROM_EMAIL=... hotel-system
```

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
port bricks' interfaces (event-store, integration, notifications, clock)
are its output pins - the complete list of
needs it has. Open those two places and you know everything this
application can do and everything it depends on.

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

Honest gap, on purpose: `react-all!` publishes in-process with no
delivery guarantee. A production system would publish integration events
via an outbox or a catch-up subscription on the event store, because
publishing is I/O and can fail independently of the append. The
translation boundary shown here is unchanged by that upgrade.

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
   `test/booking/ordering_test.clj` proves the whole argument: two
   bookings whose intervals overlap (undecidable by clock) are still
   totally ordered by the log.

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
clojure -X:test
```

```
test/booking/
  slices/*_test.clj            PURE: history + command -> events | error
                               (incl. terminal-state rejection)
  slices/*reactor tests*       PURE: event -> effect data (incl. no-PII rule)
  effects_test.clj             effect data reaches the right port (in-memory)
  flows_test.clj               decide -> evolve -> react, end to end in memory
  ports/event_store_contract   ONE behaviour spec for every adapter
  adapters/in_memory_..._test  runs the contract (fast)
  adapters/postgres_..._test   ^:integration: same contract vs real Postgres
```

Only the Postgres contract test does I/O; it is excluded by default:

```
DATABASE_URL=jdbc:postgresql://localhost/booking clojure -X:test :excludes '[]'
```

Tests assert behaviour (events out, effects out, what a guest sees) — never
implementation (no mocks, call counts, atom shapes, or SQL strings).

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
(run!* book/handle {:room-id "102" :guest {:name "Ada" :email "ada@example.com"}
                    :check-in "2026-07-01" :check-out "2026-07-03"})
(available/handle the-system)              ; => ["101" "103"]
@(:sent (:email-sender the-system))        ; the confirmation "email"
@(:published (:publisher the-system))      ; the integration event
(reset)                                    ; reload changed code, restart
```

The pure core needs no system at all — call `decider/decide` with a vector
of events and a command, straight from the editor. `dev/user.clj` has a
full `(comment ...)` walkthrough, including poking the FSM by hand and
running the test suite in-REPL.
