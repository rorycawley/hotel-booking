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
  booking/               the capability: slices, decider, effects, FSM, contracts,
                         recovery (PM sweep), pii (envelope encryption seam),
                         upcasters (schema-version forward migration).
                         public face: hotel.booking.interface — everything else private
  event-store/           the journal brick: hotel.event-store.interface bundles
                         five driven ports on ONE shared Postgres datasource
                         (EventStore, ProcessedCommands, Outbox, Inbox, RoomView).
                         in_memory.clj + postgres.clj are adapters; migrations.clj
                         runs V<n>__*.sql; projector.clj is the projector Component
  outbox-relay/          worker brick: drains the outbox to RabbitMQ with publisher
                         confirms, exponential backoff, dead-lettering after
                         max_attempts. Atomic UPDATE-RETURNING claim makes
                         concurrent relays safe
  problems/              ProblemSink port (in-memory adapter for tests,
                         stderr-JSON adapter for prod) — every operational failure
                         becomes a structured record instead of vanishing
  subject-keys/          GDPR Art. 17: per-data-subject AES-256-GCM keys.
                         Destroy a key → every event referencing that subject
                         decrypts to :erased. Auth tag surfaces tampering
  document-store/        opaque blob storage port: in-memory + MinIO/S3 adapters.
                         Documents are envelope-encrypted per subject; same
                         shredding property as field-level PII
  ids/                   IdSource port: random in prod, deterministic in tests
                         (ADR-0007); makes replay equality a property, not a hope
  integration/           cross-process publisher: publish! + rabbitmq / in-memory
  notifications/         guest notifications port (twilio + recording)
  clock/                 system + deterministic + interval algebra (ADR-0001)
  system/                the CONFIGURATOR: the one brick seeing every interface;
                         builds in-memory-system / prod-system via Component
bases/
  rest-api/              the entry point: task-based routes, identity headers,
                         /health /ready /metrics, POST/GET /documents, main.
                         Bases expose nothing; they call.
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

# fast suite (pure + in-memory, no I/O); ~3 s, ~175 tests
clojure -X:dev:test

# full suite — Testcontainers boots Postgres + RabbitMQ + MinIO
clojure -X:dev:test :excludes '[]'

# incremental, change-aware tests (Polylith reads git to test only what changed)
clojure -M:poly test

# REPL over every brick
clojure -M:dev      # then (start!), (run!* api/book-room! {...}), (reset)

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

# (the MinIO adapter exists in prod-system but main.clj does not yet
#  pass MINIO_* env vars - add the reads to main before deploying the
#  document-upload feature)
```

Schema migrations under
`components/event-store/resources/event-store/migrations/V<n>__*.sql`
are applied automatically on Postgres adapter start, guarded by
`pg_advisory_xact_lock` so two booting instances do not race. No
manual `psql` step needed before first run.

## Extraction path (why this is a modular monolith, not just a monolith)

Each brick already has: a narrow interface, its own tests, no reach into
neighbours' internals, and communication through the configurator-injected
ports plus published integration events (`hotel.booking.contracts`).
Extracting a capability to its own service later = new base + new project
around that brick, swap in-process calls for the RabbitMQ adapter that
already exists. The monolith keeps the option open without paying the
distributed-systems tax today.

## Honest notes

- **Port ownership shifted**: under Cockburn, the application defines its
  driven ports; under Polylith, the port (interface) lives in the brick
  that implements it, and the dependency arrow is enforced by the tool.
  The substance survives — the vocabulary of `notifications.interface`
  is still domain language, and `booking` knows only interfaces.
- **One brick can expose multiple driven ports.** The `event-store`
  brick bundles five protocols (`EventStore`, `ProcessedCommands`,
  `Outbox`, `Inbox`, `RoomView`) on one datasource. The standard
  Polylith advice is "one port per brick", but the transactional
  guarantee that ties events + outbox + processed_commands together is
  only possible if they share a Postgres transaction — splitting them
  across bricks would force a cross-brick `with-transaction` port,
  which is worse than the bundle. ADR-0007 records the trade.
- **Pin the poly tool version** in `deps.edn` (`:poly` alias) and check
  for newer `clj-poly` releases when adopting.
