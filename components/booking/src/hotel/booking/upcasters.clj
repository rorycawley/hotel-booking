(ns hotel.booking.upcasters
  "Pure upcaster registry. Each domain event payload carries `:event/v`;
   the read-side runs upcasters to bring older versions forward to the
   version the current decider/evolve understand.

   Policy (the new ADR):
     - Domain events evolve ADDITIVELY. Adding an optional field is a
       v(n) -> v(n+1) bump - the upcaster sets defaults.
     - Removing a field, renaming a field, or changing semantics also
       requires a version bump and an upcaster.
     - Upcasters are PURE. They run on every read (cheap), so cumulative
       cost across many versions stays bounded.
     - Once v(n+1) is in production and the log has events at v(n+1),
       the v(n) upcaster is FOREVER kept - the journal still has v(n)
       events near the bottom.

   This namespace knows nothing about ports. The upcaster table is data
   the shell looks up after `read-stream` returns.")

(def ^:private upcasters
  "{event-type {version-on-disk upcaster-fn}}.
   An upcaster lifts an event from one version to the next; the runtime
   chains them. Today nothing has been bumped yet, so the table is empty
   - but the seam exists so the FIRST bump is a one-line addition, not
   a refactor.

   Example (when room-booked goes v1 -> v2 by adding :rate):
     :room-booked {1 (fn [e] (assoc e :rate :unknown :event/v 2))}"
  {})

(def ^:private current-versions
  "Event-type -> the version the decider/evolve expect today."
  {:room-booked         1
   :booking-cancelled   1
   :room-decommissioned 1
   :move-requested      1
   :move-new-room-booked    1
   :move-new-room-failed    1
   :move-old-room-cancelled 1
   :move-old-room-failed    1
   :move-compensated        1})

(defn upcast-one
  "PURE. Walk an event from its on-disk :event/v up to the current
   version by chaining the registered upcasters. Returns the event
   unchanged when it is already current."
  [event]
  (let [event-type (:event/type event)
        target     (current-versions event-type)
        from       (or (:event/v event) 1)]
    (loop [e (assoc event :event/v from)]
      (let [v (:event/v e)]
        (cond
          (nil? target)  e             ; unknown event type: pass through
          (>= v target)  e
          :else
          (let [up (get-in upcasters [event-type v])]
            (if up
              (recur (up e))
              ;; No upcaster registered for this step - means we forgot
              ;; to write one. Leave the event as-is rather than throw;
              ;; the decider will likely reject, surfaced via tests.
              e)))))))

(defn upcast-all
  "PURE. Map upcast-one across a sequence of events."
  [events]
  (mapv upcast-one events))
