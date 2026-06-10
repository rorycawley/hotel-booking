(ns hotel.event-store.postgres
  "PRODUCTION adapter for the EventStore port (see resources/schema.sql).
   UNIQUE (stream_id, version) -> optimistic concurrency.
   Component lifecycle: the datasource is created on start."
  (:require [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [cheshire.core :as json]
            [hotel.event-store.protocol :as es])
  (:import [java.sql SQLException]))

(defn- row->event [row]
  (-> (:events/payload row) str (json/parse-string true)
      (update :event/type keyword)))

(defn- conflict! [stream-id expected-version actual-version]
  (throw (ex-info "Concurrency conflict"
                  {:hotel.event-store/error :concurrency-conflict
                   :stream stream-id
                   :expected-version expected-version
                   :actual-version actual-version})))

(defn- unique-violation? [^SQLException e]
  (= "23505" (.getSQLState e)))

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
    (try
      (jdbc/with-transaction [tx datasource]
        (let [actual-version (:version (first (jdbc/execute! tx
                                                             ["select count(*) as version from events where stream_id = ?"
                                                              stream-id])))]
          (when (not= actual-version expected-version)
            (conflict! stream-id expected-version actual-version))
          (doseq [[i e] (map-indexed vector events)]
            (jdbc/execute! tx
                           ["insert into events (stream_id, version, payload) values (?, ?, ?::jsonb)"
                            stream-id (+ expected-version i 1) (json/generate-string e)]))))
      (catch SQLException e
        (if (unique-violation? e)
          (conflict! stream-id expected-version nil)
          (throw e)))))
  (read-all [_]
    (mapv row->event
          (jdbc/execute! datasource
                         ["select payload from events order by global_position"]))))

(defn create [jdbc-url] (map->PostgresEventStore {:jdbc-url jdbc-url}))
