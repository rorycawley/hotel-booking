(ns hotel.booking.slices.book-room.decide
  "PURE domain core for 'Book a room', expressed as a Decider.
   FSM transition: :available --RoomBooked--> :booked
   Full machinery (ladder rung 3, ADR-0002): a real invariant (no double
   booking) under concurrency on a stream justifies the whole Decider."
  (:require [hotel.booking.room.fsm :as fsm]
            [hotel.booking.room.events :as events]))

(def command-schema
  [:map
   [:room-id   :string]
   [:guest     events/Guest]
   [:check-in  :string]
   [:check-out :string]])

(def decider
  {:command-schema command-schema
   :initial-state  fsm/initial-state
   :evolve         fsm/evolve
   :terminal?      fsm/terminal?
   :decide
   (fn [state command]
     (case (:status state)
       :booked    {:error :room-already-booked}
       :available {:events [{:event/type :room-booked
                             :room-id    (:room-id command)
                             :guest      (:guest command)
                             :check-in   (:check-in command)
                             :check-out  (:check-out command)}]}))})
