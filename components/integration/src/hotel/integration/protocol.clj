(ns hotel.integration.protocol
  "PORT: publish integration events to other systems.
   Adapters: rabbitmq, in-memory.")

(defprotocol IntegrationPublisher
  (publish! [this topic event]))
