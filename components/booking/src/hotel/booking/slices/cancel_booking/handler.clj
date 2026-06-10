(ns hotel.booking.slices.cancel-booking.handler
  (:require [hotel.booking.decider :as decider]
            [hotel.booking.slices.cancel-booking.decide :as core]))

(defn handle [system {:keys [room-id] :as command}]
  (decider/handle core/decider system (str "room-" room-id) command))
