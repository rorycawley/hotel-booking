(ns hotel.clock.protocol
  "DRIVEN PORT: time. TrueTime-shaped on purpose - the port returns an
   UNCERTAINTY INTERVAL {:earliest ms :latest ms} guaranteed to contain
   true UTC, never a bare instant. This makes clock quality visible in
   the data instead of silently lying, and it makes the central fact of
   distributed time undeniable in code: two overlapping intervals CANNOT
   be ordered. Legal/temporal order comes from the event store's global
   position; these intervals are evidence mapping that order onto UTC.
   Adapters: deterministic (test), system (prod).")

(defprotocol Clock
  (now-interval [this]
    "{:earliest ms :latest ms} - true UTC time is within this bound."))
