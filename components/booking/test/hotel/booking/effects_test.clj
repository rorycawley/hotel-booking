(ns hotel.booking.effects-test
  "Behaviour of the effect interpreter: each effect reaches the right
   driven PORT. Test adapters in memory; swap prod adapters, zero change."
  (:require [clojure.test :refer [deftest is]]
            [hotel.system.interface :as system]
            [hotel.booking.effects :as effects]
            [hotel.booking.interface :as api]))

(deftest notification-effects-go-through-the-guest-notifications-port
  (let [sys (system/test-system)
        booking {:guest {:name "Ada" :email "ada@example.com"}
                 :room-id "101" :check-in "2026-07-01" :check-out "2026-07-03"}]
    (effects/execute! sys (assoc booking :effect/type :notify-booking-confirmed))
    (is (= [booking] @(:sent (:guest-notifications sys))))))

(deftest publish-effects-go-through-the-integration-port
  (let [sys (system/test-system)]
    (effects/execute! sys {:effect/type :publish
                           :topic "booking.room-booked" :payload {:type "RoomBooked"}})
    (is (= [{:topic "booking.room-booked" :event {:type "RoomBooked"}}]
           @(:published (:publisher sys))))))

(deftest dispatch-command-effects-run-the-next-use-case
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "101"
                         :guest {:name "Ada" :email "ada@example.com"}
                         :check-in "2026-07-01" :check-out "2026-07-03"})
    (effects/execute! sys {:effect/type :dispatch-command
                           :command {:command/type :cancel-booking
                                     :room-id "101"}})
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "the dispatched CancelBooking freed the room")))
