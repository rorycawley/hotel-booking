# Architecture of this codebase — patterns and decisions

A teaching guide to *why this system is shaped the way it is*. It's a hotel-booking
service, but the interesting part is the set of choices underneath it: event sourcing, the
Decider pattern, hexagonal structure, a Polylith monolith, and four recorded decisions
(the ADRs).

**How to read it.** It's an arc, each part builds on the last:

1. The one idea everything rests on.
2. The pure core — four patterns that do the actual thinking.
3. Where code lives — how the pieces are arranged and isolated.
4. The recorded decisions (ADRs) — the genuinely hard calls.
5. Testing as an architectural choice.

Every pattern entry has the same four parts so you always know where to look:
**What it is · Why (the tradeoff) · In the code · The rule it buys you.**

---

# Part 1 — The one idea: architecture is a bet on change

Before any pattern, the framing the whole codebase is built on (ADR-0002):

> The first question is **not** "Hexagonal, Onion, or Clean?" — those answer a *secondary*
> question (keeping technology out of the domain). The first question is: **where should
> change be contained?**

The reasoning, in one line each:

- **Coupling is the cost of change.** If changing A forces changing B, they're coupled.
- **Not all coupling is bad.** Things that change *together because they belong together*
  (cohesion) should stay together. Things that change *independently* should be decoupled.
- **So: decompose by the problem, not by technical layers.** A change to one business
  capability should touch one place — not ripple through `controllers → services →
  repositories → entities`.

Keep this in mind; every later decision is an application of it.

---

# Part 2 — The pure core

The heart of the system is **pure**: no I/O, no clock, no randomness, no database. Given
the same inputs it always returns the same outputs. Four patterns make that possible.

## 2.1 Event sourcing — events are the source of truth

**What it is.** Instead of storing *current state* ("room 101 is booked"), you store the
*sequence of facts* that happened (`:room-booked`, then `:booking-cancelled`, …). Current
state is **derived** by replaying those facts.

**Why (the tradeoff).** You gain a complete audit trail, the ability to rebuild any past
state, and — crucially here — a single authoritative *order* of events (see ADR-0001 in
Part 4). The cost: you compute state by folding instead of reading it directly, and you
think in terms of append-only logs.

**In the code.** The store is append-only with two ordering guarantees:

```clojure
;; event_store/protocol.clj — the port
(defprotocol EventStore
  (read-stream    [this stream-id] "All events for one stream, in order.")
  (append-events! [this stream-id expected-version events]
    "Append with optimistic concurrency: throws if the stream
     no longer has exactly `expected-version` events.")
  (read-all       [this] "All events in global order (feeds read models)."))
```

```sql
-- event_store/resources/event-store/schema.sql
create table if not exists events (
  global_position bigserial primary key,   -- the global order
  stream_id       text    not null,
  version         bigint  not null,
  payload         jsonb   not null,
  unique (stream_id, version));            -- optimistic concurrency
```

**The rule it buys you.** State is always a fold over history; the log is never edited,
only appended. `UNIQUE (stream_id, version)` gives you concurrency safety for free.

## 2.2 The Decider — a pure decision machine

**What it is.** A *Decider* (Jérémie Chassaing, 2021) packages a unit of decision-making
into **seven elements**: the three data types (Command, Event, State) plus four pieces of
behaviour — `initial-state`, `decide`, `evolve`, `terminal?`.

```clojure
;; decider.clj — the generic shape, knows nothing about hotels
{:command-schema malli-schema
 :initial-state  s
 :decide         (fn [state command] -> {:events [..]} | {:error ..})  ; PURE
 :evolve         (fn [state event]   -> state)                         ; PURE
 :terminal?      (fn [state]         -> bool)}                         ; PURE
```

**Why (the tradeoff).** Splitting "what should happen?" (`decide`) from "how does state
move?" (`evolve`) keeps both tiny and testable, and lets one generic runner drive every
use case. The discipline is that `decide`/`evolve` must stay pure — no shortcuts to a
database or a clock.

**In the code.** A concrete decision (book a room):

