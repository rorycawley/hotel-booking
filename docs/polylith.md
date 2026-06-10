# Polylith workspace guide

This workspace is a **modular monolith** (Simon Brown): coarse modules
around domain concepts, ENFORCED encapsulation, one deployable unit.
Polylith is the Clojure-native realisation: modules are *bricks* whose
only public face is their `interface` namespace, and `poly check` makes
the boundaries compiler-grade instead of hoped-for.

## Anatomy

```
workspace.edn            top-namespace "hotel", interface ns name
deps.edn                 the development project (REPL over all bricks) + :test + :poly
components/              BRICKS = the modules
  booking/               the capability: slices, decider, effects, FSM, contracts
                         public face: hotel.booking.interface (the driving port:
                         use cases that handle THEN react) - everything else private
  event-store/           a driven port AS a brick: hotel.event-store.interface is
                         the port; in_memory.clj and postgres.clj are the adapters;
                         protocol.clj holds the defprotocol (impl detail)
  integration/           same shape: publish! + rabbitmq / in-memory impls
  notifications/         same shape, DOMAIN language: confirm-booking!
  clock/                 same shape + the pure interval algebra (ADR-0001)
  system/                the CONFIGURATOR: the one brick seeing every interface;
                         builds in-memory-system / prod-system via Component
bases/
  rest-api/              the entry point: task-based routes, form translation,
                         JSON encoding, main. Bases expose nothing; they call.
projects/
  hotel-system/          THE deployable: deps.edn lists the bricks, build.clj
                         makes the uberjar
development/src/user.clj the REPL (start!/reset/run!*) over every brick
```

## The rules Polylith enforces (and how they map to ours)

1. **Cross-brick access = `interface` namespaces only.** `poly check`
   fails the build otherwise. This is the mechanical form of "adapters
   depend on ports", "callers use the driving port", and "slices don't
   reach into each other".
2. **Bricks are the units of change** (ADR-0002). Today `booking` is one
   capability-brick; capability #2 becomes a sibling brick, and they may
   talk only through interfaces or published integration events. *Inside*
   a brick, internal layout is a local choice — `booking`'s vertical
   slices are this brick's preference, not a workspace rule. A future
   brick can organize by entity, classical layer, or whatever fits its
   domain.
3. **Projects choose impls.** The `system` brick's configurator selects
   in-memory vs postgres/rabbit/twilio at runtime config. (The other
   Polylith idiom - two bricks sharing one interface name, chosen per
   project at build time - is available when a project must EXCLUDE an
   impl's dependencies entirely.)

## Commands

```bash
# enforce the architecture + see the workspace
clojure -M:poly check
clojure -M:poly info

# fast suite (pure + in-memory, no I/O); add :excludes '[]' for integration
clojure -X:dev:test

# incremental, change-aware tests (Polylith reads git to test only what changed)
clojure -M:poly test

# REPL over every brick
clojure -M:dev      # then (start!), (run!* booking/book-room! {...}), (reset)

# build the ONE deployable unit
cd projects/hotel-system && clojure -T:build uber
java -jar target/hotel-system.jar          # needs env vars below

# or containerised (build context = workspace root, because of :local/root)
docker build -t hotel-system .
docker run -p 3000:3000 \
  -e DATABASE_URL=jdbc:postgresql://db/booking \
  -e RABBITMQ_URI=amqp://rabbit \
  -e SENDGRID_API_KEY=... -e FROM_EMAIL=noreply@example.com \
  hotel-system
```

Apply `components/event-store/resources/event-store/schema.sql` to
Postgres before first run.

## Extraction path (why this is a modular monolith, not just a monolith)

Each brick already has: a narrow interface, its own tests, no reach into
neighbours' internals, and communication through the configurator-injected
ports plus published integration events (`hotel.booking.contracts`).
Extracting a capability to its own service later = new base + new project
around that brick, swap in-process calls for the RabbitMQ adapter that
already exists. The monolith keeps the option open without paying the
distributed-systems tax today.

## Honest notes

- **Not yet executed here**: this workspace was restructured in a sandbox
  without Maven/Clojars access, so `poly check`, the test suite, and the
  uberjar build have NOT been run. Structure, namespaces, and
  cross-brick discipline were machine-verified (ns=path, interface-only
  requires, balanced forms). Treat the first `clojure -M:poly check` and
  `clojure -X:dev:test` as the real acceptance gate; expect at most
  small fixups.
- **Port ownership shifted**: under Cockburn, the application defines its
  driven ports; under Polylith, the port (interface) lives in the brick
  that implements it, and the dependency arrow is enforced by the tool.
  The substance survives - the vocabulary of `notifications.interface`
  is still domain language, and `booking` knows only interfaces.
- **Pin the poly tool version** in `deps.edn` (`:poly` alias) and check
  for newer `clj-poly` releases when adopting.
