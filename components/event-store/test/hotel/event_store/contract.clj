(ns hotel.event-store.contract
  "One set of behaviours that EVERY adapter must honour. Run in-memory
   (fast suite) and against a Testcontainers-backed Postgres (integration).
   If both pass, swapping adapters cannot change behaviour - the whole
   promise of the hexagon, proven."
  (:require [clojure.test :refer [is testing]]
            [hotel.event-store.interface :as es])
  (:import [java.util UUID]))

;; --- domain-shaped events used across the contract ----------------------

(defn- new-uuid [] (UUID/randomUUID))

(def e1 {:event/type :room-booked       :room-id "1"
         :guest {:name "X" :email "x@x"} :check-in "a" :check-out "b"})
(def e2 {:event/type :booking-cancelled :room-id "1"})
(def e3 {:event/type :room-booked       :room-id "2"
         :guest {:name "Y" :email "y@y"} :check-in "a" :check-out "b"})

(defn- stamp [e]
  (assoc e :event/id (new-uuid) :correlation-id (new-uuid) :causation-id (new-uuid)))

(defn- thrown-conflict? [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (= :concurrency-conflict
         (:hotel.event-store/error (ex-data e))))))

(defn- room-view-evolve [view event]
  (case (:event/type event)
    :room-booked         (assoc view (:room-id event) {:status :booked})
    :booking-cancelled   (assoc view (:room-id event) {:status :available})
    :room-decommissioned (assoc view (:room-id event) {:status :decommissioned})
    view))

;; --- the contract -------------------------------------------------------

