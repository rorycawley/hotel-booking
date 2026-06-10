(ns hotel.booking.slices.decommission-room.handler
  (:require [hotel.booking.decider :as decider]
            [hotel.booking.slices.decommission-room.decide :as core]))

(defn handle [system {:keys [room-id] :as command}]
  (decider/handle core/decider system (str "room-" room-id) command))
