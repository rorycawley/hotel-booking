(ns hotel.outbox-relay.worker
  "The transactional-outbox RELAY: a background worker that reads pending
   rows from the EventStore.Outbox, hands them to the IntegrationPublisher,
   and stamps published_at once the broker confirmed acceptance.

   Why this exists (the durability guarantee):

     Decider/handle commits events + outbox rows in ONE Postgres TX. The
     broker is NOT in the critical path - it can be down for hours and
     bookings still succeed. The relay catches up later. Conversely, if
     the relay's publish fails (broker down, network), the row stays
     pending and is retried; nothing is lost.

   Exactly-once vs at-least-once:
     The relay guarantees AT-LEAST-ONCE delivery. Consumers must dedupe
     using `message_id` (which is exactly what the INBOX pattern does on
     the other side). Trying to make publish + mark-published atomic in
     two systems is the two-generals problem; we don't try."
  (:require [com.stuartsierra.component :as component]
            [hotel.event-store.interface :as es]
            [hotel.integration.interface :as pub]))

(def ^:private default-batch-size 100)
(def ^:private default-poll-ms    50)
(def ^:private default-backoff-ms 1000)

;; Exponential backoff with a 60s cap. Returns ms.
;; attempts=1 -> 100ms, 2 -> 200ms, 3 -> 400ms, ..., 10 -> 51.2s, capped 60s.
(defn- backoff-ms [attempts]
  (min 60000 (* 100 (long (Math/pow 2 (max 0 (dec attempts)))))))

(defn drain-once!
  "Publish one batch from the outbox. Returns the number of messages
   that were successfully published. Visible for tests + ops.

   Failures defer next_attempt_at by exponential backoff. After
   max_attempts the row is dead-lettered automatically by the adapter."
  [{:keys [event-store publisher batch-size]
    :or {batch-size default-batch-size}}]
  (let [pending (es/claim-outbox-messages event-store batch-size)]
    (reduce
     (fn [n msg]
       (try
         (pub/publish! publisher
                       (:topic msg)
                       (:payload msg)
                       {:message-id     (str (:message-id msg))
                        :correlation-id (str (:correlation-id msg))
                        :causation-id   (some-> msg :causation-id str)})
         (es/mark-outbox-published! event-store (:id msg))
         (inc n)
         (catch Throwable t
           (es/mark-outbox-failed! event-store (:id msg)
                                   (.getMessage t)
                                   (backoff-ms (inc (:attempts msg 0))))
           n)))
     0
     pending)))

(defn- run-loop! [{:keys [running? poll-ms backoff-ms] :as relay}]
  (try
    (while @running?
      (let [published (try (drain-once! relay)
                           (catch Throwable t
                             ;; Hard failure (DB blip): back off, keep running
                             (.println System/err
                                       (str "[outbox-relay] " (.getMessage t)))
                             ::error))]
        (Thread/sleep
         (cond
           (= ::error published) backoff-ms
           (zero? published)     poll-ms
           :else                 0))))
    (catch InterruptedException _
      ;; Component/stop interrupted us during sleep; exit cleanly.
      nil)))

(defrecord OutboxRelay [event-store publisher
                        batch-size poll-ms backoff-ms auto-start?
                        running? thread]
  component/Lifecycle
  (start [this]
    (let [self (assoc this
                      :batch-size (or batch-size default-batch-size)
                      :poll-ms    (or poll-ms default-poll-ms)
                      :backoff-ms (or backoff-ms default-backoff-ms))]
      (cond
        thread self
        ;; auto-start? false -> the component is wired but its worker
        ;; does NOT run (callers drive it via drain-once! - useful in
        ;; tests for deterministic assertions)
        (false? auto-start?) self
        :else
        (let [running? (atom true)
              self     (assoc self :running? running?)
              t        (doto (Thread. ^Runnable
                              #(run-loop! self)
                                      "hotel.outbox-relay")
                         (.setDaemon true)
                         (.start))]
          (assoc self :thread t)))))
  (stop [this]
    (when running? (reset! running? false))
    (when thread   (.interrupt ^Thread thread))
    (assoc this :running? nil :thread nil)))

(defn create [config]
  (map->OutboxRelay config))