```clojure
;; slices/book_room/decide.clj
(def decider
  {:command-schema command-schema
   :initial-state  fsm/initial-state
   :evolve         fsm/evolve
   :terminal?      fsm/terminal?
   :decide
   (fn [state command]
     (case (:status state)
       :booked    {:error :room-already-booked}
       :available {:events [{:event/type :room-booked
                             :room-id (:room-id command) ...}]}))})
```

The generic runner folds history, refuses terminal streams, validates the command, then
calls the slice's `decide`:

```clojure
;; decider.clj
(defn decide [{:keys [terminal? command-schema] :as decider} history command]
  (if (and command-schema (not (m/validate command-schema command)))
    {:error :invalid-command :explain (me/humanize (m/explain command-schema command))}
    (let [state (current-state decider history)]   ; current-state = (reduce evolve …)
      (if (terminal? state)
        {:error :stream-is-terminal}
        ((:decide decider) state command)))))
```

`handle` is the **one impure step**, identical for every decider — read, decide, stamp the
time, append with optimistic concurrency.

**The rule it buys you.** All domain logic is `(history, command) → events | error`, a pure
function you can test with vectors and no system at all.

## 2.3 Explicit finite-state machines, not boolean flags

**What it is.** A stream's lifecycle is modelled as **named states** with explicit
transitions and a designed-up-front **terminal** state — not a pile of `booked?`,
`cancelled?`, `active?` booleans.

```clojure
;; room/fsm.clj
;;             RoomBooked            BookingCancelled
;; :available ───────────► :booked ───────────────────► :available
;;     │ RoomDecommissioned
;;     ▼
;; :decommissioned   ◄── TERMINAL: no command ever accepted again
(def initial-state {:status :available})

(defn evolve [state event]
  (case (:event/type event)
    :room-booked         (assoc state :status :booked :guest (:guest event))
    :booking-cancelled   (assoc state :status :available :guest nil)
    :room-decommissioned (assoc state :status :decommissioned)
    state))

(defn terminal? [state] (= :decommissioned (:status state)))
```

