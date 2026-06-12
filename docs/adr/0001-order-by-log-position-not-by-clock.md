# ADR-0001: Legal/temporal order is decided by log position, never by clock time

**Status**: Accepted
**Date**: 2026-06-10
**Deciders**: whole team (PO, architect, dev, tester)
**Technical story**: court-defensible "who acted first" across nodes, at fine granularity, durable for decades

---

## Context

Future systems (e.g. a land registry) must answer *"who did something first?"*
in a way that survives adversarial scrutiny in court, potentially decades
after the fact, with differentiation finer than a millisecond.

The intuitive answer - synchronize all clocks very precisely (NTP/PTP) and
compare timestamps - has a flaw that is physical, not technological:

1. **Every clock reading carries an uncertainty bound.** NTP over networks is
   millisecond-class; well-run PTP with GNSS grandmasters and hardware
   timestamping is microsecond-class. Neither is zero, and cloud/commodity
   networks rarely achieve PTP's headline figures.
2. **Two timestamps whose uncertainty bounds overlap are incomparable** -
   not "hard to compare", but undecidable in principle. Precision narrows
   the window of undecidability; it cannot close it.
3. **In court this is an attack surface**: every clock-ordered decision
   invites expert witnesses arguing skew, asymmetric network paths, leap
   seconds, and sync-daemon health on the day in question.

Meanwhile the system is event-sourced: every accepted command appends events
to a single authoritative store with a per-stream `version` and a global
sequence (`global_position`), enforced by a database unique constraint.

## Decision

**Priority is determined by position in the single, authoritative,
append-only log. Time is recorded as evidence, never used to decide order.**

Concretely:

1. **"First" means "accepted first by the authority."** The store's
   `global_position` is the total order; per-stream `version` is the order
   within one competition scope (one parcel, one room). This is a fact
   created by serialization, not a measurement subject to error.
2. **Every accepted event is stamped with a UTC uncertainty interval**
   `{:earliest :latest}` from the injected Clock port (`decider/handle`).
   The interval maps the log order onto UTC *as evidence*; it never decides.
   Degraded clock sync widens recorded intervals - it can degrade the
   quality of evidence, never the correctness of order.
3. **Client-claimed times are recorded separately and labelled as claims.**
   They are never merged with the authoritative stamp.
4. **Traceability over precision** (the MiFID II RTS 25 lesson): document
   the chain from the host clock to UTC, monitor divergence continuously,
   and alert + record breaches. Timestamps without auditable traceability
   are worthless in front of a regulator or judge.
5. **Tamper evidence for the decades horizon** (planned, not yet built):
   hash-chain the log (each event carries the hash of its predecessor) and
   periodically anchor the running digest externally. This converts "trust
   our database" into "verify our arithmetic".
6. **If the legally relevant instant is *receipt* rather than acceptance**
   (an application arrives but queues), make receipt itself an event: a
   minimal `application-received` fact appended at the gate, so receipt
   gets its own log position. This is a legal-definition choice to make
   once, explicitly, with lawyers - not an emergent property of the queue.
7. **Rejected as the foundation: multi-master writes ordered by
   synchronized clocks** (TrueTime-style commit-wait). If geography ever
   forces distribution, shard by jurisdiction/parcel range - each shard
   remains a single log - rather than by clock.

## Consequences

**Positive**
- The simultaneity question dissolves: a monotonically increasing integer
  replaces a metrology debate.
- The invariant is technology-independent: `UNIQUE (stream_id, version)` +
  a global sequence are 35-year-old relational primitives; the rule
  survives any future storage migration.
- Testable today: `components/booking/test/hotel/booking/ordering_test.clj`
  proves two events with overlapping clock intervals are undecidable by
  time yet totally ordered
  by the log.
- Clock infrastructure becomes an evidence-quality decision, not a
  correctness decision: NTP-class is acceptable; buy PTP only to make the
  evidence narrower.

**Negative / accepted costs**
- One sequencer per scope is a write ceiling. For registry volumes this is
  orders of magnitude below a single Postgres sequence's capacity, and
  contention only exists within one parcel's stream anyway.
- Cross-shard order is undefined *by design* if sharding ever happens, so
  shards must follow legally meaningful boundaries (jurisdictions), within
  which "first" remains well-defined.
- "Acceptance vs. receipt" must be defined in the rules of service (see
  Decision 6) - the log answers precisely the question it is asked.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Order by NTP timestamps | Millisecond-class uncertainty ≥ the differentiation requirement; undecidable overlaps; expert-witness attack surface. |
