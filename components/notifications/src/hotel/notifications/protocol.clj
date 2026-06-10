(ns hotel.notifications.protocol
  "DRIVEN PORT in DOMAIN language: the application announces that a
   booking was confirmed; it does not know about emails, subjects or
   message bodies. The application defines this interface and dictates
   its vocabulary - adapters must translate bookings into whatever
   technology they speak (email today, SMS tomorrow: adapter change only).
   Adapters: twilio-sendgrid (prod), recording (test).")

(defprotocol GuestNotifications
  (confirm-booking! [this {:keys [guest room-id check-in check-out]}]))
