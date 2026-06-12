(ns hotel.integration.protocol
  "PORT: publish integration events to other systems. Adapters: rabbitmq,
   in-memory. The `metadata` map carries identity headers (message-id,
   correlation-id, causation-id) the broker propagates as AMQP headers,
   so consumers can dedupe via INBOX and reconstruct causal chains.")

(defprotocol IntegrationPublisher
  (publish! [this topic event metadata]
    "Publishes `event` on `topic` with the given identity `metadata`.
     MUST throw if the broker did not confirm acceptance - the outbox
     relay catches throwables and leaves the row pending for retry."))
