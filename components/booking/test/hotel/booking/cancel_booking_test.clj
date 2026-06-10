(ns hotel.booking.cancel-booking-test
  "Behaviour: 'Cancel booking'. Pure, no I/O."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.decider :as decider]
            [hotel.booking.slices.cancel-booking.decide :as cancel]))

(def ada {:name "Ada" :email "ada@example.com"})
(def bob {:name "Bob" :email "bob@example.com"})

(deftest a-booked-room-can-be-cancelled
  (is (= {:events [{:event/type :booking-cancelled :room-id "101"}]}
         (decider/decide cancel/decider
                         [{:event/type :room-booked :room-id "101"}]
                         {:room-id "101"}))))

(deftest a-room-that-was-never-booked-cannot-be-cancelled
  (is (= {:error :room-not-booked}
         (decider/decide cancel/decider [] {:room-id "101"}))))

(deftest cancelling-twice-is-rejected-the-second-time
  (is (= {:error :room-not-booked}
         (decider/decide cancel/decider
                         [{:event/type :room-booked       :room-id "101"}
                          {:event/type :booking-cancelled :room-id "101"}]
                         {:room-id "101"}))))

(deftest cancelling-with-a-matching-guest-is-accepted
  (is (= {:events [{:event/type :booking-cancelled :room-id "101"}]}
         (decider/decide cancel/decider
                         [{:event/type :room-booked :room-id "101" :guest ada}]
                         {:room-id "101" :guest ada}))))

(deftest cancelling-with-the-wrong-guest-is-rejected
  (is (= {:error :booking-guest-mismatch}
         (decider/decide cancel/decider
                         [{:event/type :room-booked :room-id "101" :guest bob}]
                         {:room-id "101" :guest ada}))))
