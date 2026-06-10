(ns hotel.integration.in-memory
  "TEST adapter: records published integration events for assertions."
  (:require [hotel.integration.protocol :as pub]))

(defrecord RecordingPublisher [published] ; atom of [{:topic .. :event ..}]
  pub/IntegrationPublisher
  (publish! [_ topic event]
    (swap! published conj {:topic topic :event event})))

(defn create [] (->RecordingPublisher (atom [])))
