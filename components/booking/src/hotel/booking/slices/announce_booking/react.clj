(ns hotel.booking.slices.announce-booking.react
  "PURE reactor: DOMAIN event -> INTEGRATION event. This is the
   translation boundary: the internal fact (rich, keyword-typed, free
   to evolve) becomes the published contract (closed, versioned, no
   internal structure, no PII). Never publish the domain event itself.
   The contract schema lives in hotel.booking.contracts.")

(defn react [event]
  (when (= :room-booked (:event/type event))
    [{:effect/type :publish
      :topic       "booking.room-booked"
      :payload     {:type     "RoomBooked"          ; contracts/room-booked-v1
                    :version  1
                    :room-id  (:room-id event)
                    :check-in (:check-in event)}}]))
