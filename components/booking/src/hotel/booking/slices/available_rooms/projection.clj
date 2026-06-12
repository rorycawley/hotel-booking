(ns hotel.booking.slices.available-rooms.projection
  "PURE evolve fn for the persistent ROOM VIEW (the CQRS read model
   side). Fed by the projector (event-store brick) past its checkpoint;
   the view itself lives in Postgres. View shape:

     {room-id {:status :booked | :available | :decommissioned
               :guest-name :guest-email :check-in :check-out}}

   The query namespace filters this map down to room-ids the booker can
   take. Kept here because the SHAPE of the read model is a domain
   concern - the projector brick is generic infrastructure.")

(def initial {})

(defn evolve
  "PURE. (view, event) -> view. The view stores ONLY non-PII data so
   that GDPR erasure of a subject (key destruction) doesn't have to
   trigger a view rebuild. If a future query needs guest detail it
   should be served by going back to the event log via decider/handle's
   decrypted read - the only path that knows about subject keys."
  [view event]
  (case (:event/type event)
    :room-booked         (assoc view (:room-id event) {:status :booked})
    :booking-cancelled   (assoc view (:room-id event) {:status :available})
    :room-decommissioned (assoc view (:room-id event) {:status :decommissioned})
    view))

(defn available
  "PURE. Filter the room view down to room-ids that are bookable."
  [view all-room-ids]
  (vec (remove #(let [s (:status (get view % {:status :available}))]
                  (contains? #{:booked :decommissioned} s))
               all-room-ids)))
