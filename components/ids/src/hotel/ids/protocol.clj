(ns hotel.ids.protocol
  "DRIVEN PORT: identity generation. Production can use random UUIDs;
   tests and replay can use deterministic IDs.")

(defprotocol IdSource
  (new-id [this]
    "Returns a fresh UUID for shell-minted identities.")
  (name-id [this name]
    "Returns a stable UUID for a supplied deterministic name."))
