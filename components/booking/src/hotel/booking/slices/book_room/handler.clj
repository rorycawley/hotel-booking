(ns hotel.booking.slices.book-room.handler
  "Thin shell: one line, the generic decider runner does the rest."
  (:require [hotel.booking.decider :as decider]
            [hotel.booking.slices.book-room.decide :as core]))

(defn handle [system {:keys [room-id] :as command}]
  (decider/handle core/decider system (str "room-" room-id)
                  (assoc command :command/type :book-room)))
