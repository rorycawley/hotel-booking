# CLAUDE.md

## Commands (Polylith workspace - see docs/polylith.md)
- Enforce module boundaries (run before every push): `clojure -M:poly check`
- Fast test suite (pure + in-memory, zero I/O, run constantly): `clojure -X:dev:test`
- Full suite incl. Postgres/RabbitMQ/MinIO integration tests: `clojure -X:dev:test :excludes '[]'` (Testcontainers starts Docker dependencies)
- Incremental change-aware tests: `clojure -M:poly test`
- REPL over all bricks: `clojure -M:dev` then `(start!)`, after edits `(reset)` — see `development/src/user.clj`
- Build the one deployable: `cd projects/hotel-system && clojure -T:build uber`; container: `docker build -t hotel-system .`

## Architecture invariants — never break these
**Top goal: determinism.** Given the same starting event log and the same accepted command sequence, domain decisions, replay, projections, and recovery must produce the same outcomes. Entropy is allowed only at the shell: time via the Clock port, identity via the IdSource port, cryptographic randomness inside crypto adapters, and external I/O behind ports. Tests and replay use deterministic adapters; production adapters may use real clocks/randomness, but their outputs must be captured as data in the event log or command/result cache before the core sees them. Concurrency must be serialized by explicit mechanisms (stream versions, global log position, projection locks, idempotency keys), never by hoping thread scheduling is kind.

1. **Bricks and boundaries (Polylith).** Modules are bricks under `components/` and `bases/`; a brick's ONLY public namespace is `hotel.<brick>.interface`. Cross-brick requires must target interfaces — `clojure -M:poly check` enforces this; never work around it. The `booking` brick is the application (inside the hexagon); port bricks' interfaces are the ports, their impl namespaces the adapters; the `system` brick is the only one that sees every interface.
2. **The core is pure.** `decide`, `evolve`, `react`, projections: no I/O, no clock, no randomness, no port calls. Time enters only via the Clock driven port; generated IDs enter only via the IdSource driven port. `decider/handle` stamps accepted events with `:recorded-at {:earliest :latest}` uncertainty intervals and shell-minted identity. NEVER order by timestamps - temporal/legal order is the event store's global position; intervals are evidence. Rationale + precedents: `docs/adr/0001-order-by-log-position-not-by-clock.md` - read it before changing anything ordering- or time-related. Reactors return effect DATA; only explicit shell namespaces (`decider.clj`, `effects.clj`, `recovery.clj`, `pii.clj`, query handlers, document handler) may touch ports.
3. **State-changing slices compose Deciders**: `{:command-schema :initial-state :decide :evolve :terminal?}`. Several slices may share one aggregate's kernel (`initial-state`/`evolve`/`terminal?`) — the room slices do; the kernel keeps them consistent. State is always explicit: a named-status FSM when the lifecycle is modal (usually — never boolean flags), or accumulative data + invariants when that is the truthful model. Process managers are ALWAYS FSMs. Terminal state designed up front.
4. **One use case = one command = one stream append.** Never issue two commands from one handler/endpoint. Multi-step intents are processes: command → events → reactor → next command (see `slices/move_guest/process.clj`).
5. **New driven port** ⇒ a new port brick (interface in DOMAIN language) (no technology vocabulary), test adapter FIRST, production adapter second, both passing one shared contract test.
6. **New use case** ⇒ new folder under `slices/`, exposed via `hotel.booking.interface`, task-based endpoint in `bases/rest-api/.../routes.clj` (one task, one command — no CRUD endpoints).
7. **Never publish a domain event.** Integration events are translated at `announce-*` reactors and must validate against the closed, versioned contract schemas in `hotel.booking.contracts` (no PII, no internal structure; evolve contracts additively - a breaking change is a new version).
8. **Event/command names are the ubiquitous language** — past-tense events (`:room-booked`), imperative commands. Don't rename without being asked.
9. **Machinery is selective - a ladder, not a uniform.** Decompose by domain (capability → slice), never by technical layer. New concerns enter at the LOWEST rung that holds their requirements: pure fn → projection+query → pure reactor → decider slice → process manager (table in `docs/adr/0002`). Don't add a port/protocol/schema without independent external variation, testing need, or a real invariant. Decouple across change boundaries; keep together what changes together.

## Test policy (Ian Cooper TDD)
- Tests assert BEHAVIOUR through the slice's exports (decider, projection, reactor, driving port) — never internals, call counts, atom shapes, or SQL.
- One test per Given/When/Then scenario: Given = events, When = command, Then = events or error.
- No mocks. The core needs none (it's pure); edges use the in-memory adapters.
- Tests must control entropy: use deterministic clock/IDs and explicit command/correlation IDs when crossing a driving boundary.
- Refactoring must not require test changes; adding internal helper fns must not add tests.
- Almost all tests are pure/no-I/O; only `^:integration` tests may touch Postgres, RabbitMQ, or MinIO.

## Style (Elements of Clojure)
- Data > functions > macros: commands, events, effects are plain maps. No domain DSLs.
- Narrow names; pull → transform → push, never interleaved.
- Small namespaces, explicit `:require`, no `:refer :all`. Match existing formatting.

---

# Behavioral guidelines
Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.
**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.
## 1. Think Before Coding
**Don't assume. Don't hide confusion. Surface tradeoffs.**
Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.
## 2. Simplicity First
**Minimum code that solves the problem. Nothing speculative.**
- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.
## 3. Surgical Changes
**Touch only what you must. Clean up only your own mess.**
When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.
When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.
The test: Every changed line should trace directly to the user's request.
## 4. Goal-Driven Execution
**Define success criteria. Loop until verified.**
Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"
For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```
Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.
---
**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
