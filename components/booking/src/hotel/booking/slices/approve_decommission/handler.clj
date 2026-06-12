(ns hotel.booking.slices.approve-decommission.handler
  (:require [hotel.booking.decider :as decider]
            [hotel.booking.slices.approve-decommission.decide :as core]))

(defn- stream-id [decommission-id] (str "decommission-" decommission-id))

(defn request-decommission [system {:keys [decommission-id] :as command}]
  (decider/handle core/decider system (stream-id decommission-id)
                  (assoc command :command/type :request-decommission)))

(defn approve-decommission [system {:keys [decommission-id] :as command}]
  (decider/handle core/decider system (stream-id decommission-id)
                  (assoc command :command/type :approve-decommission)))

(defn reject-decommission [system {:keys [decommission-id] :as command}]
  (decider/handle core/decider system (stream-id decommission-id)
                  (assoc command :command/type :reject-decommission)))

(defn record-step
  "Target of the PM's continuation commands (:record-decommission-executed,
   :reject-decommission as on-failure)."
  [system {:keys [decommission-id] :as command}]
  (decider/handle core/decider system (stream-id decommission-id) command))
