(ns hotel.booking.contracts
  "The PUBLISHED LANGUAGE: Malli schemas for every integration event this
   context emits. These are CONTRACTS with other systems, so the rules
   differ from domain events (room/events.clj):
     - {:closed true}: nothing internal can leak, even by accident
     - explicit :version, evolved additively (v2 is a NEW schema)
     - designed for consumers, in consumer-neutral vocabulary
     - no PII, no internal keywords, no domain structure
   Domain events may change freely; these may not."
  (:require [malli.core :as m]))

(def room-booked-v1
  [:map {:closed true}
   [:type     [:= "RoomBooked"]]
   [:version  [:= 1]]
   [:room-id  :string]
   [:check-in :string]])

(def by-topic
  {"booking.room-booked" room-booked-v1})

(defn valid?
  "Is this payload a legal message on this topic?"
  [topic payload]
  (boolean (some-> (by-topic topic) (m/validate payload))))
