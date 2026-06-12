(ns hotel.integration.interface
  "DRIVEN PORT for publishing integration events. Impls: rabbitmq, in-memory.
   `publish!` takes identity METADATA (message-id, correlation-id,
   causation-id) so consumers can dedupe via INBOX and reconstruct causal
   chains. Adapters MUST throw if the broker did not confirm acceptance."
  (:require [hotel.integration.protocol :as protocol]
            [hotel.integration.in-memory :as in-memory]
            [hotel.integration.rabbitmq :as rabbitmq]))

(def publish! protocol/publish!)

(defn in-memory
  ([] (in-memory/create))
  ([opts] (in-memory/create opts)))

(defn rabbitmq [{:keys [uri exchange] :as config}]
  (rabbitmq/create config))
