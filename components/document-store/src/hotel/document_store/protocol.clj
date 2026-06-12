(ns hotel.document-store.protocol
  "DRIVEN PORT: opaque blob storage for supporting documents (scanned
   IDs, contracts, signed PDFs). Adapters: in-memory (test), MinIO
   (prod). The blob is a byte array; metadata is a small map the
   adapter stores alongside it.

   This port deliberately knows NOTHING about encryption: the booking
   shell layers envelope encryption on top using the SubjectKeys port,
   so destroying the subject's key renders every one of their blobs
   unrecoverable - the crypto-shredding pattern from ADR-0006 applied
   to bytes instead of just fields.")

(defprotocol DocumentStore
  (put-blob! [this {:keys [blob-id bytes content-type metadata]}]
    "Store bytes under `blob-id`. `metadata` is a string->string map the
     adapter persists so callers can round-trip wrapped keys / hashes /
     classification labels. Idempotent on blob-id: re-puts overwrite.")
  (get-blob [this blob-id]
    "Returns {:bytes ^bytes :content-type s :metadata {..}} or nil
     when no blob exists with this id.")
  (head-blob [this blob-id]
    "Returns {:content-type s :metadata {..}} or nil. Does not transfer
     the bytes - useful for cheap existence/integrity checks.")
  (delete-blob! [this blob-id]
    "Hard delete. Rarely used: the GDPR path is crypto-shredding via
     the subject's key, not bytes-deletion (which a backup might not
     respect). Provided for operator cleanup of failed uploads."))
