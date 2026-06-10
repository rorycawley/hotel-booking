(ns hotel.event-store.in-memory
  "TEST adapter for the EventStore port. An atom, nothing else."
  (:require [hotel.event-store.protocol :as es]))

(defrecord InMemoryEventStore [db] ; db = atom {:streams {id [events]} :all [events]}
  es/EventStore
  (read-stream [_ stream-id]
    (get-in @db [:streams stream-id] []))
  (append-events! [_ stream-id expected-version events]
    (swap! db (fn [s]
                (let [current (get-in s [:streams stream-id] [])]
                  (when (not= (count current) expected-version)
                    (throw (ex-info "Concurrency conflict" {:stream stream-id})))
                  (-> s
                      (update-in [:streams stream-id] (fnil into []) events)
                      (update :all (fnil into []) events))))))
  (read-all [_] (:all @db [])))

(defn create [] (->InMemoryEventStore (atom {})))
