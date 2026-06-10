(ns hotel.integration.interface
  "DRIVEN PORT for publishing integration events. Impls: rabbitmq, in-memory."
  (:require [hotel.integration.protocol :as protocol]
            [hotel.integration.in-memory :as in-memory]
            [hotel.integration.rabbitmq :as rabbitmq]))

(def publish! protocol/publish!)

(defn in-memory [] (in-memory/create))
(defn rabbitmq  [{:keys [uri exchange] :as config}] (rabbitmq/create config))
