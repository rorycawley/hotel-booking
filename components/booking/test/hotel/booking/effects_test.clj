(ns hotel.booking.effects-test
  "Behaviour of the effect interpreter: each effect reaches the right
   driven PORT. Test adapters in memory; swap prod adapters, zero change."
  (:require [clojure.test :refer [deftest is]]
            [hotel.system.interface :as system]
            [hotel.booking.effects :as effects]
            [hotel.booking.interface :as api]))

(def booking-command
  {:room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01"
   :check-out "2026-07-03"})

(deftest notification-effects-go-through-the-guest-notifications-port
  (let [sys (system/test-system)
        booking {:guest {:name "Ada" :email "ada@example.com"}
                 :room-id "101" :check-in "2026-07-01" :check-out "2026-07-03"}]
    (effects/execute! sys (assoc booking :effect/type :notify-booking-confirmed))
    (is (= [booking] @(:sent (:guest-notifications sys))))))

(deftest publish-effects-go-through-the-integration-port
  (let [sys (system/test-system)]
    (effects/execute! sys {:effect/type :publish
                           :topic "booking.room-booked"
                           :payload {:type "RoomBooked"
                                     :version 1
                                     :room-id "101"
                                     :check-in "2026-07-01"}})
    (is (= [{:topic "booking.room-booked"
             :event {:type "RoomBooked"
                     :version 1
                     :room-id "101"
                     :check-in "2026-07-01"}}]
           @(:published (:publisher sys))))))

(deftest invalid-publish-effects-are-rejected-before-the-port
  (let [sys (system/test-system)
        result (effects/execute! sys {:effect/type :publish
                                      :topic "booking.room-booked"
                                      :payload {:type "RoomBooked"}})]
    (is (= :invalid-integration-event (:error result)))
    (is (= [] @(:published (:publisher sys))))))

(deftest reactor-errors-are-surfaced-on-the-use-case-result
  (let [sys (assoc (system/test-system)
                   :reactors [(fn [_]
                                [{:effect/type :publish
                                  :topic "booking.room-booked"
                                  :payload {:type "RoomBooked"}}])])
        result (api/book-room! sys booking-command)]
    (is (:events result))
    (is (= :invalid-integration-event
           (-> result :effect-errors first :error)))
    (is (= [] @(:published (:publisher sys))))))

(deftest dispatch-command-continuation-errors-are-returned
  (let [sys {:reactors []
             :command-handlers
             {:first  (fn [_ _] {:events []})
              :second (fn [_ _] {:error :continuation-failed})}}
        result (effects/execute! sys {:effect/type :dispatch-command
                                      :command {:command/type :first}
                                      :on-success {:command/type :second}})]
    (is (= :continuation-failed (:error result)))))

(deftest dispatch-command-effects-run-the-next-use-case
  (let [sys (system/test-system)]
    (api/book-room! sys booking-command)
    (effects/execute! sys {:effect/type :dispatch-command
                           :command {:command/type :cancel-booking
                                     :room-id "101"}})
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "the dispatched CancelBooking freed the room")))
