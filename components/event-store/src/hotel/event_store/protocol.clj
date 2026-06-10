(ns hotel.event-store.protocol
  "PORT: event sourcing storage. Adapters: postgres, in-memory.")

(defprotocol EventStore
  (read-stream    [this stream-id]
    "All events for one stream, in order.")
  (append-events! [this stream-id expected-version events]
    "Append with optimistic concurrency: throws if the stream
     no longer has exactly `expected-version` events.")
  (read-all       [this]
    "All events in global order (feeds read models / automations)."))
