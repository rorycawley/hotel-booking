# Architecture tests

A small set of **fitness tests** (Fowler / Ford) that guard the CLAUDE.md
invariants `clojure -M:poly check` can't see. They live in
`components/booking/test/hotel/booking/arch_test.clj` and run in the fast
suite — every push, no extra infra.

## Why we need them at all

Polylith already enforces the big *structural* rule: bricks may only see
each other through their `interface` namespace. That covers the
dependency-direction half of what ArchUnit-style frameworks do.

But several invariants in `CLAUDE.md` are *semantic*, not structural —
they describe how a file is allowed to behave, not just who it may
import. Polylith doesn't read them. Examples:

- **Invariant #2** — the core (`decide`, `evolve`, `react`, projections)
  must be pure: no I/O, no clock, no port calls. Polylith is happy to
  let `slices/book_room/decide.clj` `:require` `hotel.clock.interface`
  — they're separate bricks, the dependency is "legal". The invariant
  isn't.
- **Invariant #7** — `announce-*` reactors translate domain events to
  the closed `hotel.booking.contracts` schema. Polylith can't tell
  whether a reactor is leaking the internal event shape.

Architecture tests fill that gap. They're the same shape as
`poly check`: read source files, assert a rule, fail the build.

## How they relate to the behaviour-test policy

