(ns hotel.booking.move-guest-process-test
  "Behaviour of the Move-guest PROCESS MANAGER. All pure:
   decider tests are history+command->events; reactor tests are
   event->next-command-as-data."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.decider :as decider]
            [hotel.booking.slices.move-guest.process :as pm]))

(def request
  {:command/type :request-move
   :move-id "m1" :from-room "102" :to-room "103"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(def requested
  {:event/type :move-requested
   :move-id "m1" :from-room "102" :to-room "103"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

;; ---- the PM decider: an FSM like any other ----

(deftest a-move-can-be-requested-once
  (is (= {:events [requested]} (decider/decide pm/decider [] request)))
  (is (= {:error :move-already-started}
         (decider/decide pm/decider [requested] request))))

(deftest step-results-are-only-accepted-in-the-state-awaiting-them
  (is (= :unexpected-step-result
         (:error (decider/decide pm/decider [requested]
                                 {:command/type :record-old-room-cancelled
                                  :move-id "m1"}))))) ; still in :booking-new-room

(deftest a-completed-move-is-terminal
  (let [history [requested
                 {:event/type :move-new-room-booked    :move-id "m1" :from-room "102"}
                 {:event/type :move-old-room-cancelled :move-id "m1"}]]
    (is (= {:error :stream-is-terminal}
           (decider/decide pm/decider history request)))))

(deftest a-failed-first-step-ends-the-process
  (let [history [requested
                 {:event/type :move-new-room-failed :move-id "m1"
                  :reason :room-already-booked}]]
    (is (true? (pm/terminal? (decider/current-state pm/decider history))))))

;; ---- the PM reactor: each event names the next command ----

(deftest a-requested-move-books-the-new-room-first
  (let [[effect] (pm/react requested)]
    (is (= :dispatch-command (:effect/type effect)))
    (is (= :book-room (get-in effect [:command :command/type])))
    (is (= "103"      (get-in effect [:command :room-id])))
    (is (= :record-new-room-booked (get-in effect [:on-success :command/type])))
    (is (= :record-new-room-failed (get-in effect [:on-failure :command/type])))))

(deftest a-booked-new-room-triggers-cancelling-the-old-one
  (let [[effect] (pm/react {:event/type :move-new-room-booked
                            :move-id "m1" :from-room "102"})]
    (is (= :cancel-booking (get-in effect [:command :command/type])))
    (is (= "102"           (get-in effect [:command :room-id])))))

(deftest a-failed-second-step-compensates-by-recancelling-the-new-room
  (let [[effect] (pm/react {:event/type :move-old-room-failed
                            :move-id "m1" :to-room "103"
                            :guest {:name "Ada" :email "ada@example.com"}
                            :reason :room-not-booked})]
    (is (= :cancel-booking (get-in effect [:command :command/type])))
    (is (= "103"           (get-in effect [:command :room-id])))
    ;; ownership guard MUST carry through compensation: if the to-room
    ;; was re-booked by a different guest while step 2 was failing,
    ;; cancel-booking refuses rather than cancelling someone else.
    (is (= {:name "Ada" :email "ada@example.com"}
           (get-in effect [:command :guest])))
    (is (= :record-compensated (get-in effect [:on-success :command/type])))))

(deftest recording-old-room-failed-denormalises-guest-onto-the-event
  ;; The compensation reactor reads :guest off the event - the decider
  ;; must put it there from PM state.
  (let [history [requested
                 {:event/type :move-new-room-booked
                  :move-id "m1" :from-room "102"
                  :guest {:name "Ada" :email "ada@example.com"}}]
        {:keys [events]} (decider/decide pm/decider history
                                         {:command/type :record-old-room-failed
                                          :move-id "m1"
                                          :reason :booking-guest-mismatch})]
    (is (= {:name "Ada" :email "ada@example.com"}
           (:guest (first events))))))

(deftest terminal-and-uninteresting-events-trigger-nothing
  (is (nil? (pm/react {:event/type :move-old-room-cancelled :move-id "m1"})))
  (is (nil? (pm/react {:event/type :room-booked :room-id "101"}))))
