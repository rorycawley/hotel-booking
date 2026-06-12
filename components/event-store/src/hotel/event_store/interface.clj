(ns hotel.event-store.interface
  "DRIVEN PORTS of the journal brick. The Polylith check enforces that
   other bricks touch only this namespace. Several distinct protocols
   live in one brick because they all share the same Postgres transaction
   in prod (and the same atom in tests) - which is what makes the
   transactional outbox + idempotency guarantees real."
  (:require [hotel.event-store.protocol :as p]
            [hotel.event-store.in-memory :as in-memory]
            [hotel.event-store.postgres :as postgres]
            [hotel.event-store.projector :as projector]))

;; --- EventStore ----------------------------------------------------------
(def read-stream            p/read-stream)
(def read-all               p/read-all)
(def read-after             p/read-after)
(def stream-ids-with-prefix p/stream-ids-with-prefix)
(def transactional-append!  p/transactional-append!)

;; back-compat shim: old call sites used (append-events! store stream-id
;; expected-version events). Keep it working - it routes through the new
;; transactional path with no outbox + no command record. New code should
;; call transactional-append! directly.
(defn append-events! [store stream-id expected-version events]
  (transactional-append!
   store
   {:stream-id        stream-id
    :expected-version expected-version
    :events           events}))

;; --- ProcessedCommands ---------------------------------------------------
(def command-result p/command-result)

;; --- Outbox --------------------------------------------------------------
(def claim-outbox-messages  p/claim-outbox-messages)
(def mark-outbox-published! p/mark-outbox-published!)
(def mark-outbox-failed!    p/mark-outbox-failed!)
(def dead-letter-messages   p/dead-letter-messages)
(def outbox-depth           p/outbox-depth)

;; --- Inbox ---------------------------------------------------------------
(def record-inbound!         p/record-inbound!)
(def mark-inbound-processed! p/mark-inbound-processed!)

(defn handle-inbound!
  "The inbox PATTERN: run `process-fn` until one delivery succeeds, then
   suppress later redeliveries for the same message-id. Returns one of:
     :processed     - first delivery, process-fn ran, message marked
     :already-seen  - duplicate delivery, process-fn skipped
   process-fn throws -> the row stays UN-marked so the next redelivery
   retries (at-least-once)."
  [store {:keys [message-id] :as message} process-fn]
  (case (record-inbound! store message)
    :already-seen :already-seen
    :recorded     (do (process-fn message)
                      (mark-inbound-processed! store message-id)
                      :processed)))

;; --- RoomView ------------------------------------------------------------
(def read-room-view     p/read-room-view)
(def advance-room-view! p/advance-room-view!)
(def projection-lag     p/projection-lag)

;; --- projector COMPONENT (generic; takes a pure evolve-fn) ---------------
(defn projector [config] (projector/create config))

;; --- adapters (the configurator picks one) -------------------------------
(defn in-memory [] (in-memory/create))
(defn postgres  [jdbc-url] (postgres/create jdbc-url))
