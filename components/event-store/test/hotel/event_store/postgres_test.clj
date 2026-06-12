(ns hotel.event-store.postgres-test
  "The ONLY tests with real I/O on the journal brick. Excluded from the
   fast suite. Run:  clojure -X:dev:test :excludes '[]'

   A Testcontainers-managed Postgres is started once, then verify-contract
   recreates a fresh state for each scenario by truncating the relevant
   tables. The same `contract/verify-contract` shape that the in-memory
   adapter satisfies."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [hotel.event-store.contract :as contract]
            [hotel.event-store.postgres :as pg]
            [hotel.event-store.testcontainers :as tc]))

(def ^:dynamic *pg* nil)

(defn- with-postgres [f]
  (let [c (tc/start-postgres!)]
    (try
      (binding [*pg* c] (f))
      (finally (.stop c)))))

(use-fixtures :once with-postgres)

(defn- truncate-all! [ds]
  (jdbc/execute! ds ["truncate events restart identity cascade"])
  (jdbc/execute! ds ["truncate outbox restart identity cascade"])
  (jdbc/execute! ds ["truncate processed_commands cascade"])
  (jdbc/execute! ds ["truncate inbox cascade"])
  (jdbc/execute! ds ["truncate projection_checkpoints cascade"])
  (jdbc/execute! ds ["truncate room_view cascade"]))

(deftest ^:integration postgres-store-honours-every-port-contract
  (let [url   (tc/jdbc-url *pg*)
        ds    (jdbc/get-datasource {:jdbcUrl url})
        store (component/start (pg/create url))
        fresh (fn [] (truncate-all! ds) store)]
    (contract/verify-contract fresh)
    (component/stop store)
    (is true)))