| Order by PTP timestamps | Narrows but cannot eliminate overlap; assumes hardware timestamping + PTP-aware network end-to-end; couples legal correctness to ops perfection. |
| TrueTime-style commit-wait (Spanner) | Brilliant for global OLTP; re-imports bounded-uncertainty reasoning into the legal story, couples to special infrastructure, and solves a scale problem a registry does not have. |
| Hybrid logical clocks | Preserve causality, not court-grade total order between independent actors; the hard cases (two strangers, same parcel, same instant) are exactly where HLCs shrug. |
| Vendor ledger database (e.g. AWS QLDB) | See precedent #9 below: the product was deprecated within seven years. Decades-scale invariants belong in boring primitives, not products. |

## Industry precedent

The pattern below is not a fashion; it is what every system whose ordering
faces money, regulators, or courts converges on.

1. **Land registration itself (the domain!).** HM Land Registry: an
   application's priority runs from the moment the registry *receives* it,
   not from when it is processed - order of lodgement at the authority is
   the rule, exactly "log position". The Land Registration Act 2002 s.72
   priority-search mechanism is a *reservation window* layered on top of
   that order-of-receipt rule (and is itself a nice event-modeled process:
   reserve → lodge within period → priority preserved). Torrens-system
   registries worldwide follow the same principle: registration order
   confers priority.
2. **Financial exchanges.** Price-time priority sounds like "ordering by
   time", but the "time" is assigned by arrival sequence at a single
   matching engine per instrument (CME Globex FIFO, NASDAQ): a sequencer
   issues the order; market-data feeds carry sequence numbers. Timestamps
   exist for the *audit trail* - MiFID II RTS 25 mandates 100µs-1ms
   traceability to UTC for records - while matching truth is the sequence.
   Regulators themselves treat timestamps as evidence, not as the matcher.
3. **Stripe.** Explicitly refuses to guarantee event delivery order;
   consumers are told to fetch authoritative object state instead. Event
   timestamps are whole seconds - sub-second "who was first" is not even
   expressible. Ordering is the authority's database; events are hints.
4. **Wise.** Publishes a monotonically increasing per-resource sequence ID
   and documents: use it "regardless of timestamp values". Log position,
   exported to consumers.
5. **Kafka.** The industry's general-purpose log guarantees order only as
   offsets within a partition - position, scoped to a serialization point.
   Its record timestamps carry no ordering guarantee.
6. **Bitcoin.** A network securing immense value refuses to trust
   timestamps for order: consensus order is chain position; block
   timestamps may drift (future-dated blocks tolerated within bounds,
   median-time-past smoothing) and decide nothing about sequence.
7. **Certificate Transparency (RFC 6962).** Append-only Merkle-tree logs,
   verified by every major browser: the working template for tamper-evident
   logs operating at internet scale - the model for Decision 5.
8. **Git.** Order is the commit DAG (parent hashes); commit timestamps are
   user-settable and famously unreliable. Two decades of daily proof that
   position beats timestamps.
9. **A longevity warning: AWS QLDB.** Amazon's managed hash-chained ledger
   database launched 2019, deprecated 2024-25. The *pattern* (append-only,
   hash-chained, verifiable) is sound; the lesson is to encode it in
   primitives you control (Postgres + arithmetic), because decades outlive
   products.
10. **Pre-digital practice.** Double-entry journals, court dockets, ship
    logs, the registry's own ledger books: sequence in the book has been
    the legal order for centuries. This decision computerizes a tradition
    rather than inventing one.

## Implementation in this codebase

- Order: `events.global_position` (global) and `UNIQUE (stream_id, version)`
  (per scope) -
  `components/event-store/resources/event-store/migrations/V1__initial.sql`,
  both event-store adapters, one shared contract test.
- Evidence: `components/clock/src/hotel/clock/` (interval-shaped port
  with `definitely-before?` / `overlapping?` algebra), stamping in
  `components/booking/src/hotel/booking/decider.clj`, adapters
  `deterministic` (test) and `system-clock` (prod, optional
  `:uncertainty-fn` to read the live bound from
  chrony/PTP/ClockBound).
- Proof: `components/booking/test/hotel/booking/ordering_test.clj`.
- Not yet built, tracked here: hash-chaining + external anchoring
  (Decision 5); receipt-gate event (Decision 6) if the legal definition
  requires it.

## References

- HM Land Registry, processing times & priority of applications (gov.uk);
  Land Registration Act 2002 s.72 explanatory notes (legislation.gov.uk)
- ESMA MiFID II RTS 25 - clock synchronisation & UTC traceability
- Corbett et al., *Spanner: Google's Globally-Distributed Database* (OSDI
  2012) - TrueTime intervals & commit-wait
- Stripe docs, *Receive Stripe events in your webhook endpoint* - "Stripe
  doesn't guarantee the delivery of events in the order that they're
  generated"
- Wise developer docs, *Event Ordering* - sequence IDs over timestamps
- Laurie, Langley, Kasper, RFC 6962 *Certificate Transparency*
- Kleppmann, *Designing Data-Intensive Applications*, ch. 8-9 (unreliable
  clocks; ordering and total order broadcast)
- AWS, QLDB end-of-support announcement (2024)
