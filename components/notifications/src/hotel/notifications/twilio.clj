(ns hotel.notifications.twilio
  "PRODUCTION adapter: Twilio SendGrid v3 Mail Send API.
   NOTE where the copywriting lives: HERE, outside the application.
   The domain says 'booking confirmed for this guest'; turning that into
   a subject line and prose is presentation - an adapter concern."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [hotel.notifications.protocol :as notify]))

(defrecord TwilioSendGridNotifications [api-key from]
  notify/GuestNotifications
  (confirm-booking! [_ {:keys [guest room-id check-in check-out]}]
    (http/post "https://api.sendgrid.com/v3/mail/send"
      {:headers      {"Authorization" (str "Bearer " api-key)}
       :content-type :json
       :body (json/generate-string
              {:personalizations [{:to [{:email (:email guest)}]}]
               :from    {:email from}
               :subject "Booking confirmed"
               :content [{:type "text/plain"
                          :value (str "Dear " (:name guest) ", room " room-id
                                      " is yours from " check-in
                                      " to " check-out ".")}]})})))

(defn create [{:keys [api-key from]}]
  (->TwilioSendGridNotifications api-key from))
