(ns hotel.integration.rabbitmq
  "PRODUCTION adapter: integration events to a RabbitMQ topic exchange.

   Three hardening choices for the durability guarantee:

     1. DURABLE exchange + PERSISTENT messages: brokers survive restarts.
     2. PUBLISHER CONFIRMS (per-message NACK detection): every publish
        waits for the broker's ack. wait-for-confirms returns FALSE on a
        NACK; we treat that as a failed publish and let the outbox relay
        retry / dead-letter.
     3. MANDATORY routing + RETURN LISTENER: a published message with no
        bound queue would otherwise be confirmed AND silently dropped.
        We track basic.return signals on the channel; if one fires
        between publish and confirm, we throw.

   Net effect: every `publish!` either commits durably on the broker or
   throws - never a silent loss."
  (:require [com.stuartsierra.component :as component]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.basic :as lb]
            [langohr.confirm :as lcf]
            [cheshire.core :as json]
            [hotel.integration.protocol :as pub])
  (:import [com.rabbitmq.client ReturnListener]))

(def ^:private confirm-timeout-ms 5000)

(defn- close-quietly [close!]
  (try
    (close!)
    (catch Throwable _ nil)))

(defn- install-return-tracker!
  "Wire a ReturnListener that bumps `returned` on every basic.return.
   A per-publish snapshot lets us detect unroutable-but-confirmed loss."
  [channel returned]
  (.addReturnListener
   channel
   (reify ReturnListener
     (handleReturn [_ _reply-code _reply-text _exchange _routing-key
                    _properties _body]
       (swap! returned inc)))))

(defrecord RabbitMqPublisher [uri exchange conn channel returned]
  component/Lifecycle
  (start [this]
    (if channel
      this
      (let [conn (rmq/connect {:uri uri
                               :automatically-recover false
                               :automatically-recover-topology false})
            ch   (lch/open conn)
            ret  (atom 0)]
        (le/declare ch exchange "topic" {:durable true})
        (lcf/select ch)                              ; publisher confirms
        (install-return-tracker! ch ret)
        (assoc this :conn conn :channel ch :returned ret))))
  (stop [this]
    (when channel (close-quietly #(lch/close channel)))
    (when conn (close-quietly #(rmq/close conn)))
    (assoc this :conn nil :channel nil :returned nil))

  pub/IntegrationPublisher
  (publish! [_ topic event metadata]
    (let [body  (json/generate-string event)
          props {:content-type   "application/json"
                 :persistent     true
                 :message-id     (:message-id metadata)
                 :correlation-id (:correlation-id metadata)
                 :headers        (cond-> {}
                                   (:causation-id metadata)
                                   (assoc "causation-id" (:causation-id metadata)))}
          returns-before @returned]
      (lb/publish channel exchange topic body (assoc props :mandatory true))
      ;; (1) Wait for the broker to ack or nack. Returns false on NACK.
      (when-not (lcf/wait-for-confirms channel confirm-timeout-ms)
        (throw (ex-info "RabbitMQ NACK on publish"
                        {:hotel.integration/error :publish-nacked
                         :topic topic
                         :message-id (:message-id metadata)})))
      ;; (2) Even if confirmed, mandatory + no-queue would have fired a
      ;;     basic.return (async). Detect via the tracker delta.
      (when (> @returned returns-before)
        (throw (ex-info "RabbitMQ returned an unroutable message"
                        {:hotel.integration/error :publish-unroutable
                         :topic topic
                         :message-id (:message-id metadata)}))))))

(defn create [{:keys [uri exchange]}]
  (map->RabbitMqPublisher {:uri uri :exchange exchange}))
