(ns hotel.booking.slices.cancel-booking.decide
  "FSM transition: :booked --BookingCancelled--> :available"
  (:require [hotel.booking.room.fsm :as fsm]
            [hotel.booking.room.events :as events]))

(def command-schema
  [:map
   [:room-id :string]
   [:guest {:optional true} events/Guest]])

(def decider
  {:command-schema command-schema
   :initial-state  fsm/initial-state
   :evolve         fsm/evolve
   :terminal?      fsm/terminal?
   :decide
   (fn [state command]
     (case (:status state)
       :available {:error :room-not-booked}
       :booked    (if (and (contains? command :guest)
                           (not= (:guest state) (:guest command)))
                    {:error :booking-guest-mismatch}
                    {:events [{:event/type :booking-cancelled
                               :room-id    (:room-id command)}]})))})
