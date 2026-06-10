# ADR-0004: Polylith as the modular-monolith realisation

**Status**: Accepted
**Date**: 2026-06-10
**Relates to**: ADR-0002 (capabilities as units of change)

## Context
Simon Brown's modular monolith demands three things: modules around
domain concepts, ENFORCED encapsulation (architecture the compiler
agrees with, not a diagram), and one deployable unit. Clojure has no
module-visibility mechanism, so enforcement needs tooling. Options
considered: a hand-rolled architecture fitness test (~40 lines parsing
ns forms - cheap, but bespoke and feature-poor) vs Polylith (the
established Clojure-native workspace architecture: bricks with single
public `interface` namespaces, `poly check` enforcement, incremental
change-aware testing, multiple deployable projects over shared bricks).

## Decision
Adopt Polylith. Bricks map onto our existing concepts: the booking
capability is a component whose interface IS the driving port; each
driven port is a component whose interface IS the port and whose impl
namespaces ARE the adapters; the configurator is the `system` component;
the task-based HTTP adapter + main is the `rest-api` base; the single
deployable is the `hotel-system` project (uberjar via tools.build,
shipped as a two-stage Docker image). The development project gives one
REPL over everything.

## Consequences
**Positive**: boundary violations fail the build (`poly check`); adding
capability #2 is "add a brick" with the interaction rules pre-enforced;
incremental `poly test`; the service-extraction path stays open (brick →
own base/project) without distribution costs today.
**Negative / accepted**: tool commitment (workspace conventions, poly on
CI); port-definition ownership moves into the implementing brick
(documented in docs/polylith.md - the domain vocabulary is preserved,
which is the substance); one more concept (brick) in the team's
vocabulary.
**Rejected**: staying on plain deps.edn with a hand-rolled fitness test -
viable, but re-implements a maintained tool poorly.
