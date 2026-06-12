(ns hotel.outbox-relay.lifecycle-test
  "Behaviour of the relay COMPONENT under start/stop. The `auto-start?`
   switch is what lets the in-memory system wire the same component the
   prod system uses, without leaking a worker thread into tests."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [hotel.event-store.interface :as es]
            [hotel.integration.interface :as pub]
            [hotel.outbox-relay.interface :as relay]))

(defn- system [auto?]
  (component/system-map
   :event-store (es/in-memory)
   :publisher   (pub/in-memory)
   :outbox-relay (component/using
                  (relay/create {:auto-start? auto?})
                  [:event-store :publisher])))

(deftest auto-start-false-leaves-the-worker-thread-unstarted
  (let [sys (component/start (system false))]
    (try
      (is (nil? (:thread (:outbox-relay sys)))
          "no background thread - tests drive it via drain-once!")
      (finally (component/stop sys)))))

(deftest stop-after-auto-start-true-shuts-down-cleanly
  (let [sys (component/start (system true))
        t   (:thread (:outbox-relay sys))]
    (is (some? t) "worker thread is running")
    (component/stop sys)
    ;; the run-loop checks @running? each iteration with a small poll;
    ;; give it a beat to exit so the assertion isn't racy
    (.join ^Thread t 1000)
    (is (not (.isAlive ^Thread t))
        "thread exits when the component is stopped")))

(deftest stop-is-safe-on-an-unstarted-relay
  (let [sys (system false)]
    (is (some? (component/stop sys))
        "stopping a never-started system must not throw")))
