(ns hotel.event-store.pool-exhaustion-test
  "When the HikariCP pool is saturated, the next caller MUST fail with
   a SQLException within the configured connection-timeout - never block
   past the deadline, never silently return nil.

   We configure a SHORT connection-timeout (1 second) on the adapter
   and hold the only connection for longer than that. The assertion is
   sharper than the earlier 'either succeeds or throws within 30 s'
   version: it pins the exact production guarantee that misconfigured
   load cannot silently hang request threads."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [hotel.event-store.interface :as es]
            [hotel.event-store.postgres :as pg]
            [hotel.event-store.testcontainers :as tc])
  (:import [java.sql SQLException]
           [org.testcontainers.containers PostgreSQLContainer]))

(def ^:dynamic *pg* nil)

(defn- with-postgres [f]
  (let [c (tc/start-postgres!)]
    (try (binding [*pg* c] (f))
         (finally (.stop c)))))

(use-fixtures :once with-postgres)

(deftest ^:integration pool-exhaustion-throws-sqlexception-within-configured-timeout
  (let [url        (tc/jdbc-url ^PostgreSQLContainer *pg*)
        timeout-ms 1000                                  ; short, deterministic
        hold-ms    3000                                  ; longer than timeout
        ;; pool-size = 1, connection-timeout = 1 s
        store      (component/start (pg/create url 1 timeout-ms))
        ds         (:datasource store)
        ready      (promise)
        holder     (future
                     (with-open [conn (jdbc/get-connection ds)]
                       (deliver ready :have-conn)
                       ;; keep the only connection past the timeout window
                       (Thread/sleep hold-ms)
                       (jdbc/execute! conn ["select 1"])))]
    (try
      @ready                                            ; pool is now empty
      (let [start-ms (System/currentTimeMillis)
            thrown   (try (es/outbox-depth store) nil
                          (catch SQLException e e))
            elapsed  (- (System/currentTimeMillis) start-ms)]
        (is (instance? SQLException thrown)
            "pool exhaustion throws SQLException - never returns nil, never hangs")
        ;; The bound: roughly the configured timeout, NOT the 30 s
        ;; HikariCP default. Slack of ±500 ms keeps the test stable
        ;; under noisy CI schedulers without weakening the claim.
        (is (<= (- timeout-ms 500) elapsed (+ timeout-ms 500))
            (str "elapsed " elapsed " ms not within "
                 (- timeout-ms 500) "-" (+ timeout-ms 500)
                 " ms of configured " timeout-ms)))
      (finally
        @holder
        (component/stop store)))))
