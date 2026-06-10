(ns hotel.notifications.interface
  "DRIVEN PORT in DOMAIN language: confirm a booking for a guest. The
   application dictates the vocabulary; impls translate to technology
   (email today via Twilio SendGrid; SMS tomorrow = new impl, same port)."
  (:require [hotel.notifications.protocol :as protocol]
            [hotel.notifications.recording :as recording]
            [hotel.notifications.twilio :as twilio]))

(def confirm-booking! protocol/confirm-booking!)

(defn recording [] (recording/create))
(defn twilio    [{:keys [api-key from] :as config}] (twilio/create config))
