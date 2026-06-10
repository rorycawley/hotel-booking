(ns hotel.event-store.postgres-test
  "The ONLY test with real I/O. Excluded from the fast suite.
   Run:  DATABASE_URL=jdbc:postgresql://... clojure -X:test :excludes '[]'"
  (:require [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]
            [hotel.event-store.contract :as contract]
            [hotel.event-store.postgres :as pg]))

(deftest ^:integration postgres-store-honours-the-event-store-contract
  (if-let [url (System/getenv "DATABASE_URL")]
    (let [ds (jdbc/get-datasource {:jdbcUrl url})
          fresh-store (fn []
                        (jdbc/execute! ds ["truncate table events"])
                        (pg/create url))]
      (contract/verify-contract fresh-store))
    (is true "DATABASE_URL not set - skipping Postgres contract test")))
