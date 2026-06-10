(ns hotel.booking.slices.cancel-booking.decide
  "FSM transition: :booked --BookingCancelled--> :available"
  (:require [hotel.booking.room.fsm :as fsm]))

(def command-schema [:map [:room-id :string]])

(def decider
  {:command-schema command-schema
   :initial-state  fsm/initial-state
   :evolve         fsm/evolve
   :terminal?      fsm/terminal?
   :decide
   (fn [state command]
     (case (:status state)
       :available {:error :room-not-booked}
       :booked    {:events [{:event/type :booking-cancelled
                             :room-id    (:room-id command)}]}))})
