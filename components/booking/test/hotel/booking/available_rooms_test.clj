(ns hotel.booking.available-rooms-test
  "Behaviour: 'Which rooms can a guest still book?'. Pure projection.
   Pipeline tested here: (reduce evolve {} events) -> (available view rooms)."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.slices.available-rooms.projection :as proj]))

(def rooms ["101" "102" "103"])

(defn- fold [events] (reduce proj/evolve proj/initial events))

(deftest with-no-history-every-room-is-available
  (is (= rooms (proj/available (fold []) rooms))))

(deftest a-booked-room-is-not-available
  (is (= ["101" "103"]
         (proj/available (fold [{:event/type :room-booked
                                 :room-id "102"
                                 :guest {:name "X" :email "x@x"}
                                 :check-in "a" :check-out "b"}])
                         rooms))))

(deftest a-cancelled-booking-frees-the-room
  (is (= rooms
         (proj/available (fold [{:event/type :room-booked
                                 :room-id "102"
                                 :guest {:name "X" :email "x@x"}
                                 :check-in "a" :check-out "b"}
                                {:event/type :booking-cancelled :room-id "102"}])
                         rooms))))

(deftest a-decommissioned-room-is-gone-for-good
  (is (= ["101" "102"]
         (proj/available (fold [{:event/type :room-decommissioned :room-id "103"}])
                         rooms))))

(deftest events-the-view-does-not-care-about-are-ignored
  (is (= rooms (proj/available (fold [{:event/type :price-changed}]) rooms))))
