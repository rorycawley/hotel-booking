(ns hotel.booking.room.events
  "Malli schemas for every event the room stream can contain.
   One :multi schema dispatching on :event/type."
  (:require [malli.core :as m]))

(def Interval
  "UTC uncertainty bound from the Clock port: true time is within
   [earliest, latest]. Added by the shell (decider/handle), so it is
   optional on events produced by the pure core."
  [:map [:earliest :int] [:latest :int]])

(def Guest
  [:map
   [:name  :string]
   [:email [:re #"^[^@\s]+@[^@\s]+$"]]])

(def event-schema
  [:multi {:dispatch :event/type}
   [:room-booked
    [:map
     [:event/type [:= :room-booked]]
     [:room-id    :string]
     [:guest      Guest]
     [:check-in   :string]
     [:check-out  :string]
     [:supporting-document-ids {:optional true} [:vector :string]]
     [:recorded-at {:optional true} Interval]]]
   [:booking-cancelled
    [:map
     [:event/type [:= :booking-cancelled]]
     [:room-id    :string]
     [:recorded-at {:optional true} Interval]]]
   [:room-decommissioned
    [:map
     [:event/type [:= :room-decommissioned]]
     [:room-id    :string]
     [:recorded-at {:optional true} Interval]]]])

(defn valid? [event] (m/validate event-schema event))
