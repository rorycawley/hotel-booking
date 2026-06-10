(ns hotel.rest-api.task-forms
  "TASK-BASED UI fragments: each screen IS one user task posting ONE
   command - never a CRUD 'edit booking record' grid. Lives in the
   driving adapter: HTML is technology, outside the application."
  (:require [hiccup2.core :as h]))

(defn book-room-form [room-id]
  (str
   (h/html
    [:form {:method "post" :action (str "/rooms/" room-id "/book")}
     [:h1 "Book room " room-id]
     [:label "Guest name " [:input {:name "guest-name"}]]
     [:label "Email "      [:input {:name "guest-email" :type "email"}]]
     [:label "Check-in "   [:input {:name "check-in"  :type "date"}]]
     [:label "Check-out "  [:input {:name "check-out" :type "date"}]]
     [:button "Book this room"]])))

(defn cancel-booking-button [room-id]
  (str
   (h/html
    [:form {:method "post" :action (str "/rooms/" room-id "/cancel")}
     [:button "Cancel this booking"]])))
