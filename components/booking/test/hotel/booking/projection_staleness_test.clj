(ns hotel.booking.projection-staleness-test
  "Queries do AT MOST ONE catch-up batch synchronously; the background
   projector drains the rest. If the projection is still behind by more
   than the threshold AFTER that batch, the query records a problem so
   ops sees the staleness instead of it being silently swallowed."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system]))

(defn- valid-room-event [room-id i]
  {:event/type     (if (even? i) :room-booked :booking-cancelled)
   :event/id       (java.util.UUID/randomUUID)
   :correlation-id (java.util.UUID/randomUUID)
   :causation-id   (java.util.UUID/randomUUID)
   :recorded-at    {:earliest 0 :latest 0}
   :room-id        room-id
   :guest          {:name "X" :email "x@x.io"}
   :check-in       "x" :check-out "y"})

(defn- preload! [event-store stream-id n]
  (dotimes [i n]
    (es/append-events! event-store stream-id i
                       [(valid-room-event "101" i)])))

(deftest a-deep-backlog-is-CAUGHT-UP-by-repeated-queries
  ;; Each query advances one batch (1000); a 2500-event backlog takes
  ;; ~3 queries before the projection is fully current.
  (let [sys (system/test-system)]
    (preload! (:event-store sys) "room-101" 2500)
    (is (= 2500 (es/projection-lag (:event-store sys) "rooms")))
    (api/available-rooms sys)
    (is (= 1500 (es/projection-lag (:event-store sys) "rooms"))
        "first query advanced exactly one batch (1000)")
    (api/available-rooms sys)
    (api/available-rooms sys)
    (is (zero? (es/projection-lag (:event-store sys) "rooms"))
        "third query catches up the rest")))

(deftest staleness-past-the-threshold-is-recorded-as-a-problem
  ;; Pre-load 2500 events. First query: advances 1000, still 1500 behind
  ;; -> over threshold (1000) -> records a problem.
  (let [sys (system/test-system)]
    (preload! (:event-store sys) "room-101" 2500)
    (api/available-rooms sys)
    (let [problems @(:problems (:problem-sink sys))]
      (is (some #(= :room-view-stale-after-sync-catchup (:problem %)) problems)
          "the staleness was surfaced to ops"))))

(deftest in-the-happy-path-no-staleness-problem-is-recorded
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "101"
                         :guest {:name "A" :email "a@a.io"}
                         :check-in "x" :check-out "y"})
    (api/available-rooms sys)
    (let [problems @(:problems (:problem-sink sys))]
      (is (not-any? #(= :room-view-stale-after-sync-catchup (:problem %))
                    problems)
          "no staleness recorded when the projection is current"))))
