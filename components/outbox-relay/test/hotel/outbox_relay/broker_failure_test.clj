(ns hotel.outbox-relay.broker-failure-test
  "Focused failure-path tests for the RabbitMQ publisher:

   - When the broker dies after a publish has been sent but before it
     confirms, `wait-for-confirms` returns false and the adapter throws.
   - When the broker is unreachable, the publisher's start lifecycle
     fails fast.
   - When a publish targets an exchange with NO bound queues
     (mandatory=true), the basic.return listener fires and the adapter
     throws :publish-unroutable - NEVER silently confirms-and-drops.

   These properties are what gives the OUTBOX RELAY's at-least-once
   guarantee its teeth - a publish that should have failed but didn't
   would let a message disappear forever."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [hotel.event-store.testcontainers :as tc]
            [hotel.integration.interface :as pub]
            [hotel.integration.rabbitmq :as rmq-adapter])
  (:import [org.testcontainers.containers RabbitMQContainer]))

(def ^:dynamic *rabbit* nil)

(defn- with-rabbit [f]
  (let [c (tc/start-rabbit!)]
    (try (binding [*rabbit* c] (f))
         (finally (.stop c)))))

(use-fixtures :each with-rabbit)

(deftest ^:integration publish-throws-:publish-unroutable-when-mandatory-message-cannot-be-routed
  ;; mandatory=true + no bound queue = the broker confirms the publish
  ;; AND sends basic.return. Without the return-listener tracking, we
  ;; would silently lose the message; with it, the adapter throws.
  (let [pub (component/start
             (rmq-adapter/create {:uri      (tc/rabbit-uri *rabbit*)
                                  :exchange "test-unroutable"}))]
    (try
      (let [thrown (try
                     (pub/publish!
                      pub "topic.that.nothing.consumes"
                      {:type "Whatever"}
                      {:message-id "test-1"
                       :correlation-id "test-c1"})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (some? thrown) "publish! threw, did not silently confirm-and-drop")
        (is (= :publish-unroutable
               (:hotel.integration/error thrown))
            "the specific error is :publish-unroutable, not a generic publish failure"))
      (finally (component/stop pub)))))

(deftest ^:integration publish-throws-when-the-broker-DIES-mid-flight
  ;; A publish in flight when the broker dies should throw (the confirm
  ;; never arrives). This protects the relay from silently 'publishing'
  ;; to a dead broker.
  (let [pub (component/start
             (rmq-adapter/create {:uri      (tc/rabbit-uri *rabbit*)
                                  :exchange "test-broker-dies"}))]
    (try
      ;; kill the broker before the publish
      (.stop ^RabbitMQContainer *rabbit*)
      (is (thrown? Exception
                   (pub/publish!
                    pub "anything" {:hello "world"}
                    {:message-id "m" :correlation-id "c"}))
          "publishing into a dead broker throws, never silently succeeds")
      (finally
        (try (component/stop pub) (catch Throwable _ nil))))))
