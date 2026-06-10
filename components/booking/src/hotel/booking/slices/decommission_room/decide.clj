(ns hotel.booking.slices.decommission-room.decide
  "FSM transition: :available --RoomDecommissioned--> :decommissioned (terminal)."
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
       :booked    {:error :room-still-booked}   ; guests first!
       :available {:events [{:event/type :room-decommissioned
                             :room-id    (:room-id command)}]}))})