(defn verify-contract
  "fresh-store: 0-arg fn returning an EMPTY (started) store."
  [fresh-store]

  ;; === EventStore =======================================================

  (testing "a stream that was never written to is empty"
    (is (= [] (es/read-stream (fresh-store) "room-1"))))

  (testing "appended events come back, in order, with their data intact"
    (let [store (fresh-store)
          es-stamped [(stamp e1) (stamp e2)]]
      (es/append-events! store "room-1" 0 [(first es-stamped)])
      (es/append-events! store "room-1" 1 [(second es-stamped)])
      (let [read (es/read-stream store "room-1")]
        (is (= 2 (count read)))
        (is (= :room-booked       (-> read first  :event/type)))
        (is (= :booking-cancelled (-> read second :event/type))))))

  (testing "streams are isolated from each other"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [(stamp e1)])
      (es/append-events! store "room-2" 0 [(stamp e3)])
      (is (= 1 (count (es/read-stream store "room-1"))))
      (is (= 1 (count (es/read-stream store "room-2"))))))

  (testing "read-all returns every event in global append order"
    (let [store (fresh-store)
          sa (stamp e1) sb (stamp e3) sc (stamp e2)]
      (es/append-events! store "room-1" 0 [sa])
      (es/append-events! store "room-2" 0 [sb])
      (es/append-events! store "room-1" 1 [sc])
      (is (= [:room-booked :room-booked :booking-cancelled]
             (map :event/type (es/read-all store))))))

  (testing "a stale expected-version is rejected (optimistic concurrency)"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [(stamp e1)])
      (is (thrown-conflict?
           #(es/append-events! store "room-1" 0 [(stamp e1)])))))

  (testing "stream-ids-with-prefix returns matching streams (and only those)"
    (let [store (fresh-store)]
      (es/append-events! store "move-m1"   0 [(stamp e1)])
      (es/append-events! store "move-m2"   0 [(stamp e1)])
      (es/append-events! store "room-101"  0 [(stamp e1)])
      (is (= ["move-m1" "move-m2"]
             (es/stream-ids-with-prefix store "move-"))
          "matches the prefix, sorted, no room-* leakage")
      (is (= [] (es/stream-ids-with-prefix store "nope-")))))

  (testing "a future expected-version is rejected instead of creating gaps"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [(stamp e1)])
      (is (thrown-conflict?
           #(es/append-events! store "room-1" 10 [(stamp e2)])))
      (is (= 1 (count (es/read-stream store "room-1"))))))

  ;; === ProcessedCommands + transactional-append! =========================

  (testing "transactional-append! records events, outbox, and command atomically"
    (let [store      (fresh-store)
          cmd-id     (new-uuid)
          corr-id    (new-uuid)
          event      (assoc (stamp e1) :correlation-id corr-id :causation-id cmd-id)
          msg-id     (new-uuid)
          outbox-msg {:message-id msg-id :correlation-id corr-id
                      :causation-id (:event/id event)
                      :topic "booking.room-booked"
                      :payload {:type "RoomBooked" :version 1 :room-id "1"
                                :check-in "a"}}]
      (es/transactional-append!
       store
       {:stream-id "room-1" :expected-version 0
        :events    [event]
        :outbox    [outbox-msg]
        :command-record {:command-id cmd-id
                         :command-type :book-room
                         :result {:events [event]
                                  :correlation-id corr-id}}})
      (is (= 1 (count (es/read-stream store "room-1"))))
      (is (= 1 (count (es/claim-outbox-messages store 10))))
      (is (some? (es/command-result store cmd-id)))))

  (testing "command-result is nil for unseen commands"
    (let [store (fresh-store)]
      (is (nil? (es/command-result store (new-uuid))))))

  (testing "command-result returns identical events with UUID identity preserved"
    (let [store   (fresh-store)
          cmd-id  (new-uuid)
          corr-id (new-uuid)
          stamped (assoc (stamp e1) :correlation-id corr-id :causation-id cmd-id)
          result  {:events [stamped] :correlation-id corr-id}]
      (es/transactional-append!
       store
       {:stream-id nil :expected-version 0
        :events [] :outbox []
        :command-record {:command-id   cmd-id
                         :command-type :book-room
                         :result       result}})
      (let [back (es/command-result store cmd-id)]
        (is (= 1 (count (:events back))))
        (is (= (:event/id stamped) (:event/id (first (:events back))))
            "event-id round-trips as a UUID, not a string")
        (is (= corr-id (:correlation-id back))
            "correlation-id round-trips as a UUID")
        (is (uuid? (:event/id (first (:events back))))
            "type is preserved across JSONB serialization"))))

  (testing "duplicate command-id is rejected before appending new events"
    (let [store   (fresh-store)
          cmd-id  (new-uuid)
          corr-id (new-uuid)
          first-event  (assoc (stamp e1) :correlation-id corr-id :causation-id cmd-id)
          second-event (assoc (stamp e3) :correlation-id corr-id :causation-id cmd-id)]
      (es/transactional-append!
       store
       {:stream-id "room-1" :expected-version 0
        :events [first-event] :outbox []
        :command-record {:command-id   cmd-id
                         :command-type :book-room
                         :result       {:events [first-event]
                                        :correlation-id corr-id}}})
      (is (thrown-conflict?
           #(es/transactional-append!
             store
             {:stream-id "room-2" :expected-version 0
              :events [second-event] :outbox []
              :command-record {:command-id   cmd-id
                               :command-type :book-room
                               :result       {:events [second-event]
                                              :correlation-id corr-id}}}))
          "same command-id cannot create another stream write")
      (is (= [] (es/read-stream store "room-2"))
          "the duplicate command aborts before appending events")))

  ;; === Outbox ===========================================================

  (testing "mark-outbox-published! moves a row out of pending"
    (let [store (fresh-store)
          msg   {:message-id (new-uuid) :correlation-id (new-uuid)
                 :causation-id (new-uuid)
                 :topic "booking.room-booked"
                 :payload {:type "RoomBooked" :version 1 :room-id "1"
                           :check-in "a"}}]
      (es/transactional-append!
       store {:stream-id nil :expected-version 0
              :events [] :outbox [msg]
              :command-record nil})
      (let [[row] (es/claim-outbox-messages store 10)]
        (es/mark-outbox-published! store (:id row))
        (is (empty? (es/claim-outbox-messages store 10))))))

  (testing "mark-outbox-failed! defers re-claim by the backoff window"
    (let [store (fresh-store)
          msg   {:message-id (new-uuid) :correlation-id (new-uuid)
                 :causation-id (new-uuid)
                 :topic "booking.room-booked"
                 :payload {:type "RoomBooked" :version 1 :room-id "1"
                           :check-in "a"}}]
      (es/transactional-append!
       store {:stream-id nil :expected-version 0
              :events [] :outbox [msg] :command-record nil})
      (let [[row] (es/claim-outbox-messages store 10)]
        ;; backoff=0 -> immediately re-claimable
        (es/mark-outbox-failed! store (:id row) "broker down" 0)
        (is (= 1 (count (es/claim-outbox-messages store 10)))
            "row is re-claimable after the backoff elapses"))))

  (testing "mark-outbox-failed! with a future backoff hides the row from claim"
    (let [store (fresh-store)
          msg   {:message-id (new-uuid) :correlation-id (new-uuid)
                 :causation-id (new-uuid)
                 :topic "booking.room-booked"
                 :payload {:type "RoomBooked" :version 1 :room-id "1"
                           :check-in "a"}}]
      (es/transactional-append!
       store {:stream-id nil :expected-version 0
              :events [] :outbox [msg] :command-record nil})
      (let [[row] (es/claim-outbox-messages store 10)]
        (es/mark-outbox-failed! store (:id row) "broker down" 60000)
        (is (empty? (es/claim-outbox-messages store 10))
            "row hidden until next_attempt_at elapses"))))

  (testing "claim-outbox-messages is ATOMIC: concurrent calls return disjoint sets"
    (let [store (fresh-store)
          n     50]
      (es/transactional-append!
       store {:stream-id nil :expected-version 0 :events []
              :outbox (vec (repeatedly
                            n
                            #(hash-map :message-id (new-uuid)
                                       :correlation-id (new-uuid)
                                       :causation-id (new-uuid)
                                       :topic "booking.room-booked"
                                       :payload {:type "RoomBooked"
                                                 :version 1 :room-id "1"
                                                 :check-in "a"})))
              :command-record nil})
      ;; 8 concurrent claimers, each asking for up to 50. With atomic
      ;; claim each row appears in AT MOST ONE caller's result.
      (let [results (->> (range 8)
                         (mapv (fn [_]
                                 (future (es/claim-outbox-messages store 50))))
                         (mapv deref))
            all-ids (mapcat #(map :id %) results)]
        (is (= (count all-ids) (count (set all-ids)))
            "no row was claimed by two threads")
        (is (= n (count (set all-ids)))
            "every row was claimed by exactly one thread"))))

  (testing "outbox-depth counts only pending (unpublished) rows"
    (let [store (fresh-store)
          mk    (fn [] {:message-id (new-uuid) :correlation-id (new-uuid)
                        :causation-id (new-uuid)
                        :topic "booking.room-booked"
                        :payload {:type "RoomBooked" :version 1 :room-id "1"
                                  :check-in "a"}})]
      (is (zero? (es/outbox-depth store)) "empty initially")
      (es/transactional-append!
       store {:stream-id nil :expected-version 0
              :events [] :outbox [(mk) (mk) (mk)] :command-record nil})
      (is (= 3 (es/outbox-depth store)) "all three are pending")
      (let [[row] (es/claim-outbox-messages store 1)]
        (es/mark-outbox-published! store (:id row)))
      (is (= 2 (es/outbox-depth store)) "published row no longer counts")))

  (testing "a permanently failing row is dead-lettered after max_attempts"
    (let [store (fresh-store)
          msg   {:message-id (new-uuid) :correlation-id (new-uuid)
                 :causation-id (new-uuid)
                 :topic "booking.room-booked"
                 :payload {:type "RoomBooked" :version 1 :room-id "1"
                           :check-in "a"}}]
      (es/transactional-append!
       store {:stream-id nil :expected-version 0
              :events [] :outbox [msg] :command-record nil})
      ;; default max_attempts is 10; fail 10 times
      (dotimes [_ 10]
        (let [[row] (es/claim-outbox-messages store 10)]
          (es/mark-outbox-failed! store (:id row) "permafail" 0)))
      (is (empty? (es/claim-outbox-messages store 10))
          "outbox no longer holds the row")
      (is (= 1 (count (es/dead-letter-messages store 10)))
          "the row landed in the dead letter")
      (is (= "permafail" (-> (es/dead-letter-messages store 10) first :last-error)))))

  ;; === Inbox ============================================================

  (testing "record-inbound! suppresses messages only after processing"
    (let [store  (fresh-store)
          msg-id (new-uuid)
          msg    {:message-id msg-id :topic "ext.thing" :payload {:hello "world"}}]
      (is (= :recorded     (es/record-inbound! store msg)))
      (is (= :recorded     (es/record-inbound! store msg))
          "unprocessed redelivery is eligible for retry")
      (es/mark-inbound-processed! store msg-id)
      (is (= :already-seen (es/record-inbound! store msg)))))

  (testing "handle-inbound! runs the process fn AT MOST ONCE per message-id"
    (let [store     (fresh-store)
          msg       {:message-id (new-uuid)
                     :topic "ext.guest-preference-changed"
                     :payload {:guest "Ada" :pref :high-floor}}
          processed (atom 0)
          process   (fn [_] (swap! processed inc))]
      ;; first delivery -> runs
      (is (= :processed    (es/handle-inbound! store msg process)))
      ;; broker redelivers the SAME message -> skipped
      (is (= :already-seen (es/handle-inbound! store msg process)))
      (is (= :already-seen (es/handle-inbound! store msg process)))
      (is (= 1 @processed)
          "the consumer's process-fn ran exactly once, no matter how many
           times the broker redelivers")))

  (testing "handle-inbound! does not mark processed if process-fn throws"
    (let [store (fresh-store)
          msg   {:message-id (new-uuid) :topic "ext.flaky"
                 :payload {:n 1}}
          attempts (atom 0)]
      (is (thrown? Exception
                   (es/handle-inbound! store msg
                                       (fn [_]
                                         (swap! attempts inc)
                                         (throw (ex-info "boom" {}))))))
      ;; The row is recorded but NOT processed; the consumer's redelivery
      ;; loop is free to retry it. (Marking only happens AFTER process-fn
      ;; returns normally.)
      (is (= 1 @attempts))
      (is (= :processed
             (es/handle-inbound! store msg (fn [_] (swap! attempts inc)))))
      (is (= 2 @attempts))
      (is (= :already-seen
             (es/handle-inbound! store msg (fn [_] (swap! attempts inc)))))
      (is (= 2 @attempts))))

  ;; === RoomView (persistent CQRS read model) ===========================

  (testing "advance-room-view! drains events past the checkpoint, idempotent"
    (let [store (fresh-store)
          sa (stamp e1) sb (stamp e2)]
      (es/append-events! store "room-1" 0 [sa])
      (es/append-events! store "room-1" 1 [sb])
      (is (= 2 (es/advance-room-view! store "rooms" room-view-evolve 100)))
      (is (= 0 (es/advance-room-view! store "rooms" room-view-evolve 100))
          "idempotent once caught up")
      (is (= :available (:status (get (es/read-room-view store) "1"))))))

  (testing "projection-lag returns events behind the checkpoint"
    (let [store (fresh-store)
          sa (stamp e1) sb (stamp e2)]
      (is (zero? (es/projection-lag store "rooms")) "no events: lag 0")
      (es/append-events! store "room-1" 0 [sa])
      (es/append-events! store "room-1" 1 [sb])
      (is (= 2 (es/projection-lag store "rooms"))
          "two events un-projected")
      (es/advance-room-view! store "rooms" room-view-evolve 100)
      (is (zero? (es/projection-lag store "rooms"))
          "after catch-up, lag is 0")))

  (testing "advance-room-view! catches up from a DEEP backlog in batches"
    (let [store (fresh-store)
          n     500]
      ;; build a backlog of 500 events
      (dotimes [i n]
        (es/append-events!
         store "room-1" i
         [(stamp (if (even? i)
                   {:event/type :room-booked :room-id "1"
                    :guest {:name "X" :email "x@x"} :check-in "a" :check-out "b"}
                   {:event/type :booking-cancelled :room-id "1"}))]))
      (is (= n (es/projection-lag store "rooms")))
      ;; Drain in BATCHES of 100 - the catch-up loop the projector runs.
      (loop [steps 0]
        (let [applied (es/advance-room-view! store "rooms" room-view-evolve 100)]
          (if (zero? applied)
            (do (is (zero? (es/projection-lag store "rooms")) "fully caught up")
                (is (>= steps 4) "took several batches, no fold-the-world"))
            (do (is (< steps 100) "shouldn't loop unbounded")
                (recur (inc steps))))))
      ;; The room's final state is consistent with the alternation
      ;; (even N -> last event was :booking-cancelled -> available)
      (is (= :available (:status (get (es/read-room-view store) "1")))))))
