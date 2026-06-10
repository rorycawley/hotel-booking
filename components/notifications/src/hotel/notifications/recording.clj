(ns hotel.notifications.recording
  "TEST adapter: records confirmed bookings instead of notifying anyone."
  (:require [hotel.notifications.protocol :as notify]))

(defrecord RecordingGuestNotifications [sent] ; atom of booking maps
  notify/GuestNotifications
  (confirm-booking! [_ booking] (swap! sent conj booking)))

(defn create [] (->RecordingGuestNotifications (atom [])))
