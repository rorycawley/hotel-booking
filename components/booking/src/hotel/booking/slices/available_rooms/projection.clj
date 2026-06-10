(ns hotel.booking.slices.available-rooms.projection
  "PURE read-model projection: fold events into a view.
   DELIBERATELY the lightest slice (ladder rung 1, ADR-0002): no decider,
   no command schema, no dedicated port - it cannot receive invalid input
   and holds no invariant, so it earns no machinery. If requirements grow
   (persisted/cached view), it climbs a rung; not before.")

(def initial {})

(defn project [view event]
  (case (:event/type event)
    :room-booked         (assoc view (:room-id event) :booked)
    :booking-cancelled   (assoc view (:room-id event) :free)
    :room-decommissioned (assoc view (:room-id event) :decommissioned)
    view))

(defn available-rooms [all-events all-room-ids]
  (let [view (reduce project initial all-events)]
    (vec (remove #(contains? #{:booked :decommissioned} (view %)) all-room-ids))))
