# ADR 0008 — Attribute naming: namespaced and global, not container-scoped

## Context

Hickey's *Effective Programs* argues that the meaning of a data
attribute should live in the attribute itself, not in the container
that holds it. `Person.Name` and `MailingListEntry.Name` are not two
different things — they are both "name", and treating them as different
because they live in different containers is a category error that
costs years of mapping code as the system grows.

This codebase already follows Clojure's idiomatic namespacing for system
metadata (`:event/id`, `:event/type`, `:command/id`, `:command/type`,
`:event/v`). But domain attributes are inconsistent: `:room-id`,
`:guest {:name "X"}`, `:check-in`, `:move-id`, `:document-id`,
`:subject-id`, `:decommission-id` — unqualified names whose meaning is
container-scoped rather than global.

For a hotel demo with ~15 attribute kinds, this is harmless. For the
registry direction (directors, beneficial owners, applicants,
presenters, charges, filings, court orders, …) it would produce an
explosion of near-duplicate names — `:director-name`,
`:applicant-name`, `:beneficial-owner-name` — that need constant
mapping back and forth. Establishing the convention now is much cheaper
than enforcing it later at scale-200.

## Decision

### Rule 1: every domain attribute is namespaced

A "domain attribute" is any key inside a command payload, an event
payload, an integration event payload, or a projection row. Namespace
by what the attribute IS globally, not by what container carries it
in any particular event.

```clojure
;; YES
{:event/type :room-booked
 :room/id           "101"
 :person/name       "Ada Lovelace"
 :person/email      "ada@example.com"
 :booking/check-in  "2026-07-01"
 :booking/check-out "2026-07-03"}

;; NO
{:event/type :room-booked
 :room-id    "101"
 :guest      {:name  "Ada Lovelace"
              :email "ada@example.com"}
 :check-in   "2026-07-01"
 :check-out  "2026-07-03"}
```

### Rule 2: roles are attributes, not containers

When the same kind of thing (a person, an address, a date) plays
different roles in different events, the role is its own attribute —
NOT a wrapper that buries the underlying attributes:

```clojure
;; A hotel booking
{:event/type   :room-booked
 :person/name  "Ada Lovelace"
 :person/role  :guest
 :person/email "ada@example.com"}

;; A company incorporation (registry direction)
{:event/type    :company-incorporated
 :company/number "12345"
 :person/name   "Ada Lovelace"
 :person/role   :director
 :person/email  "ada@example.com"}
```

`:person/name` is ONE named thing across the platform. A projection
that needs "all directors of company 12345" joins on
`:company/number` AND `:person/role`. A projection that needs "all
guests of room 101" joins on `:room/id` AND `:person/role`. The
shapes are composable; you do not invent `:director/name` and
`:guest/name` and then write code that converts between them.

### Rule 3: do not retrofit existing names

This rule applies **to new attributes from this point forward**:

- new event types
- new fields added to existing event types (a v(n+1) bump)
- new command types
- new projection row shapes

Existing unqualified attributes (`:room-id`, `:guest`, `:check-in`,
`:move-id`, `:document-id`, `:subject-id`, `:decommission-id`,
`:supporting-document-ids`, ...) stay as they are. They work; the
journal has events that use them; tests assert on them. Retrofitting
purely for hygiene fails the cost/benefit test (every brick touched,
every test rewritten, an upcaster needed for every old event) when
there are about 15 unqualified names total. Stop the bleeding; do not
sterilise the wound.

If a future change has another reason to bump an event's version —
adding a field, changing a relationship — the version bump may also
rename existing fields to the new convention. The upcaster registry
(`booking/upcasters.clj`) is the migration mechanism.

### Rule 4: system metadata is already correct and stays

These names are correct as-is and are excluded from this policy because
they describe the event ITSELF, not the domain it carries:

| Attribute | Meaning |
|---|---|
| `:event/id` `:event/type` `:event/v` | The event's identity and shape |
| `:command/id` `:command/type` | The command's identity |
| `:correlation-id` `:causation-id` `:actor-id` | Cross-event identity threading (intentionally short — they appear on every event) |
| `:recorded-at` `:global-position` | Time and ordering metadata |

### Rule 5: integration events stay string-typed by their contract

Integration events declared in `booking/contracts.clj` use string-typed
names (`"RoomBooked"`, `"version"`, `"check-in"`) to stay consumer-
neutral. Outside consumers may not speak Clojure or have access to our
namespaces. That decision is separate from this one and is not changed
here.

## Consequences

- New events and commands carry namespaced attributes; reviewers
  reject PRs that introduce unqualified domain attributes.
- The `:guest` sub-container is the last of its kind. Future commands
  referring to a person use `:person/name` + `:person/role`, not a
  sub-map.
- Read-model rows in new projections use the same attribute names as
  the events they fold. A row about a person is
  `{:person/name "Ada" :person/role :director}`, not
  `{:name "Ada" :role :director}`. Shape in the database and shape in
  memory match.
- A naming registry (a single Malli schema namespace per attribute
  family — `:person/*`, `:company/*`, `:address/*`, etc.) becomes the
  next leverage move once the registry domain starts. We do not need
  it yet; flagging it so the path is visible.
- Existing tests, events, and projections continue to work unchanged.
  No journal migration is implied by this ADR.

## Status

Accepted. Reviewers cite this ADR when rejecting new unqualified
domain attributes. The upcaster registry is the migration path if a
future change makes retrofitting an existing attribute cheap and
worthwhile.
