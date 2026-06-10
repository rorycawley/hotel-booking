(ns hotel.integration.rabbitmq
  "PRODUCTION adapter: integration events to a RabbitMQ topic exchange.
   Component lifecycle: connection + channel opened on start, closed on stop."
  (:require [com.stuartsierra.component :as component]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.basic :as lb]
            [cheshire.core :as json]
            [hotel.integration.protocol :as pub]))

(defrecord RabbitMqPublisher [uri exchange conn channel]
  component/Lifecycle
  (start [this]
    (if channel
      this
      (let [conn (rmq/connect {:uri uri})
            ch   (lch/open conn)]
        (le/declare ch exchange "topic" {:durable true})
        (assoc this :conn conn :channel ch))))
  (stop [this]
    (when channel (lch/close channel))
    (when conn (rmq/close conn))
    (assoc this :conn nil :channel nil))

  pub/IntegrationPublisher
  (publish! [_ topic event]
    (lb/publish channel exchange topic
                (json/generate-string event)
                {:content-type "application/json" :persistent true})))

(defn create [{:keys [uri exchange]}]
  (map->RabbitMqPublisher {:uri uri :exchange exchange}))
