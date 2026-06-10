(ns hotel.booking.slices.send-confirmation.react
  "PURE reactor: RoomBooked -> a DOMAIN-language effect. Note what is
   absent: no email address plumbing, no subject, no body text. The
   guest-notifications impl owns presentation.")

(defn react [event]
  (when (= :room-booked (:event/type event))
    [{:effect/type :notify-booking-confirmed
      :guest      (:guest event)
      :room-id    (:room-id event)
      :check-in   (:check-in event)
      :check-out  (:check-out event)}]))
