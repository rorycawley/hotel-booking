# ADR-0002: Domain-sliced architecture with selective machinery

**Status**: Accepted
**Date**: 2026-06-10
**Deciders**: whole team
**Sources**: Drotbohm, *Why Hexagonal/Onion/Clean are answers to the wrong
question*; Beck, *Tidy First?* (coupling/cohesion); Yourdon & Constantine,
*Structured Design*; Hickey, *Simple Made Easy*; Ackoff (systems are the
product of interactions); Cockburn, *Hexagonal Architecture*.

---

## Context

Architecture is a bet on future change. The first architectural question is
not "Hexagonal, Onion, or Clean?" - those answer the SECONDARY question of
separating technical concerns from domain code. The primary question is:

> **Where should change be contained?**

Coupling is the cost of change: if changing A forces changing B, they are
coupled. Not all coupling is bad - accidental coupling (conceptually
separate things forced to change together) raises cost; cohesive coupling
(things changing together because they belong together) lowers it. Yourdon
& Constantine's old result still holds: modification cost is minimised when
software pieces correspond to pieces of the PROBLEM and their relationships
mirror the problem's relationships. And Ackoff warns that modules are not a
system - the system is their interactions.

## Decision

1. **Decompose by domain first; protect with the hexagon second - in that
   order.** Event Modeling and DDD discover the units (capabilities →
   slices); ports & adapters protect each unit from technology. Hexagonal
   architecture never decides what the units are.

2. **The decomposition hierarchy is:**
   `system → capability (bounded context) → vertical slice (use case) →
   pure decisions/events/state → ports only where useful → adapters at the
   edge`. Never `controllers → services → repositories → entities` - those
   are technical categories, and a single business change ripples through
   all of them.

3. **The rule of coupling:** decouple across change boundaries; keep
   together what changes together. Indirection (a port, a protocol, a
   mapper) is paid for ONLY by independent external variation, testing
   need, failure isolation, or substitution. Clojure's low ceremony is an
   asset; do not rebuild heavyweight OO architecture out of protocols.

4. **Machinery is selective, but as a LADDER, not a continuum.** Few,
   recognisable shapes - a team navigates recognisable rungs faster than
   artisanal slices. The rungs in this codebase, lightest first:

   | Rung | Shape | When | Example here |
   |---|---|---|---|
   | 0 | pure function | a calculation | `clock/overlapping?`, `room/fsm` |
   | 1 | projection + query fn | a view of events | `available_rooms` - no decider, no schema, no own port |
   | 2 | pure reactor | event → effect data | `send_confirmation`, `announce_booking` |
   | 3 | full decider slice | invariants + concurrency on a stream | `book_room`, `cancel_booking`, `decommission_room` |
   | 4 | process manager | cross-stream coordination, compensation | `move_guest` |

   A new concern enters at the LOWEST rung that holds its requirements and
   climbs only when requirements force it. Climbing a rung is a normal,
   small refactor (the tests, asserting behaviour, survive it).

5. **One capability = one hexagon.** Today this codebase is a single
   capability (booking), so there is one `booking/` (inside), one
   `adapters/` (outside), one configurator - Cockburn geometry as adopted
   in the README. **The scaling rule, recorded now so it is not improvised
   later:** when a second capability arrives (payments, documents,
   identity), the top-level partition becomes capabilities -
   `src/<capability>/` each with its own slices and its own ports, its own
   adapters clustered per capability, hexagon applied PER CAPABILITY - and
   capabilities interact ONLY through published integration events
   (`integration/contracts.clj` is already that seam) or explicit driving
   ports. The invariant that survives both scales: dependency arrows point
   toward the domain. Adapter LOCATION follows the unit of change.

6. **The system is the interactions** (Ackoff). Decomposition is not done
   when modules exist; for every boundary, answer in the event-modeling
   workshop: what events does it publish? what commands does it accept?
   what read models does it own? what does it hide? what consistency does
   the business require between it and its neighbours? The answers are the
   architecture; the folders merely store it.

7. **Encapsulation over visual organisation.** The test of modularity is
   not tidy folders, it is: can a routine business change be made with
   local reasoning, local edits, local tests, behind a narrow interface?
   Concretely: adding a use case here touches one slice folder plus two
   one-line registrations (driving-port facade, routes). If a "simple"
   change starts touching many folders, the boundaries are wrong - move
   the boundary, do not add process.

## Consequences

**Positive**: changes are local; light concerns stay light (no Malli
schema for a projection that cannot receive invalid input); the path from
one capability to many is pre-decided, so growth is a refactor, not a
rewrite; review question becomes "is this the right rung?" instead of
"did you add all the layers?".

**Negative / accepted**: rung judgment is judgment - reviewers must push
back on both over-machining (a decider for a calculation) and
under-machining (a bare function holding a real invariant). The ladder
table above is the tiebreaker. Two-tier adapter geometry (global now,
per-capability later) means one planned folder migration on the day
capability #2 lands; we accept that over speculative structure today.

**Explicitly rejected**: technical-layer top-level folders; uniform
maximal machinery ("every slice gets a decider+schema+port"); uniform
minimal machinery ("it's all just functions") - both ignore that
different concerns carry different change risk.

## Relationship to other ADRs

ADR-0001 fixes WHAT decides order (the log). This ADR fixes WHERE change
is contained (domain slices, selectively machined). Together: cohesive
inside, loosely coupled outside, pure where possible, effectful only at
the edges, structured around anticipated change.
