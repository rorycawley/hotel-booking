(ns hotel.booking.events-test
  "Behaviour: deciders only ever produce events that match the Malli
   event schema. Pure - no I/O."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.decider :as decider]
            [hotel.booking.room.events :as events]
            [hotel.booking.slices.book-room.decide :as book]
            [hotel.booking.slices.cancel-booking.decide :as cancel]
            [hotel.booking.slices.decommission-room.decide :as decomm]))

(def book-cmd
  {:room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest every-produced-event-matches-the-event-schema
  (let [results [(decider/decide book/decider [] book-cmd)
                 (decider/decide cancel/decider
                                 [{:event/type :room-booked :room-id "101"}]
                                 {:room-id "101"})
                 (decider/decide decomm/decider [] {:room-id "101"})]
        produced (mapcat :events results)]
    (is (= 3 (count produced)) "each decider produced its event")
    (doseq [event produced]
      (is (events/valid? event) (pr-str event)))))

(deftest the-schema-rejects-a-corrupt-event
  (is (false? (events/valid? {:event/type :room-booked :room-id 101}))))
