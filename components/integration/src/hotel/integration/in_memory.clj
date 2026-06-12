(ns hotel.integration.in-memory
  "TEST adapter: records published integration events for assertions."
  (:require [hotel.integration.protocol :as pub]))

(defrecord RecordingPublisher [published failing?]
  pub/IntegrationPublisher
  (publish! [_ topic event metadata]
    (when @failing?
      (throw (ex-info "in-memory publisher is in :failing mode"
                      {:topic topic :metadata metadata})))
    (swap! published conj {:topic topic :event event :metadata metadata})))

(defn create
  "Optional opts: {:failing? <atom-bool>} - lets a test simulate a broker
   that is down to verify outbox semantics."
  ([] (create {}))
  ([{:keys [failing?] :or {failing? (atom false)}}]
   (->RecordingPublisher (atom []) failing?)))
