(ns hotel.event-store.postgres
  "PRODUCTION adapter for the EventStore port (see resources/schema.sql).
   UNIQUE (stream_id, version) -> optimistic concurrency.
   Component lifecycle: the datasource is created on start."
  (:require [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [cheshire.core :as json]
            [hotel.event-store.protocol :as es]))

(defn- row->event [row]
  (-> (:events/payload row) str (json/parse-string true)
      (update :event/type keyword)))

(defrecord PostgresEventStore [jdbc-url datasource]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      (assoc this :datasource (jdbc/get-datasource {:jdbcUrl jdbc-url}))))
  (stop [this] (assoc this :datasource nil))

  es/EventStore
  (read-stream [_ stream-id]
    (mapv row->event
          (jdbc/execute! datasource
            ["select payload from events where stream_id = ? order by version"
             stream-id])))
  (append-events! [_ stream-id expected-version events]
    (jdbc/with-transaction [tx datasource]
      (doseq [[i e] (map-indexed vector events)]
        (jdbc/execute! tx
          ["insert into events (stream_id, version, payload) values (?, ?, ?::jsonb)"
           stream-id (+ expected-version i 1) (json/generate-string e)]))))
  (read-all [_]
    (mapv row->event
          (jdbc/execute! datasource
            ["select payload from events order by global_position"]))))

(defn create [jdbc-url] (map->PostgresEventStore {:jdbc-url jdbc-url}))
