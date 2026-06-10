# ADR-0003: Rejections — ephemeral errors or recorded events?

**Status**: Accepted (as a criterion, applied per stream/capability)
**Date**: 2026-06-10
**Deciders**: whole team
**Relates to**: ADR-0001 (only things in the log have a position and a
timestamp), ADR-0002 (machinery proportional to the concern)

---

## Context

Today `decide` returns `{:events [...]}` or `{:error :room-already-booked}`.
An `{:error}` is returned to the caller and never appended: it has **no
log position, no `:recorded-at` interval, no audit trail**. It simply
never happened, as far as the record is concerned.

For a hotel booking that is correct: a rejected booking is a UX moment,
not a fact anyone will litigate.

For a land registry it is dangerously wrong. A *refused application* is a
legally significant fact: who applied, for what, when the authority
received and refused it, why, and — critically under ADR-0001 — **where
the refusal sits in the order relative to competing applications**.
"Your application was refused at position N; the competing one was
granted at N+3" is exactly the kind of statement a court asks for, and an
ephemeral error cannot make it.

Precedent says the same:
- **Regulated trading venues** must retain records of order events
  *including rejections and cancellations* (MiFIR order-record-keeping);
  a rejected order is sequenced and reportable, not discarded.
- **HM Land Registry** treats applications themselves as first-class
  tracked records — lodged, requisitioned, completed, *cancelled* — with
  their own lifecycle, independent of whether the register changes.
- **Accounting**: nothing is erased; wrong entries are corrected by
  *reversing entries*, preserving the record that the mistake occurred.
- **Event-sourcing practice**: "errors as events" whenever a failure is
  domain-meaningful rather than technical.

The cost side: rejection events appended to an *entity* stream bloat its
fold (a popular parcel could accumulate thousands of refusals), force
`evolve` to ignore or track them, and change the decider contract that
every caller and process manager relies on.

## Decision

**The criterion**: a rejection is recorded as an event **iff the
rejection itself is a business or legal fact** — i.e. someone could
legitimately demand "prove that X was refused, when, in what order, and
why". Otherwise it remains an ephemeral `{:error}` (ADR-0002: lowest
rung that holds the requirements).

**The preferred mechanics when the criterion is met — Option B, model
the APPLICATION as its own aggregate:**

The deep fix is usually not "append rejection events to the entity
stream". It is recognising that the thing being accepted or refused — the
*application/request* — deserves its own stream and FSM:

```
application-<id> stream (one per application):
  :received ──► :under-examination ──► :granted   (terminal)
                        │
                        └────────────► :refused   (terminal, with :reason)
```

- "Rejection" becomes `:application-refused` — an **ordinary lifecycle
  event** on the application's own stream: full Malli schema, log
  position, `:recorded-at` interval, no special machinery at all.
- The **entity stream** (parcel/title; here, the room) records only
  *effected* changes, so its fold stays small and its `evolve` clean.
- Receipt gets a log position for free (`:application-received` is the
  gate event ADR-0001 Decision 6 anticipated).
- Cross-application priority disputes are settled by comparing positions
  of `:application-received` events — pure ADR-0001.
- Correlation: the granted application's event carries the entity-stream
  append it caused (and vice versa), exactly as the move-guest process
  manager correlates today. The shape already exists in this codebase:
  `slices/move_guest/process.clj` IS an application-lifecycle FSM with
  terminal success/failure — Option B generalises it.

**Option A — append rejection events to the entity stream itself — is
acceptable only when** rejections are rare, the entity's history is the
natural home for them, and no separate application lifecycle exists in
the domain language. `evolve` must treat them as no-ops (or count them if
the domain cares, e.g. "three refusals triggers review").

**This codebase (hotel) stays on `{:error}`** — deliberately, as the
worked example of the criterion saying *no*: a refused room booking is
not a fact anyone must later prove.

## Consequences

**Positive**: refusals become court-grade facts with order and time
evidence; entity streams stay lean; receipt-vs-acceptance (ADR-0001) is
resolved structurally; the workshop gains a reusable shape ("model the
application's life, not just the entity's").

**Negative / accepted**: one stream per application (cheap in an event
store, but real); correlation discipline between application and entity
streams; the application FSM must be explicitly event-modeled — it is
domain logic, not plumbing, and the PO owns its states and reasons.

**Risk + mitigation**: teams defaulting every rejection to Option B
"for safety". The criterion gates it, and ADR-0002's review question
applies: is this the right rung? An ephemeral error is not a defect; it
is the correct weight for a rejection nobody will ever need to prove.

## Decision drivers, summarised

| Question | If yes → | If no → |
|---|---|---|
| Could anyone demand proof a request was refused, when, in what order? | record it | `{:error}` |
| Does the domain track the request's lifecycle in its own language? | Option B (application aggregate) | consider Option A |
| Are refusals frequent on hot entities? | Option B (keep entity folds lean) | either |
