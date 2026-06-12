(ns hotel.ids.interface
  "Port interface for deterministic or random identity generation."
  (:require [hotel.ids.protocol :as protocol]
            [hotel.ids.deterministic :as deterministic]
            [hotel.ids.random :as random]))

(def new-id protocol/new-id)
(def name-id protocol/name-id)

(defn deterministic
  ([] (deterministic/create))
  ([prefix] (deterministic/create prefix)))

(defn random [] (random/create))
