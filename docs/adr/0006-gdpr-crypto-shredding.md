# ADR 0006 — GDPR Article 17 via crypto-shredding

## Context

This system stores its truth as an **append-only event log** (ADR-0001).
That is exactly the wrong shape for the GDPR Article 17 right to
erasure, which says (paraphrased): when a data subject asks, you must
make their personal data unrecoverable - permanently and on demand.

You cannot satisfy that by deleting events: the log is the source of
truth, deletions corrupt the order, and the audit trail (who-did-what)
depends on the events being there. You also cannot just blank fields,
because the COMMITTED event is by design unchangeable.

The only architecturally honest answer: **encrypt the personal data
inside each event with a key SCOPED to the subject, and make erasure
== destroying that key**.

After the key is gone the event row is still there - same id, same
position, same recorded-at - but its personal-data leaves are
ciphertext nobody can decrypt, ever. The audit and the order survive;
the personal data does not.

## Decision

### Subject identity

Each natural person is identified by a stable **subject-id**: the
SHA-256 hash of a natural identifier (here: the lower-cased email).

```
subject-id = base64(sha256(lowercase(trim(email))))
```

The hash is a one-way function so storing the subject-id alongside the
ciphertext is safe (it is not by itself PII).

### Field-level encryption

The `booking/pii.clj` namespace declares which paths inside each event
type contain personal data. At write time, every leaf in that registry
is encrypted with AES-256-GCM using the subject's key. The event row
that goes into Postgres has:

- non-PII fields in plaintext (room-id, check-in, status, ...)
- `:subject-id` stamped at the top level (opaque)
- PII leaves stored as base64 ciphertext blobs

### The SubjectKeys port

A new driven port `hotel.subject-keys.interface`:

```
(get-or-create-key [_ subject-id])   ; idempotent
(erase-subject!     [_ subject-id])
(erased?            [_ subject-id])
```

- The in-memory adapter is shipped for tests.
- Production wires AWS KMS, GCP KMS, HashiCorp Vault, or an HSM.

### Erasure

The booking interface exposes:

```
(api/erase-by-natural-id! system "ada@example.com")
```

which hashes to the subject-id and calls `SubjectKeys.erase-subject!`.
After this:

1. `get-or-create-key` for that subject returns `nil` forever.
2. Decryption of any historical ciphertext with `nil` returns the
   marker keyword `:erased`.
3. The application REFUSES to write fresh PII for an erased subject -
   re-identification would defeat the whole point.

### What the view shows

The read-side projection (`available-rooms`) **does not store PII** -
status only. So a view rebuild after erasure is unnecessary; erasure
only affects callers reading guest detail back through the decider's
decrypt path.

If a future query DOES need guest detail, the implementer has two
choices:

1. Store ciphertext in the view; decrypt on read (so the view honours
   erasure automatically).
2. Drop and rebuild the view on each erasure (heavyweight; only
   acceptable if erasures are rare).

The codebase currently does neither - because the projection doesn't
contain PII at all.

## Consequences

- The integrity of the event log is unchanged. No event is ever
  modified or deleted; only keys are destroyed.
- The application can answer "what was Ada's booking on 2026-07-01"
  AS OF a point in time only if that point is before her erasure
  request - AFTER, the row reads `:erased`. This is the desired
  property.
- Backups taken BEFORE erasure still contain the ciphertext. They also
  contain the now-destroyed key if the key was in the same Postgres
  database. Production deployments MUST hold subject keys in a
  separate KMS / HSM whose backups have a SHORTER retention than the
  event-log backups, so a restore-attack cannot resurrect erased data.
- Crypto agility: rotating the AES-GCM algorithm requires either (a)
  re-encrypting every existing event for every subject - expensive
  but doable as a background task - or (b) tagging each ciphertext
  with the algorithm used (a simple version byte). We do (b) implicitly
  by keeping the algorithm choice constant for the lifetime of the
  install; if it ever changes, add the version byte then.

## Status

Accepted. Implemented in `components/subject-keys` and wired through
`components/booking/src/hotel/booking/pii.clj`. Tests in
`gdpr_erasure_test.clj` cover the round trip, erasure, refusal-of-
re-identification, and idempotent-replay-respects-erasure.
