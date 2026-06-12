(ns hotel.outbox-relay.interface
  "The outbox-relay brick has no driven port - it's a wiring concern
   (a Component) that hooks the EventStore.Outbox to the IntegrationPublisher.
   Exposed: a constructor, plus `drain-once!` for tests and ops."
  (:require [hotel.outbox-relay.worker :as worker]))

(defn create
  "config = {:event-store .. :publisher .. :batch-size .. :poll-ms .. :backoff-ms ..}.
   :event-store and :publisher get injected by the configurator (Component)."
  [config]
  (worker/create config))

(def drain-once! worker/drain-once!)
