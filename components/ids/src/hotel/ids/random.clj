(ns hotel.ids.random
  (:require [hotel.ids.protocol :as ids])
  (:import [java.util UUID]))

(defrecord RandomIds []
  ids/IdSource
  (new-id [_] (UUID/randomUUID))
  (name-id [_ name]
    (UUID/nameUUIDFromBytes (.getBytes (str name) "UTF-8"))))

(defn create [] (->RandomIds))
