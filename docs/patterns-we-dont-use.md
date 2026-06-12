# Patterns we don't use

Patterns common in adjacent communities — particularly the .NET
modular-monolith world — that this codebase deliberately does not
adopt. Each entry names the pattern, says *why people reach for it*,
and explains what we already have that makes it unnecessary. Useful
to read when someone says "should we use X?" — for the patterns
below, the short answer is "no, and here's the equivalent that's
already in the code."

## The Mediator pattern (MediatR-style dispatcher)

**What it is.** A central dispatcher routes typed commands and queries
to their registered handlers (`mediator.Send(command)`). Cross-cutting
concerns — validation, logging, auth, error handling — attach as
pipeline behaviors that wrap every dispatch.

**Why .NET teams reach for it.** Controllers and handlers are classes
that can't easily be passed around as values. Direct references between
the API layer and the application layer create tight coupling and
circular dependencies. The mediator inverts that: controllers import
only the dispatcher interface; handlers register themselves at startup.
Pipeline behaviors then give a single place to hang middleware.

**Why we don't need it.**

- Functions are first-class values. The REST routes import
  `hotel.booking.interface` and call `api/book-room!` directly — the
  driving port *already plays the role* of "the mediator": one
  namespace through which every use case is dispatched. There is no
  coupling to invert.
- Where keyed dispatch is genuinely required, we already do it.
  Process managers emit `{:effect/type :dispatch-command :command
  {:command/type :book-room …}}` as *data* (you can't store a function
  reference inside an event), so `app.clj` keeps a `command-handlers`
  map keyed on `:command/type`. That's the one spot where
  mediator-shape dispatch earns its weight, and we have it precisely
  there. Adding the same indirection at the REST → use-case boundary
  would solve no coupling problem.
- Pipeline behaviors map cleanly onto tools we already have: Malli
  command-schemas validate at the decider boundary, the decider runner
  handles errors uniformly, Ring middleware wraps HTTP, and the effect
  executor handles reactor errors. Authorisation will fit the same
  shape when it lands (see `docs/backlog.md`).

Adding a mediator on top of this would hide the direct call behind
indirection without solving a coupling problem — exactly the kind of
speculative machinery the "machinery is selective" rule in CLAUDE.md
rules out.

## An in-memory message bus

**What it is.** A named abstraction that lets producers publish events
to topics, consumers subscribe by topic, and the bus fans out — often
asynchronously, often with a queue between producer and consumer.

**Why teams reach for it.** Decoupling producers from consumers, the
ability to add new subscribers without touching the producer, async
processing so a slow consumer doesn't block the producer.

**Why we don't need it intra-process.**

- We already have intra-process pub/sub, just unnamed. When events
  are produced, `effects/partition-reactor-output` runs every reactor
  over every event; `effects/execute-effects!` interprets the
  non-publish results post-commit. Reactors don't know about each
  other, multiple can subscribe to the same event, each returns effect
  data. That *is* pub/sub — expressed as a list of functions plus a
  runner, rather than an explicit "bus" abstraction.
- The two things a named bus would add are not wins here:
  - **Topic-based subscription** is cosmetic. Replacing
    `(when (= :room-booked (:event/type event)) …)` with a registration
    hook saves zero meaningful lines and obscures what the reactor
    actually reacts to.
  - **Async queuing** for the *post-commit* effects (notify,
    dispatch-command) would actively hurt: failures surface on
    `:effect-errors` to the caller AND to the `ProblemSink`. Making
    them async would lose that error surface. Publish effects ARE
    asynchronous — they go through the outbox + relay — because
    durability is the property that justifies async there, not
    decoupling.

**Where an actual bus *does* live, and why.**
`hotel.integration.interface` with its in-memory and RabbitMQ adapters
*is* a message bus — but only for the cross-process boundary. That's
the one place it earns its abstraction: an in-memory adapter for
tests, RabbitMQ in production, swappable by the configurator. The
boundary forces the abstraction; that's what justifies it. Promoting
the same idea inward, between slices in the same process, is
speculative machinery — direct function dispatch through
`hotel.booking.interface` is already decoupled enough.

The substantive concern around publish reliability — what if the
integration bus is unreachable when we try to publish? — has been
solved by the transactional outbox + relay (see
[`docs/fault-tolerance.md`](fault-tolerance.md), §4 *The publish
path*). That was the real problem worth solving; a generic in-memory
bus was not.

## See also

- `docs/architecture-patterns-and-decisions.md` — the patterns we *do*
  use, and why.
- `docs/backlog.md` — substantive concerns worth acting on (including
  the outbox and authorisation referenced above).