CLAUDE.md says "Refactoring must not require test changes" — that rule
governs the **behaviour suite** (Ian Cooper TDD: history in, command in,
events or error out, asserted through the slice's public exports).
Fitness tests are a different category and play by different rules:

|                  | Behaviour test                      | Fitness test                    |
| ---------------- | ----------------------------------- | ------------------------------- |
| Asserts          | What the system does                | How it's structured             |
| Locked to        | The slice's public API              | File paths and function names   |
| Refactor-safe?   | Yes — that's the point              | No — also the point             |
| Shape            | Given / When / Then                 | Static scan, assert emptiness   |
| Lives in         | `*_test.clj` next to each slice     | `arch_test.clj`                 |

Renaming `effects.clj` will break rules 2 and 3. That is the intended
behaviour: a rename moves the contract gate's home, and we want a
failing test the moment the structural assumption stops holding. The
"no test churn on refactor" rule is for the behaviour suite, not for
the architectural conscience.

## What we check today

Three rules. All static scans over `.clj` files under
`components/booking/src/hotel/booking` — no AST walker, no Java agent.

### 1. The core stays pure (`core-stays-pure`)

Two assertions per file, applied to:

```
slices/*/decide.clj
slices/*/react.clj
slices/*/projection.clj
slices/*/process.clj
room/fsm.clj
room/events.clj
```

**Import scan.** May not `:require`: `hotel.event-store.interface`,
`hotel.clock.interface`, `hotel.notifications.interface`,
`hotel.integration.interface`, `hotel.problems.interface`,
`hotel.subject-keys.interface`, `hotel.document-store.interface`,
`clojure.java.io`, `java.io`, `java.net`.

**Inline-call scan.** File body may not contain:

- Clock statics: `Instant/now`, `LocalDate/now`, `LocalDateTime/now`,
  `ZonedDateTime/now`, `System/currentTimeMillis`, `System/nanoTime`.
- Randomness: `Math/random`, `(rand …)`, `(rand-int …)`, `(rand-nth …)`.
- FQN port calls: anything matching
  `hotel.(event-store|clock|notifications|integration|problems|subject-keys|document-store).interface/…`.

The body scan exists because FQN calls and `clojure.core` randomness
need no `:require` — the import scan can't see them. The FQN port
pattern in particular mirrors rule 2 inside the file: if you can't
`:require` a port from a reactor, you can't call it by FQN either.

Catches: "I'll just grab the clock here for a moment" inside a reactor
or projection — whether by import, by Java static, or by FQN.

### 2. Only the seams touch ports (`only-seams-touch-ports`)

Any file that `:require`s a `hotel.<port>.interface` must be one of:

```
effects.clj                          -- interprets post-commit effects
decider.clj                          -- the generic decider runner (handle)
recovery.clj                         -- the PM-recovery sweep shell
pii.clj                              -- envelope-encrypt seam for PII fields
slices/*/query.clj                   -- read-side projection runner
slices/documents/handler.clj         -- envelope-encrypt seam for blobs
```

Catches: a new slice growing a "convenience" port import outside one
of the documented seams.

### 3. The contract gate stays wired (`effects-gate-stays-wired`)

`effects.clj` must contain calls to both `contracts/known-topic?` and
`contracts/valid?`. That runtime check is what actually keeps domain
events from leaking — it gates the `:publish` effect's topic and
payload against the closed schemas in `hotel.booking.contracts`. If the
call disappears, every other contract rule is theater.

This is deliberately a crude string scan. A previous version of this
rule tried to forbid `announce-*` reactors from `:require`-ing internal
event namespaces. That test was vacuous on the current code (the
reactor has no `:require` clause at all) and missed the actual leak
shape: a payload that simply forwards `(:guest event)` into the
published map. The static check that *would* catch that is hard; the
runtime gate already does, so we guard the gate instead.

## Running

```bash
clojure -X:dev:test                  # the fast suite, includes arch tests
clojure -X:dev:test :vars '[hotel.booking.arch-test/core-stays-pure]'
```

Three deftests, ~30 assertions on the current tree (one per file that
matches each scan, plus two for the contract gate). A failure prints
the offending file and what was found — the forbidden `:require`, the
forbidden inline call, or the missing gate function.

## How to add a new rule

Resist first. A fitness test is durable cost: every CI run pays for it,
and a false positive that gets muted is worse than no rule at all. The
bar is the same as the machinery ladder in ADR-0002 — only add a rung
when the requirement actually shows up.

Add one when:

- An invariant has **already been violated once** in this codebase, or
- The cost of catching the slip in review is high — typically because
  the violation is invisible at a diff level (an import three layers
  away, a payload field that quietly mirrors an internal name).

Don't add one when:

- The rule covers a stylistic preference (event-name tense, namespace
  ordering). Code review is cheaper for those.
- The rule needs an AST walker to even express. Wait for the second
  occurrence before paying that cost.

Two shapes to copy from:

- **Multi-file scan** (rules 1 & 2): pick a file pattern, pick a
  forbidden set, walk `clj-files`, assert emptiness per file. Reuse
  `required-nses` for `:require`-clause checks or `forbidden-calls` for
  body-pattern checks.
- **Single-file presence** (rule 3): `slurp` a known file, assert it
  still contains the call that enforces a runtime invariant. Use when
  the static check would be hopelessly weak (or vacuous) and the real
  guard is at runtime.

The shared scaffolding (`ns-form`, `required-nses`, `rel`,
`matches-any?`, `forbidden-calls`) is ~30 lines and is the only thing a
new rule typically needs.

## Known gaps

- **Data-level event leak.** Rule 3 guards the runtime gate but doesn't
  prove every `announce-*` reactor's payload conforms statically. The
  conformance check happens when the effect is interpreted; a payload
  with a typo'd key fails there, not at the source-scan level.
- **Within-file allowlist.** Rule 2 allows the whole of `decider.clj`
  to import port interfaces; CLAUDE.md actually says only `handle`
  inside it should call them. A second port-touching function added to
  the file would pass the test.
- **Inline call scan is regex-shaped.** A docstring that contains the
  literal string `Instant/now` would false-positive. Fix the docstring
  if that happens; we accept the risk for the simpler implementation.

## See also

- `docs/polylith.md` — the structural rules `poly check` enforces; the
  fitness tests are deliberately the complement.
- `docs/adr/0002-domain-sliced-architecture-selective-machinery.md` —
  the "machinery is selective" principle this doc applies to its own
  rule set.
