(ns hotel.outbox-relay.integration-test
  "HEADLINE durability test: with Postgres + RabbitMQ in Docker, prove
   that a booking written while the broker is DOWN still ends up published
   once the broker is back up - nothing is lost.

   Tagged ^:integration: needs Docker. Run:
       clojure -X:dev:test :excludes '[]'"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [cheshire.core :as json]
            [next.jdbc :as jdbc]
            [hotel.event-store.testcontainers :as tc]
            [hotel.event-store.postgres :as pg]
            [hotel.integration.rabbitmq :as rmq-adapter]
            [hotel.outbox-relay.worker :as relay]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.basic :as lb])
  (:import [org.testcontainers.containers RabbitMQContainer]))

(def ^:dynamic *pg*     nil)
(def ^:dynamic *rabbit* nil)

(defn- with-containers [f]
  (let [pg     (tc/start-postgres!)
        rabbit (tc/start-rabbit!)]
    (try
      (binding [*pg* pg *rabbit* rabbit] (f))
      (finally
        (.stop rabbit)
        (.stop pg)))))

(use-fixtures :once with-containers)

(defn- subscribe!
  "Declare exchange + queue + binding SYNCHRONOUSLY. Returns
   {:poll-fn, :close-fn}. poll-fn does basic.get in a loop with backoff -
   simpler than push-style consumer registration and immune to the race
   where a published message arrives before the consumer is registered."
  [uri exchange topic]
  (let [conn (rmq/connect {:uri uri})
        ch   (lch/open conn)
        _    (le/declare ch exchange "topic" {:durable true})
        q    (.getQueue (lq/declare ch "" {:exclusive true}))
        _    (lq/bind ch q exchange {:routing-key topic})]
    {:poll-fn  (fn [timeout-ms]
                 (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
                   (loop []
                     ;; langohr.basic/get returns [meta payload-bytes] or nil
                     (let [[_ payload] (lb/get ch q true)]
                       (cond
                         payload (json/parse-string (String. ^bytes payload "UTF-8") true)
                         (> (System/currentTimeMillis) deadline) nil
                         :else (do (Thread/sleep 50) (recur)))))))
     :close-fn (fn [] (lch/close ch) (rmq/close conn))}))

(deftest ^:integration a-booking-survives-rabbit-being-down-then-up
  (let [pg-url     (tc/jdbc-url *pg*)
        ds         (jdbc/get-datasource {:jdbcUrl pg-url})
        store      (component/start (pg/create pg-url))
        msg-id     (java.util.UUID/randomUUID)
        corr-id    (java.util.UUID/randomUUID)
        causation  (java.util.UUID/randomUUID)]

    ;; Step 1: write event + outbox row in ONE Postgres TX, while the
    ;; broker is DEAD. This is the whole durability point - the broker
    ;; is never in the critical path.
    (.stop ^RabbitMQContainer *rabbit*)
    (jdbc/with-transaction [tx ds]
      (jdbc/execute!
       tx
       ["insert into events
          (stream_id, version, event_id, correlation_id, causation_id, payload)
         values (?, ?, ?, ?, ?, ?::jsonb)"
        "room-101" 1 (java.util.UUID/randomUUID) corr-id corr-id
        (json/generate-string {:event/type :room-booked :room-id "101"})])
      (jdbc/execute!
       tx
       ["insert into outbox (message_id, correlation_id, causation_id, topic, payload)
         values (?, ?, ?, ?, ?::jsonb)"
        msg-id corr-id causation "booking.room-booked"
        (json/generate-string {:type "RoomBooked" :version 1
                               :room-id "101" :check-in "2026-07-01"})]))
    (is (seq (jdbc/execute! ds ["select 1 from outbox where published_at is null"]))
        "outbox holds the message while the broker is dead")

    ;; Step 2: bring Rabbit back, drain the relay, the message lands.
    (let [rabbit2 (tc/start-rabbit!)]
      (try
        (let [uri         (tc/rabbit-uri rabbit2)
              ;; Set up the consumer (queue + binding) BEFORE the producer
              ;; publishes, so the message has somewhere to land.
              {:keys [poll-fn close-fn]}
              (subscribe! uri "booking" "booking.room-booked")
              pub        (component/start (rmq-adapter/create
                                           {:uri uri :exchange "booking"}))
              published  (relay/drain-once!
                          {:event-store store :publisher pub :batch-size 10})]
          (is (= 1 published) "relay published one message")
          (let [body (poll-fn 10000)]
            (is (some? body)             "broker delivered the message")
            (is (= "RoomBooked" (:type body))))
          (is (empty? (jdbc/execute! ds ["select 1 from outbox where published_at is null"]))
              "outbox row is now marked published")
          (close-fn)
          (component/stop pub))
        (finally
          (.stop rabbit2))))
    (component/stop store)))
