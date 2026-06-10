(ns hotel.clock.deterministic
  "TEST adapter: a clock the test controls completely - settable,
   advanceable, with configurable uncertainty width."
  (:require [hotel.clock.protocol :as clock]))

(defrecord DeterministicClock [state] ; atom {:now ms :uncertainty-ms ms}
  clock/Clock
  (now-interval [_]
    (let [{:keys [now uncertainty-ms]} @state]
      {:earliest now :latest (+ now uncertainty-ms)})))

(defn create
  ([] (create {:now 1750000000000 :uncertainty-ms 0}))
  ([init] (->DeterministicClock (atom init))))

(defn set-time!        [c ms] (swap! (:state c) assoc :now ms))
(defn advance!         [c ms] (swap! (:state c) update :now + ms))
(defn set-uncertainty! [c ms] (swap! (:state c) assoc :uncertainty-ms ms))
