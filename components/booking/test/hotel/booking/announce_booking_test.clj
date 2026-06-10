(ns hotel.booking.announce-booking-test
  "Behaviour: 'Other systems learn about a booking - via a public contract.'
   Pure reactor, pure test."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.contracts :as contracts]
            [hotel.booking.slices.announce-booking.react :as announce]))

(def room-booked
  {:event/type :room-booked :room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest a-booking-is-announced-on-the-booking-topic
  (let [[effect] (announce/react room-booked)]
    (is (= :publish (:effect/type effect)))
    (is (= "booking.room-booked" (:topic effect)))
    (is (= "RoomBooked" (get-in effect [:payload :type])))
    (is (= "101"        (get-in effect [:payload :room-id])))))

(deftest the-internal-event-does-not-leak-personal-data
  (let [[effect] (announce/react room-booked)]
    (is (nil? (get-in effect [:payload :guest])))))

(deftest other-events-are-not-announced
  (is (empty? (announce/react {:event/type :booking-cancelled :room-id "101"}))))

(deftest the-published-payload-honours-the-versioned-contract
  ;; the contract schema is CLOSED: any leaked internal field fails this
  (let [[effect] (announce/react room-booked)]
    (is (true? (contracts/valid? (:topic effect) (:payload effect))))))

(deftest the-contract-rejects-internal-structure
  (is (false? (contracts/valid? "booking.room-booked"
                                {:type "RoomBooked" :version 1 :room-id "101"
                                 :check-in "2026-07-01"
                                 :event/type :room-booked})) ; internal keyword leaking
      "closed schema: domain shape cannot cross the boundary"))
