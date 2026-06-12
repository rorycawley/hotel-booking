(ns hotel.system.core
  "THE CONFIGURATOR (Cockburn): the only brick that sees every other
   brick's interface and plugs concrete impls into the ports at startup.

   Production stands on three durability legs:

     1. Transactional outbox:  decider/handle commits events + outbox
        rows in ONE Postgres TX. The broker is never in the critical
        path.
     2. Outbox relay:          background worker drains the outbox into
        RabbitMQ with publisher confirms. If Rabbit is down, the relay
        retries indefinitely - nothing is lost.
     3. CQRS read model:       background projector follows the event
        log past its checkpoint and writes the room_view table.

   The in-memory system WIRES the same components but with
   `auto-start? false` - tests call `flush!` to drain deterministically,
   the room-view query already advances synchronously."
  (:require [com.stuartsierra.component :as component]
            [hotel.booking.interface :as booking]
            [hotel.event-store.interface :as event-store]
            [hotel.integration.interface :as integration]
            [hotel.notifications.interface :as notifications]
            [hotel.problems.interface :as problems]
            [hotel.document-store.interface :as document-store]
            [hotel.subject-keys.interface :as subject-keys]
            [hotel.clock.interface :as clock]
            [hotel.ids.interface :as ids]
            [hotel.outbox-relay.interface :as outbox-relay]
            [hotel.system.pm-sweeper :as pm-sweeper]))

(defn- room-view-projector [{:keys [auto-start?] :or {auto-start? true}}]
  (component/using
   (event-store/projector
    (assoc booking/room-view-projection :auto-start? auto-start?))
   [:event-store]))

(defn- outbox-relay-component [{:keys [auto-start?] :or {auto-start? true}}]
  (component/using
   (outbox-relay/create {:auto-start? auto-start?})
   [:event-store :publisher]))

(defn- pm-sweeper-component [{:keys [auto-start?] :or {auto-start? true}}]
  (component/using
   (pm-sweeper/create {:auto-start? auto-start?})
   [:event-store :clock :command-handlers :reactors :problem-sink]))

(defn in-memory-system
  "Test/REPL system. Components are WIRED (same shape as prod) but
   background workers are not started - tests call `flush!`."
  []
  (component/system-map
   :event-store         (event-store/in-memory)
   :publisher           (integration/in-memory)
   :guest-notifications (notifications/recording)
   :problem-sink        (problems/in-memory)
   :subject-keys        (subject-keys/in-memory)
   :document-store      (document-store/in-memory)
   :clock               (clock/deterministic)
   :ids                 (ids/deterministic)
   :reactors            booking/reactors
   :command-handlers    booking/command-handlers
   :all-room-ids        ["101" "102" "103"]
   :outbox-relay        (outbox-relay-component {:auto-start? false})
   :room-view-projector (room-view-projector {:auto-start? false})
   :pm-sweeper          (pm-sweeper-component {:auto-start? false})))

(defn- prod-clock
  "System clock with a degraded-threshold callback wired to the sink.
   `uncertainty-fn` should be a 0-arg fn reading the live max-error
   from chrony/PTP/ClockBound; nil falls back to the static 5ms."
  [problem-sink uncertainty-fn]
  (clock/system-clock
   {:uncertainty-ms        5
    :uncertainty-fn        uncertainty-fn
    :degraded-threshold-ms 100
    :on-degraded
    (fn [{:keys [uncertainty-ms threshold-ms]}]
      (problems/record-problem!
       problem-sink
       {:problem :clock-uncertainty-over-threshold
        :uncertainty-ms uncertainty-ms
        :threshold-ms threshold-ms}))}))

(defn prod-system [{:keys [jdbc-url rabbit-uri sendgrid-key from-email
                           clock-uncertainty-fn
                           minio-endpoint minio-access-key minio-secret-key
                           minio-bucket]}]
  (let [sink (problems/stderr)]
    (component/system-map
     :event-store         (event-store/postgres jdbc-url)
     :publisher           (integration/rabbitmq {:uri rabbit-uri :exchange "booking"})
     :guest-notifications (notifications/twilio {:api-key sendgrid-key :from from-email})
     :problem-sink        sink
     :subject-keys        (subject-keys/in-memory)
     :document-store      (document-store/minio {:endpoint   minio-endpoint
                                                 :access-key minio-access-key
                                                 :secret-key minio-secret-key
                                                 :bucket     (or minio-bucket "booking-documents")})
     :clock               (prod-clock sink clock-uncertainty-fn)
     :ids                 (ids/random)
     :reactors            booking/reactors
     :command-handlers    booking/command-handlers
     :all-room-ids        ["101" "102" "103"]
     ;; Backpressure: warn at 1k pending rows, reject at 10k. Tunable
     ;; per install - set high enough to ride out brief broker hiccups,
     ;; low enough that an outage cannot grow the queue beyond what the
     ;; relay can drain in a reasonable recovery window.
     :outbox-backpressure {:warn-at 1000 :reject-at 10000}
     :outbox-relay        (outbox-relay-component {:auto-start? true})
     :room-view-projector (room-view-projector {:auto-start? true})
     :pm-sweeper          (pm-sweeper-component {:auto-start? true}))))

(defn test-system
  "A STARTED in-memory system, ready to use."
  []
  (component/start (in-memory-system)))

(defn flush!
  "Drives the outbox relay forward synchronously - tests use this in
   place of waiting for the background worker. Returns the number of
   messages successfully published."
  [system]
  (outbox-relay/drain-once! (:outbox-relay system)))
