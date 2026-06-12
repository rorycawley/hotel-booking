(ns hotel.subject-keys.protocol
  "DRIVEN PORT: per-data-subject encryption keys. The whole point of the
   port is to enable GDPR Article 17 (right to erasure) on top of an
   IMMUTABLE event log: PII fields are encrypted with a subject-scoped
   key; destroying that key makes every event referencing the subject
   permanently unreadable, even though the events themselves are still
   there.

   Adapters: in-memory (test); a real install would back this with AWS
   KMS, GCP KMS, HashiCorp Vault, or an HSM. The port is small enough
   that any of those is a thin wrapper.")

(defprotocol SubjectKeys
  (get-or-create-key [this subject-id]
    "Idempotent. Returns the raw 256-bit key bytes for this subject,
     generating + storing if not present, returning the existing key
     otherwise. Nil ONLY when the key has been erased.")
  (erase-subject! [this subject-id]
    "Destroys the key. Subsequent get-or-create-key returns nil
     forever. Subsequent decrypt attempts of any ciphertext sealed
     with this key will fail. The historical events are NOT touched -
     they remain immutable, but their PII payload becomes recoverable
     only by re-deriving a key that no longer exists, i.e. never.")
  (erased? [this subject-id]
    "True if the subject's key has been destroyed."))
