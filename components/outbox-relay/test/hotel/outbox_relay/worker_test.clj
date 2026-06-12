(ns hotel.outbox-relay.worker-test
  "Pure in-memory tests for the relay's drain semantics: it publishes
   pending rows, marks them, retries on failure, and is idempotent."
  (:require [clojure.test :refer [deftest is]]
            [hotel.event-store.interface :as es]
            [hotel.integration.interface :as pub]
            [hotel.outbox-relay.interface :as relay])
  (:import [java.util UUID]))

(defn- new-uuid [] (UUID/randomUUID))

(defn- queue-message! [store payload]
  (es/transactional-append!
   store
   {:stream-id nil :expected-version 0
    :events []
    :outbox [{:message-id (new-uuid)
              :correlation-id (new-uuid)
              :causation-id (new-uuid)
              :topic "booking.room-booked"
              :payload payload}]
    :command-record nil}))

(deftest drain-once-publishes-pending-rows-and-marks-them
  (let [store     (es/in-memory)
        publisher (pub/in-memory)
        _         (queue-message! store {:type "RoomBooked" :version 1})]
    (is (= 1 (relay/drain-once!
              {:event-store store :publisher publisher :batch-size 10})))
    (is (empty? (es/claim-outbox-messages store 10)))
    (is (= 1 (count @(:published publisher))))))

(deftest drain-once-is-idempotent-on-an-empty-outbox
  (let [store     (es/in-memory)
        publisher (pub/in-memory)]
    (is (zero? (relay/drain-once!
                {:event-store store :publisher publisher :batch-size 10})))
    (is (= [] @(:published publisher)))))

(deftest publish-failure-leaves-the-row-pending-for-retry
  ;; The relay computes a backoff (100ms first attempt) so the row is
  ;; HIDDEN from claim until next_attempt_at elapses. We wait past that
  ;; backoff before asserting recovery.
  (let [store     (es/in-memory)
        failing?  (atom true)
        publisher (pub/in-memory {:failing? failing?})]
    (queue-message! store {:type "RoomBooked" :version 1})
    (is (zero? (relay/drain-once!
                {:event-store store :publisher publisher :batch-size 10}))
        "publish failed -> no row counted as published")
    (reset! failing? false)
    (Thread/sleep 150)                   ; > 100ms initial backoff
    (is (= 1 (relay/drain-once!
              {:event-store store :publisher publisher :batch-size 10}))
        "after the broker recovers AND backoff elapses, the row goes through")))
