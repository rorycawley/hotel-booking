(ns hotel.event-store.contract
  "One set of behaviours that every EventStore adapter must honour.
   Run in-memory (fast suite) and against Postgres (integration) -
   if both pass, swapping adapters cannot change behaviour."
  (:require [clojure.test :refer [is testing]]
            [hotel.event-store.interface :as es]))

(def e1 {:event/type :room-booked       :room-id "1"})
(def e2 {:event/type :booking-cancelled :room-id "1"})
(def e3 {:event/type :room-booked       :room-id "2"})

(defn verify-contract
  "fresh-store: 0-arg fn returning an EMPTY store."
  [fresh-store]
  (testing "a stream that was never written to is empty"
    (is (= [] (es/read-stream (fresh-store) "room-1"))))

  (testing "appended events come back, in order, with their data intact"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [e1])
      (es/append-events! store "room-1" 1 [e2])
      (is (= [e1 e2] (es/read-stream store "room-1")))))

  (testing "streams are isolated from each other"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [e1])
      (es/append-events! store "room-2" 0 [e3])
      (is (= [e1] (es/read-stream store "room-1")))))

  (testing "read-all returns every event in global append order"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [e1])
      (es/append-events! store "room-2" 0 [e3])
      (es/append-events! store "room-1" 1 [e2])
      (is (= [e1 e3 e2] (es/read-all store)))))

  (testing "a stale expected-version is rejected (optimistic concurrency)"
    (let [store (fresh-store)]
      (es/append-events! store "room-1" 0 [e1])
      (is (thrown? Exception
                   (es/append-events! store "room-1" 0 [e1]))))))
