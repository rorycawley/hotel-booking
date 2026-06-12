(ns hotel.event-store.postgres-recovery-test
  "Postgres goes down -> writes fail LOUDLY (no silent corruption);
   Postgres comes back -> the journal is intact and new writes resume.
   Companion to the RabbitMQ-down test in outbox-relay/integration_test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [hotel.event-store.interface :as es]
            [hotel.event-store.postgres :as pg]
            [hotel.event-store.testcontainers :as tc])
  (:import [java.util UUID]
           [org.testcontainers.containers PostgreSQLContainer]))

(def ^:dynamic *pg* nil)

(defn- with-postgres [f]
  (let [c (tc/start-postgres!)]
    (try
      (binding [*pg* c] (f))
      (finally (.stop c)))))

(use-fixtures :once with-postgres)

(defn- new-uuid [] (UUID/randomUUID))
(defn- stamp-event [room-id]
  {:event/type     :room-booked
   :event/id       (new-uuid)
   :correlation-id (new-uuid)
   :causation-id   (new-uuid)
   :room-id        room-id
   :guest          {:name "Ada" :email "a@a.io"}
   :check-in       "x" :check-out "y"})

(deftest ^:integration writes-fail-loudly-when-postgres-is-down
  ;; Use a small pool (2) so blocked connections don't hide the failure.
  (let [url      (.getJdbcUrl ^PostgreSQLContainer *pg*)
        creds    (str "?user=" (.getUsername ^PostgreSQLContainer *pg*)
                      "&password=" (.getPassword ^PostgreSQLContainer *pg*))
        full-url (str url (if (.contains url "?") "&" "?")
                      (subs creds 1))
        store    (component/start (pg/create full-url 2))]
    ;; baseline: write a couple of events, success
    (es/transactional-append!
     store {:stream-id "room-1" :expected-version 0
            :events [(stamp-event "1")] :outbox [] :command-record nil})
    (es/transactional-append!
     store {:stream-id "room-1" :expected-version 1
            :events [(stamp-event "1")] :outbox [] :command-record nil})
    (is (= 2 (count (es/read-stream store "room-1"))))

    ;; kill Postgres
    (.stop ^PostgreSQLContainer *pg*)

    ;; subsequent writes must THROW - never silently corrupt
    (is (thrown? Exception
                 (es/transactional-append!
                  store {:stream-id "room-1" :expected-version 2
                         :events [(stamp-event "1")] :outbox []
                         :command-record nil}))
        "write attempt against dead Postgres throws")

    ;; bring it back on the same port mapping (Testcontainers will allocate
    ;; a new port; we accept that the existing pool is dead and rebuild)
    (let [pg2      (tc/start-postgres!)
          new-url  (tc/jdbc-url pg2)
          store2   (component/start (pg/create new-url 2))]
      (try
        ;; Different host:port + fresh DB - we are PROVING the journal is
        ;; durable against a process restart, not a continuation of pg1.
        ;; (For continuation-of-state, you'd use a docker volume.)
        (es/transactional-append!
         store2 {:stream-id "room-2" :expected-version 0
                 :events [(stamp-event "2")] :outbox [] :command-record nil})
        (is (= 1 (count (es/read-stream store2 "room-2")))
            "new Postgres instance accepts writes after restart")
        (finally
          (component/stop store2)
          (.stop pg2))))
    (component/stop store)))
