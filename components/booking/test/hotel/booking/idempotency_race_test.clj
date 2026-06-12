(ns hotel.booking.idempotency-race-test
  "Concurrent retries with the SAME :command/id must give the SAME result
   to BOTH callers - even if they raced through the cached-result check
   before either committed. Without this property the idempotency
   contract is a lie under concurrent retries (a real production
   scenario when a client double-clicks or a flaky network triggers
   parallel retries)."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.event-store.interface :as es]
            [hotel.event-store.protocol :as event-store-port]
            [hotel.system.interface :as system]))

(defrecord ConflictAfterCommandRecordedStore [cache]
  event-store-port/EventStore
  (read-stream [_ _stream-id] [])
  (read-all [_] [])
  (read-after [_ _position _limit] [])
  (stream-ids-with-prefix [_ _prefix] [])
  (transactional-append! [_ op]
    (reset! cache (get-in op [:command-record :result]))
    (throw (ex-info "Concurrency conflict"
                    {:hotel.event-store/error :concurrency-conflict})))

  event-store-port/ProcessedCommands
  (command-result [_ _command-id] @cache))

(deftest concurrent-retries-with-the-same-idempotency-key-give-the-same-result
  ;; Hammer the race: 30 trials of two concurrent calls with one cmd-id.
  ;; Without the cache-recheck on conflict, ~50% of trials show one
  ;; caller getting :concurrency-conflict - the bug we just closed.
  (dotimes [_ 30]
    (let [sys (system/test-system)
          cmd-id (java.util.UUID/randomUUID)
          cmd    {:room-id "101" :command/id cmd-id
                  :guest {:name "X" :email "x@x.io"}
                  :check-in "x" :check-out "y"}
          [r1 r2] (mapv deref
                        [(future (api/book-room! sys cmd))
                         (future (api/book-room! sys cmd))])]
      (is (and (:events r1) (:events r2))
          "both callers see the successful outcome")
      (is (= (mapv :event/id (:events r1))
             (mapv :event/id (:events r2)))
          "and they see the SAME events (idempotent replay)")
      (is (= 1 (count (es/read-stream (:event-store sys) "room-101")))
          "only one event was actually appended"))))

(deftest conflict-replay-returns-decrypted-cached-events
  (let [cache (atom nil)
        sys   (assoc (system/test-system)
                     :event-store (->ConflictAfterCommandRecordedStore cache))
        result (api/book-room!
                sys
                {:room-id "101"
                 :command/id (java.util.UUID/randomUUID)
                 :guest {:name "Ada Lovelace" :email "ada@example.com"}
                 :check-in "x" :check-out "y"})]
    (is (not= "Ada Lovelace" (-> @cache :events first :guest :name))
        "the fake store returns the encrypted persisted command result")
    (is (= "Ada Lovelace" (-> result :events first :guest :name))
        "the conflict replay path decrypts cached events before returning")
    (is (= "ada@example.com" (-> result :events first :guest :email)))))