**Why (the tradeoff).** Boolean flags multiply into impossible combinations
(`booked? true, available? true`?). A named-status machine makes illegal states
unrepresentable and makes "what happens next" obvious. The tradeoff is that the FSM is a
*choice, not a decree*: when the truthful model is **accumulative** (a set of reserved
seats, a running balance) rather than modal, plain state guarded by invariants is equally
valid. (Process managers, §3.3, are the one exception — they're *always* FSMs.)

**The rule it buys you.** Terminal states are designed in advance; the decider refuses
*every* command on a terminal stream, forever.

## 2.4 Decide → Evolve → React — effects are data

**What it is.** The third pure step (Ismael Celis, 2024): after events happen, **reactors**
look at them and return *descriptions of effects as plain data* — not the effects
themselves. A single interpreter at the edge turns that data into real I/O.

**Why (the tradeoff).** Sending an email is I/O; *deciding to send one* is a pure
function of an event. Separating them keeps reactors testable and keeps every port call in
one place. The cost is an extra indirection (an effect map plus an interpreter).

**In the code.** The reactor (pure):

```clojure
;; slices/send_confirmation/react.clj
(defn react [event]
  (when (= :room-booked (:event/type event))
    [{:effect/type :notify-booking-confirmed
      :guest (:guest event) :room-id (:room-id event)
      :check-in (:check-in event) :check-out (:check-out event)}]))
```

Note what's *absent*: no email address plumbing, no subject line, no body. The interpreter
turns the data into a port call:

```clojure
;; effects.clj
(defn execute! [{:keys [guest-notifications publisher command-handlers] :as system} effect]
  (case (:effect/type effect)
    :notify-booking-confirmed
    (notify/confirm-booking! guest-notifications (select-keys effect [:guest :room-id ...]))
    :publish   ...
    :dispatch-command ...))   ; <- this branch is how processes work (§3.3)
```

**The rule it buys you.** The pipeline is **decide (pure) → evolve (pure) → react (pure) →
effects at the edge**. Only `effects.clj` and `decider/handle` ever touch a port.

---

# Part 3 — Where the code lives

Pure logic is useless if it's tangled with everything else. These patterns decide
*placement and isolation*.

## 3.1 Vertical slices — one folder per use case

**What it is.** The codebase is sliced by **use case** (the columns of the event model),
not by technical layer. Each slice is a folder:

```
slices/
  book_room/         decide.clj  handler.clj      (rung 3 — a real invariant)
  cancel_booking/    decide.clj  handler.clj
  decommission_room/ decide.clj  handler.clj
  available_rooms/   projection.clj  query.clj    (rung 1 — just a view)
  send_confirmation/ react.clj                    (rung 2 — a pure reactor)
  announce_booking/  react.clj
  move_guest/        process.clj handler.clj      (rung 4 — a process manager)
```

**Why (the tradeoff).** Adding a use case should touch *one* slice folder plus two
one-line registrations — not six layers. Slices that share an aggregate (book / cancel /
decommission all act on a room) **share one kernel** (`room/fsm`), which is what keeps
their notion of `:booked` consistent.

**The rule it buys you.** Local reasoning, local change, local tests. If a "simple" change
touches many folders, the boundaries are wrong.

## 3.2 Hexagonal architecture — ports and adapters

**What it is.** The application sits *inside* a hexagon. Everything it needs from the
outside world is expressed as a **port** (an interface in *domain* language). Real
technology lives *outside* as **adapters** that implement the ports.

**Why (the tradeoff).** Technology choices (Postgres, RabbitMQ, SendGrid) become
replaceable details that never leak into the domain. The cost is indirection — you only
pay for a port where there's real external variation, testing need, or substitution.

**In the code.** The port speaks the domain, not the technology:

```clojure
;; notifications/protocol.clj — DRIVEN PORT in DOMAIN language
(defprotocol GuestNotifications
  (confirm-booking! [this {:keys [guest room-id check-in check-out]}]))
```

The adapter, *outside*, owns all the email vocabulary:

```clojure
;; notifications/twilio.clj — copywriting lives HERE, not in the domain
(confirm-booking! [_ {:keys [guest room-id check-in check-out]}]
  (http/post "https://api.sendgrid.com/v3/mail/send"
    {... :subject "Booking confirmed"
         :content [{:value (str "Dear " (:name guest) ", room " room-id " is yours …")}]}))
```

Swap email for SMS tomorrow → new adapter, **the application doesn't change one line**.

**Three things that trip people up:**

- **Dependency arrows point inward.** Adapters depend on ports; the domain depends on
  nothing outside. (Runtime *calls* flow outward; *dependencies* point in.)
- **Interface ownership is asymmetric.** Driving side: the app defines *and implements* its
  API; adapters just call it. Driven side: the app still *defines* the interface (in its
  own words), but adapters must *implement* it.
- **The configurator wires it.** One place (`system`) knows every adapter and injects them.

## 3.3 Process managers — multi-step intents that span streams

**What it is.** Some intents can't be one atomic command. "Move a guest from room 102 to
103" touches *two* streams. That's a **process**: `command → events → reactor → next
command`, coordinated by a process manager that is itself a Decider with its *own* stream
and FSM — the same pattern as a room, one level up.

**Why (the tradeoff).** You get eventual consistency with a full audit trail and **no
distributed transaction**. The cost is designing the failure/compensation path explicitly.

**In the code.** Its reactor emits the next command with continuation commands:

```clojure
;; slices/move_guest/process.clj
;; :not-started ─Request─► :booking-new-room ─Booked─► :cancelling-old-room ─► :completed
;;                              │ Failed                    │ Failed
;;                              ▼                           ▼
;;                           :failed                   :compensating ─► :failed
(defn react [event]
  (case (:event/type event)
    :move-requested        ; step 1: book the NEW room first
    [{:effect/type :dispatch-command
      :command    {:command/type :book-room :room-id (:to-room event) ...}
      :on-success {:command/type :record-new-room-booked :move-id (:move-id event)}
      :on-failure {:command/type :record-new-room-failed :move-id (:move-id event)}}]
    :move-new-room-booked  ; step 2: cancel the OLD room
    [{... :command {:command/type :cancel-booking :room-id (:from-room event)} ...}]
    :move-old-room-failed  ; COMPENSATE: undo step 1
    [{... :command {:command/type :cancel-booking :room-id (:to-room event)} ...}]
    nil))
```

New room booked **first** (a failed step 1 changes nothing); a failed step 2 is
**compensated** by re-cancelling the new room.

**The rule it buys you.** *One use case = one command = one stream append.* Anything that
needs two appends is a process, never a single handler doing two writes.

## 3.4 Polylith + Component — enforced modular monolith

**What it is.** The whole thing is a **modular monolith** (Simon Brown) realised with
**Polylith**: modules are *bricks*; a brick's only public face is its `interface`
namespace; `clojure -M:poly check` *fails the build* on any cross-brick access that
bypasses an interface.

**Why (the tradeoff).** You get module boundaries the compiler actually enforces (not a
diagram everyone ignores) while still shipping **one deployable unit** — no
distributed-systems tax today, but the extraction path stays open (brick → its own
service later). The cost is tool commitment and one more concept ("brick") in the
vocabulary (ADR-0004).

**In the code.** Bricks map onto the hexagon: the `booking` component's interface *is* the
driving port; each driven port (`event-store`, `integration`, `notifications`, `clock`) is
a component whose interface *is* the port and whose impl namespaces *are* the adapters; the
`system` component is the configurator. Wiring is **dependency injection via Component**:

```clojure
;; system/core.clj — the CONFIGURATOR, the only brick that sees every interface
(defn in-memory-system []                       ; tests + REPL
  (component/system-map
   :event-store (event-store/in-memory)
   :publisher   (integration/in-memory)
   :clock       (clock/deterministic) ...))

(defn prod-system [{:keys [jdbc-url rabbit-uri ...]}]   ; production
  (component/system-map
   :event-store (event-store/postgres jdbc-url)
   :publisher   (integration/rabbitmq {:uri rabbit-uri :exchange "booking"})
   :clock       (clock/system-clock {:uncertainty-ms 5}) ...))
```

Adapters that own a connection implement `Lifecycle` (`start`/`stop`); in-memory ones need
none. "Want Postgres instead of memory? Change the configurator. Nothing inside the
hexagon ever knows which adapter it got."

**The rule it buys you.** Cross-brick access = `interface` namespaces only, enforced by
the build.

---

# Part 4 — The recorded decisions (the ADRs)

These are the calls worth writing down because they're non-obvious and expensive to
reverse. Each is a full ADR in `docs/adr/`.

## 4.1 Selective machinery — the ladder (ADR-0002)

**The decision.** Machinery is applied as a **ladder, not a uniform**. A concern enters at
the *lowest rung that holds its requirements* and climbs only when forced.

| Rung | Shape | When | Example here |
|---|---|---|---|
| 0 | pure function | a calculation | `clock/overlapping?`, `room/fsm` |
| 1 | projection + query | a view of events | `available_rooms` — no decider, no schema, no port |
| 2 | pure reactor | event → effect data | `send_confirmation`, `announce_booking` |
| 3 | full decider slice | invariant + concurrency on a stream | `book_room`, `cancel_booking` |
| 4 | process manager | cross-stream coordination | `move_guest` |

**Why it matters.** This is the antidote to two failure modes: *over-machining* (a decider
for a calculation) and *under-machining* (a bare function holding a real invariant). The
`available_rooms` slice is the worked example of restraint — it's just a fold over events,
with no schema or port, because it can't receive invalid input and holds no invariant:

```clojure
;; slices/available_rooms/projection.clj — deliberately the lightest slice
(defn project [view event]
  (case (:event/type event)
    :room-booked         (assoc view (:room-id event) :booked)
    :booking-cancelled   (assoc view (:room-id event) :free)
    :room-decommissioned (assoc view (:room-id event) :decommissioned)
    view))
```

**The reviewer's question** becomes *"is this the right rung?"* — not *"did you add all the
layers?"*.

## 4.2 Order by log position, not by clock (ADR-0001)

**The decision.** "Who acted first?" is decided by **position in the append-only log**
(`global_position`), never by comparing timestamps. Time is recorded as *evidence*, never
used to decide order.

**Why it matters (this is the deep one).** Every clock reading has an uncertainty bound.
Two timestamps whose bounds *overlap* are not "hard to compare" — they're **undecidable in
principle**. More precision (NTP → PTP) narrows the window; it never closes it. In court,
every clock-ordered decision is an attack surface. So the system splits the problem the way
Google's Spanner/TrueTime does:

1. **Time is a driven port** returning an *interval*, never an instant:

   ```clojure
   ;; clock/protocol.clj
   (defprotocol Clock
     (now-interval [this] "{:earliest ms :latest ms} - true UTC is within this bound."))
   ```

2. **The shell stamps; the core never sees a clock.** `decider/handle` adds `:recorded-at`
   to every accepted event; `decide`/`evolve` stay pure.

3. **Legal order = the store's `global_position`** — a fact created by serialization, not a
   measurement subject to error. The intervals just map that order onto UTC as evidence.

The pure interval algebra makes the point undeniable in code:

```clojure
;; clock/interface.clj
(defn definitely-before? [a b] (< (:latest a) (:earliest b)))   ; a court-safe claim
(defn overlapping? [a b] (not (or (definitely-before? a b)
                                  (definitely-before? b a))))    ; incomparable by clock alone
```

The test `ordering_test.clj` proves two events with *overlapping* intervals are undecidable
by time yet *totally ordered* by the log. This is how land registries, exchanges (price-time
priority is really *sequence* priority), Stripe, Kafka, Bitcoin and git all actually work.

**The rule it buys you.** Never order by timestamps. Clock infrastructure becomes an
*evidence-quality* decision (buy PTP for narrower evidence), not a *correctness* decision.

## 4.3 Rejections — ephemeral error or recorded event? (ADR-0003)

**The decision (a criterion, not a blanket rule).** A rejection is recorded as an **event**
*if and only if the rejection itself is a business or legal fact* — i.e. someone could
legitimately demand "prove that X was refused, when, in what order, and why." Otherwise it
stays an ephemeral `{:error}`.

**Why it matters.** For a hotel, a rejected booking is a UX moment, not something anyone
litigates — so it stays a returned error with no log position:

```clojure
;; book_room/decide.clj
:booked {:error :room-already-booked}   ; ephemeral — no position, no timestamp, no audit
```

For a land registry, a *refused application* is legally significant. The deep fix there
isn't "append rejection events to the entity stream" — it's recognising the
*application/request* deserves **its own aggregate** (Option B): a stream and FSM
(`:received → :under-examination → :granted | :refused`). The move-guest process manager is
already that shape — an application-lifecycle FSM with terminal success/failure.

**The rule it buys you.** Don't reflexively turn every rejection into an event. An ephemeral
error isn't a defect; it's the *correct weight* for a rejection nobody must ever prove.

## 4.4 Domain events vs integration events — two species, one boundary

**The decision.** Internal **domain events** and externally **published integration
events** are different species with opposite rules, and you **never publish a domain
event**.

| | Domain event (`room/events.clj`) | Integration event (`contracts.clj`) |
|---|---|---|
| Audience | this context only | other systems, over RabbitMQ |
| Language | rich, keyword-typed (`:room-booked`) | consumer-neutral, string-typed (`"RoomBooked"`) |
| Contents | everything, incl. PII | the minimum consumers need, **no PII** |
| Schema | open, free to evolve | `{:closed true}` + explicit `:version`, additive only |
| Stability | cheap to change | a **contract** — breaking it breaks strangers |

**Why it matters.** If you publish your internal event shape, every consumer is now coupled
to your internals and you can never refactor freely. The `announce_booking` reactor is the
*translation boundary*: domain fact in, versioned contract out, PII stripped:

```clojure
;; slices/announce_booking/react.clj
(defn react [event]
  (when (= :room-booked (:event/type event))
    [{:effect/type :publish :topic "booking.room-booked"
      :payload {:type "RoomBooked" :version 1            ; consumer-neutral, versioned
                :room-id (:room-id event) :check-in (:check-in event)}}]))  ; no :guest!
```

The contract is a closed Malli schema, and a test asserts an internal keyword leaking in
*fails* validation.

**The rule it buys you.** Publish only translated, closed, versioned contracts. Evolve them
additively (v2 is a new schema, never a breaking edit).

---

# Part 5 — Testing is an architectural decision

**What it is.** Tests assert **behaviour through the exports** (decider, projection,
reactor, driving port) — never internals, call counts, atom shapes, or SQL. The test suite
is treated as the **first driving adapter** (Cockburn, after Ron Jeffries): it calls the
exact same driving port that HTTP does.

**Why (the tradeoff).** Behaviour tests survive refactoring — you can change the
implementation freely as long as behaviour holds. The discipline: **no mocks** (the core is
pure and needs none; edges use in-memory adapters), and every driven port has *two*
adapters held to **one shared contract test**.

**In the code.**

```clojure
;; flows_test.clj — drives the DRIVING PORT, exactly as HTTP does, 100% in memory
(deftest a-successful-booking-confirms-announces-and-blocks-the-room
  (let [sys (system/test-system)]
    (is (:events (api/book-room! sys ada-books-102)))
    (is (= ["101" "103"] (api/available-rooms sys))         "room is taken")
    (is (= 1 (count @(:sent (:guest-notifications sys))))   "guest got email")
    (is (= 1 (count @(:published (:publisher sys)))))))
```

```clojure
;; event_store/contract.clj — ONE behaviour spec every adapter must honour
(defn verify-contract [fresh-store] ...)   ; run by BOTH in-memory and Postgres tests
```

The in-memory contract test runs in the fast suite; the Postgres one is tagged
`^:integration` and excluded by default. If both pass, **swapping adapters cannot change
behaviour** — which is the whole promise of the hexagon, proven.

**The rule it buys you.** Refactoring must not require test changes. A test that breaks
when you rename an internal function was testing the wrong thing.

---

# Cross-cutting principles (the quiet rules)

These show up everywhere and are worth stating once:

- **Data > functions > macros.** Commands, events, effects are plain maps. No domain DSLs.
  Effects are *data* you can inspect and test before anything runs.
- **Event and command names ARE the ubiquitous language.** Past-tense events
  (`:room-booked`), imperative commands (`:book-room`). You don't rename them casually —
  they're the shared vocabulary with the business.
- **The honeycomb rule.** The hexagon can be *drawn* at any scale (each slice has a pure
  core and thin shell), but it's *enforced* only at the capability — because slices sharing
  a stream must share the FSM and ports. Enforcement has a cost, so it sits where languages
  and change-rhythms actually diverge: the brick boundary.
- **Scaling is pre-decided.** Capability #2 (payments, identity) becomes a sibling brick;
  capabilities talk only through published integration events or explicit driving ports.
  Growth is a refactor, not a rewrite.

---

# Decision cheat sheet

| Situation | What this codebase does | Why |
|---|---|---|
| Need to store a change | Append an event; derive state by folding | Audit trail + a single authoritative order |
| Need a domain decision | A Decider: `(history, command) → events \| error`, pure | Testable with no system |
| Modelling a lifecycle | Explicit FSM with named states + terminal | Illegal states unrepresentable |
| Need to do I/O after an event | Pure reactor returns effect *data*; interpret at edge | Reactors stay pure & testable |
| Adding a use case | New slice folder + two one-line registrations | Local change, local tests |
| Need something from outside | A driven port in domain language + adapter outside | Technology stays replaceable |
| A multi-step / cross-stream intent | A process manager (its own stream + FSM + compensation) | No distributed transaction |
| Deciding how much structure | Lowest rung of the ladder that holds the requirement | Avoid over- and under-machining |
| "Who was first?" | The log's `global_position`; time is evidence only | Clocks can't order; courts can't argue with a sequence |
| A rejection | `{:error}` unless it's a fact someone must prove → then its own aggregate | Right weight for the concern |
| Telling other systems | Translate to a closed, versioned, PII-free contract | Never couple consumers to internals |
| Writing a test | Assert behaviour through exports; no mocks; in-memory adapters | Survives refactoring; proves adapter-swappability |

---

*Source decisions in full: `docs/adr/0001` (order by log), `0002` (selective machinery),
`0003` (rejections), `0004` (Polylith). Patterns from Chassaing's Decider, Celis's
Decide/Evolve/React, Cockburn's Hexagonal, Brown's modular monolith.*
