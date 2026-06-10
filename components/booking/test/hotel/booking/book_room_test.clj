(ns hotel.booking.book-room-test
  "Behaviour: 'Book a room'. Pure: history in, events out. No I/O."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.decider :as decider]
            [hotel.booking.slices.book-room.decide :as book]))

(def book-cmd
  {:room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest an-available-room-can-be-booked
  (is (= {:events [{:event/type :room-booked
                    :room-id    "101"
                    :guest      {:name "Ada" :email "ada@example.com"}
                    :check-in   "2026-07-01"
                    :check-out  "2026-07-03"}]}
         (decider/decide book/decider [] book-cmd))))

(deftest a-booked-room-cannot-be-booked-again
  (is (= {:error :room-already-booked}
         (decider/decide book/decider
                         [{:event/type :room-booked :room-id "101"}]
                         book-cmd))))

(deftest a-cancelled-room-can-be-booked-again
  (let [history [{:event/type :room-booked       :room-id "101"}
                 {:event/type :booking-cancelled :room-id "101"}]]
    (is (= :room-booked
           (-> (decider/decide book/decider history book-cmd)
               :events first :event/type)))))

(deftest a-decommissioned-room-accepts-no-commands-ever-again
  ;; terminal? - the 7th element of the Decider
  (is (= {:error :stream-is-terminal}
         (decider/decide book/decider
                         [{:event/type :room-decommissioned :room-id "101"}]
                         book-cmd))))

(deftest unknown-events-do-not-break-the-fold
  (is (= :room-already-booked
         (:error (decider/decide book/decider
                                 [{:event/type :room-booked :room-id "101"}
                                  {:event/type :price-changed}]
                                 book-cmd)))))

(deftest a-malformed-command-is-rejected-with-an-explanation
  ;; Malli guards the door: no decision is even attempted
  (let [result (decider/decide book/decider [] {:room-id 101})]
    (is (= :invalid-command (:error result)))
    (is (map? (:explain result)))))
