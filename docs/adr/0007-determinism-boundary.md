# ADR 0007 — The determinism boundary

## Context

"Determinism" reviewed in isolation invites two failure modes. Reviewers
chase byte-for-byte reproducibility and flag every UUID / nonce / clock
call as a violation; or reviewers see "tests pass" and call the system
deterministic when the production journal is not. Both miss the point.

Dave Farley's framing - **state in, decision out; entropy at the shell;
replay produces the same outcome from the same log** - is the bar this
system holds itself to. This ADR names exactly what that means in this
codebase, so future readers (human and LLM) do not re-derive the
boundary by grep and reach different conclusions each time.

## Decision

### Three concentric circles

```
        ┌──────────────────────────────────────────────────────┐
        │  Operational entropy (acceptable, by design)         │
        │    - race winner among concurrent commands           │
        │    - background worker latency / scheduling          │
        │    - broker delivery order across topics             │
        │  ┌────────────────────────────────────────────────┐  │
        │  │  Shell entropy (controlled via ports)          │  │
        │  │    - Clock port returns interval               │  │
        │  │    - IdSource port mints identity              │  │
        │  │    - Crypto adapters mint nonces and per-doc   │  │
        │  │      keys (intentionally random; otherwise the │  │
        │  │      ciphertext would be predictable)          │  │
        │  │  ┌──────────────────────────────────────────┐  │  │
        │  │  │  DETERMINISTIC CORE                      │  │  │
        │  │  │    - decide / evolve / react             │  │  │
        │  │  │    - upcasters / projections             │  │  │
        │  │  │    - process-manager FSMs                │  │  │
        │  │  │    - state-in, decision-out only         │  │  │
        │  │  └──────────────────────────────────────────┘  │  │
        │  └────────────────────────────────────────────────┘  │
        └──────────────────────────────────────────────────────┘
```

### What IS deterministic - the guarantees

1. **Domain decisions.** Given the same `(history, command)` pair, `decide`
   returns the same `{:events ..}` or `{:error ..}`. Pure; no I/O.
2. **Replay.** Folding any prefix of the event log through `evolve` produces
   the same state. Ordering is by `global_position` and `(stream_id,
   version)`, never by `recorded_at`. Order is a fact of serialisation,
   not a measurement.
3. **Process-manager re-fires.** Dispatched commands derive their
   `:command/id` deterministically from `(source-event-id, command-type)`
   (see `booking/effects.clj/derive-command-id`). The sweeper re-running
   a stuck PM produces the SAME ids, so the processed-commands cache
   short-circuits work already done.
4. **Idempotent retries.** A second command with the same `:command/id`
   returns the cached result. Concurrent retries that race past the
   cache check are caught at append time and re-check the cache before
   surfacing `:concurrency-conflict`.
5. **Sweeper re-fires are no-ops.** Property: running
   `recover-move-guest-processes!` an arbitrary number of times against a
   stuck PM produces the same final state and the same event log. Proven
   by `pm_recovery_test/recovery-is-IDEMPOTENT-and-does-not-double-book`.

### What is NOT deterministic - the EXCEPTIONS, and why each is correct

1. **Race winner among concurrent commands** is database-ordered
   (`UNIQUE (stream_id, version)` + optimistic concurrency). The
   resulting log is deterministic *after* the race; the winner is
   chosen by Postgres, not by domain logic. **Correct:** the alternative
   is global serialisation through a single writer (LMAX-style), which
   would cap throughput at a single thread. The optimistic-concurrency
   approach trades pre-determined winner for end-to-end consistency,
   which is what the domain needs.
2. **Clock returns wall time.** `:recorded-at` intervals carry
   uncertainty (`{:earliest :latest}`) - evidence, not ordering. Tests
   use the deterministic clock; production uses the system clock.
   ADR-0001 is the long version. **Correct:** time is data, the core
   never reads a clock, and the journal's order is independent of it.
