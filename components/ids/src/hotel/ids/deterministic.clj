(ns hotel.ids.deterministic
  (:require [hotel.ids.protocol :as ids])
  (:import [java.util UUID]))

(defrecord DeterministicIds [state prefix]
  ids/IdSource
  (new-id [_]
    (let [n (swap! state inc)]
      (UUID/nameUUIDFromBytes (.getBytes (str prefix "/" n) "UTF-8"))))
  (name-id [_ name]
    (UUID/nameUUIDFromBytes (.getBytes (str prefix "/" name) "UTF-8"))))

(defn create
  ([] (create "hotel-test-id"))
  ([prefix] (->DeterministicIds (atom 0) prefix)))
