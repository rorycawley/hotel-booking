(ns hotel.booking.backpressure-test
  "When the outbox is full enough that the relay can't catch up, new
   commands are REJECTED (not silently queued) so the system fails fast
   rather than degrading invisibly. Below the hard cap but past warning,
   the depth is recorded as a problem so ops gets early notice."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system]))

(def cmd
  {:room-id "101" :guest {:name "A" :email "a@a.io"}
   :check-in "x" :check-out "y"})

(defn- pre-fill-outbox! [event-store n]
  (let [msg (fn []
              {:message-id (java.util.UUID/randomUUID)
               :correlation-id (java.util.UUID/randomUUID)
               :causation-id (java.util.UUID/randomUUID)
               :topic "booking.room-booked"
               :payload {:type "RoomBooked" :version 1 :room-id "x"
                         :check-in "a"}})]
    (es/transactional-append!
     event-store {:stream-id nil :expected-version 0
                  :events [] :outbox (repeatedly n msg)
                  :command-record nil})))

(deftest commands-are-rejected-once-the-outbox-exceeds-the-hard-cap
  (let [sys (assoc (system/test-system)
                   :outbox-backpressure {:warn-at 3 :reject-at 5})]
    (pre-fill-outbox! (:event-store sys) 5)
    (let [result (api/book-room! sys cmd)]
      (is (= :system-overloaded (:error result)))
      (is (= 5 (:outbox-depth result)))
      (is (empty? (es/read-stream (:event-store sys) "room-101"))
          "no event was appended"))))

(deftest depth-past-the-warning-records-a-problem-but-still-succeeds
  (let [sys (assoc (system/test-system)
                   :outbox-backpressure {:warn-at 3 :reject-at 100})]
    (pre-fill-outbox! (:event-store sys) 3)
    (let [result (api/book-room! sys cmd)]
      (is (:events result) "still succeeds below the hard cap")
      (let [problems @(:problems (:problem-sink sys))]
        (is (some #(= :outbox-depth-over-warning (:problem %)) problems)
            "but a problem was recorded")))))

(deftest without-backpressure-config-no-check-happens
  ;; in-memory test-system has no :outbox-backpressure - default behaviour
  (let [sys (system/test-system)]
    (pre-fill-outbox! (:event-store sys) 10000)
    (is (:events (api/book-room! sys cmd))
        "commands still flow when backpressure isn't configured")))