3. **Identity is shell-minted.** The IdSource port (`hotel.ids`) returns
   random UUIDs in production, deterministic ones in tests. The core
   never mints an id. **Correct:** identities must be stamped at the
   single impure step (the shell), not derived by the pure decider.
4. **Encryption uses random keys and nonces.** AES-GCM with a fixed
   nonce is a known catastrophic break. Per-document keys are random so
   one compromised document cannot decrypt another. **Correct:** the
   property we want is "ciphertext is indistinguishable from noise" -
   that requires randomness.
5. **Background workers run on their own clocks.** Outbox relay,
   projector, and PM sweeper drain at their own pace. Eventual state
   from a fixed event log is deterministic; intermediate observation
   (broker queue length, projection lag) is not. **Correct:** the
   alternative is synchronous publishing which puts the broker on the
   critical path - exactly what the transactional outbox is built to
   avoid.
6. **ProblemSink `:ts` uses `System/currentTimeMillis` directly.** This
   is observation wall-clock - when the sink saw the problem - and is
   intentionally NOT routed through the domain clock. If the clock
   itself is degraded (the very thing the
   `:clock-uncertainty-over-threshold` problem reports), the observation
   timestamp must not come from the same source being investigated.
   **Correct:** the only inline `System/currentTimeMillis` outside test
   helpers, and the only correct place for one.

### Where the boundary is enforced

| Mechanism | What it enforces |
|---|---|
| `arch_test.clj` | Pure files (`*/decide.clj`, `*/react.clj`, `*/projection.clj`, `*/process.clj`, `room/fsm.clj`, `room/events.clj`) cannot `:require` a port interface AND cannot make inline calls to clock / random / port-namespace fns |
| `arch_test.clj` port allowlist | Only `effects.clj`, `decider.clj`, `recovery.clj`, `pii.clj`, query handlers, and `documents/handler.clj` may touch ports |
| `clojure -M:poly check` | Cross-brick requires must target `.interface` namespaces only |
| Contract tests for both adapters | The in-memory adapter and Postgres adapter share one `verify-contract` spec; behaviour cannot diverge |
| Deterministic adapters in `system/in-memory-system` | Tests get `clock/deterministic` + `ids/deterministic` so the same scenario reproduces bit-for-bit |
| `processed_commands` table | The cache that makes retries idempotent |
| Property tests using fixed seeds | `test.check` records the failing seed; any property regression is replayable |

### Tooling-side reproducibility

- Testcontainers images are pinned to patch versions
  (`postgres:16.4-alpine`, `rabbitmq:3.13.7-management-alpine`,
  `minio/minio:RELEASE.2024-08-17T01-24-54Z`).
- Dockerfile base image pinned
  (`clojure:temurin-21-tools-deps-1.11.3.1463`).
- Clojure CLI pinned in CI (`CLOJURE_CLI_VERSION: "1.11.3.1463"`).
- Library coordinates pinned by `:mvn/version` or `:git/sha` in
  `deps.edn`.
- GitHub Actions are major-version-pinned (`actions/checkout@v6`).
  Pinning to commit SHAs would harden the supply chain further; that
  is a security choice and is tracked separately from this ADR.

## Consequences

- A reviewer can answer "is this deterministic?" by checking the
  guarantees list, not by grepping for `UUID/randomUUID`. Most calls
  outside the `ids` brick that *look* like determinism violations are
  actually correct (the six exceptions above).
- A future change that introduces inline `System/currentTimeMillis`,
  `UUID/randomUUID`, or inline calls to `*.interface/` from a pure file
  fails the architecture test. Determinism is a CI gate, not a hope.
- The boundary is permissive at the shell. Bug fixes that need a port
  call from `effects.clj` / `decider.clj` / `pii.clj` /
  `documents/handler.clj` / `recovery.clj` are allowed without ADR
  revision. Bug fixes that need a port call from a pure file are not.

## Status

Accepted. This is the single source of truth for what the system
promises about determinism; reviews should cite this ADR rather than
re-derive the boundary.
