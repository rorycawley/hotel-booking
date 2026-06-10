(ns hotel.booking.available-rooms-test
  "Behaviour: 'Which rooms can a guest still book?'. Pure projection."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.slices.available-rooms.projection :as proj]))

(def rooms ["101" "102" "103"])

(deftest with-no-history-every-room-is-available
  (is (= rooms (proj/available-rooms [] rooms))))

(deftest a-booked-room-is-not-available
  (is (= ["101" "103"]
         (proj/available-rooms [{:event/type :room-booked :room-id "102"}]
                               rooms))))

(deftest a-cancelled-booking-frees-the-room
  (is (= rooms
         (proj/available-rooms [{:event/type :room-booked       :room-id "102"}
                                {:event/type :booking-cancelled :room-id "102"}]
                               rooms))))

(deftest a-decommissioned-room-is-gone-for-good
  (is (= ["101" "102"]
         (proj/available-rooms [{:event/type :room-decommissioned :room-id "103"}]
                               rooms))))

(deftest events-the-view-does-not-care-about-are-ignored
  (is (= rooms (proj/available-rooms [{:event/type :price-changed}] rooms))))
