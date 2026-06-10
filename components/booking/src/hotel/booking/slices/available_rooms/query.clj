(ns hotel.booking.slices.available-rooms.query
  "Impure shell: reads events via the PORT, runs the pure projection."
  (:require [hotel.event-store.interface :as es]
            [hotel.booking.slices.available-rooms.projection :as proj]))

(defn handle [{:keys [event-store all-room-ids]}]
  (proj/available-rooms (es/read-all event-store) all-room-ids))
