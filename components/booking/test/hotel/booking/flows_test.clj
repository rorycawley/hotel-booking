(ns hotel.booking.flows-test
  "The test suite is the FIRST driving adapter (Cockburn, after Ron
   Jeffries): these tests drive the application through its DRIVING
   PORT (hotel.booking.interface), exactly as HTTP does. End-to-end decide -> evolve -> react across slices. 100% in memory."
  (:require [clojure.test :refer [deftest is]]
            [hotel.system.interface :as system]
            [hotel.booking.interface :as api]
            [hotel.booking.slices.move-guest.process :as pm]
            [hotel.booking.decider :as decider]
            [hotel.event-store.interface :as es]
            [hotel.booking.slices.available-rooms.query :as available]))

(defn run!*
  "decide+evolve via the handler, then REACT to the new events.
   This is exactly what the production dispatcher does."
  [handle sys command]
  (let [result (handle sys command)]
    (effects/react-all! sys (:events result))
    result))

(def ada-books-102
  {:room-id "102" :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest a-successful-booking-confirms-announces-and-blocks-the-room
  (let [sys (system/test-system)]
    (is (:events (run!* api/book-room! sys ada-books-102)))
    (is (= ["101" "103"] (api/available-rooms sys))           "room is taken")
    (is (= 1 (count @(:sent (:guest-notifications sys))))         "guest got email")
    (is (= 1 (count @(:published (:publisher sys))))       "world was told")))

(deftest a-rejected-booking-has-no-side-effects
  (let [sys (system/test-system)]
    (run!* api/book-room! sys ada-books-102)
    (is (:error (run!* api/book-room! sys ada-books-102)))
    (is (= 1 (count @(:sent (:guest-notifications sys))))   "no second email")
    (is (= 1 (count @(:published (:publisher sys)))) "no second announcement")))

(deftest cancelling-frees-the-room-for-the-next-guest
  (let [sys (system/test-system)]
    (run!* api/book-room! sys ada-books-102)
    (is (:events (run!* api/cancel-booking! sys {:room-id "102"})))
    (is (= ["101" "102" "103"] (api/available-rooms sys)))
    (is (:events (run!* api/book-room! sys
                        (assoc-in ada-books-102 [:guest :name] "Bob"))))))

(deftest a-decommissioned-room-is-dead-to-every-slice
  (let [sys (system/test-system)]
    (is (:events (run!* api/decommission-room! sys {:room-id "103"})))
    (is (= ["101" "102"] (api/available-rooms sys)))
    ;; the stream is terminal: EVERY decider refuses, forever
    (is (= :stream-is-terminal
           (:error (run!* api/book-room! sys (assoc ada-books-102 :room-id "103")))))
    (is (= :stream-is-terminal
           (:error (run!* api/cancel-booking! sys {:room-id "103"}))))))

(defn- move-status [sys move-id]
  (:status (decider/current-state pm/decider
                                  (es/read-stream (:event-store sys)
                                                  (str "move-" move-id)))))

(def move-ada-102->103
  {:move-id "m1" :guest {:name "Ada" :email "ada@example.com"}
   :from-room "102" :to-room "103"
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest moving-a-guest-is-a-process-with-its-own-stream
  (let [sys (system/test-system)]
    (run!* api/book-room! sys ada-books-102)
    (is (:events (run!* api/move-guest! sys move-ada-102->103)))
    (is (= ["101" "102"] (api/available-rooms sys)) "103 booked, 102 freed")
    (is (= :completed (move-status sys "m1"))    "the PM stream tells the story")))

(deftest a-failed-first-step-changes-nothing
  (let [sys (system/test-system)]
    (run!* api/book-room! sys ada-books-102)
    (run!* api/book-room! sys (assoc ada-books-102 :room-id "103")) ; 103 taken
    (run!* api/move-guest! sys move-ada-102->103)
    (is (= ["101"] (api/available-rooms sys)) "both bookings intact")
    (is (= :failed (move-status sys "m1")))))

(deftest a-failed-second-step-is-compensated
  ;; 102 was never booked, so step 2 (cancel it) fails;
  ;; the PM compensates by re-cancelling the new room.
  (let [sys (system/test-system)]
    (run!* api/move-guest! sys move-ada-102->103)
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "compensation freed 103 - the world is exactly as before")
    (is (= :failed (move-status sys "m1")))))
