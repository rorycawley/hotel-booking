(ns hotel.booking.slices.move-guest.handler
  "Driving port for the 'Move guest' task: starts the process manager.
   :move-id identifies THIS process instance (a web adapter would
   generate a UUID); everything after step 0 is driven by the PM's
   reactor through :on-success/:on-failure continuations."
  (:require [hotel.booking.decider :as decider]
            [hotel.booking.slices.move-guest.process :as process]))

(defn- stream-id [move-id] (str "move-" move-id))

(defn handle
  "Step 0: record that the move was requested. One command, one stream."
  [system {:keys [move-id] :as command}]
  (decider/handle process/decider system (stream-id move-id)
                  (assoc command :command/type :request-move)))

(defn record-step
  "Target of the PM's continuation commands (:record-*)."
  [system {:keys [move-id] :as command}]
  (decider/handle process/decider system (stream-id move-id) command))
