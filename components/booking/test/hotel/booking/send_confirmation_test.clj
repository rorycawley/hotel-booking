(ns hotel.booking.send-confirmation-test
  "Behaviour: 'When a room is booked, the guest is notified.' The
   reactor speaks DOMAIN language - the test proves no presentation
   (subjects, bodies, addresses-as-plumbing) leaks out of the core."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.slices.send-confirmation.react :as confirm]))

(def room-booked
  {:event/type :room-booked :room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest a-booking-produces-exactly-one-domain-notification
  (let [[effect :as effects] (confirm/react room-booked)]
    (is (= 1 (count effects)))
    (is (= {:effect/type :notify-booking-confirmed
            :guest {:name "Ada" :email "ada@example.com"}
            :room-id "101"
            :check-in "2026-07-01" :check-out "2026-07-03"}
           effect))
    (is (nil? (:subject effect)) "copywriting belongs to the adapter")
    (is (nil? (:body effect)))))

(deftest other-events-produce-no-notification
  (is (empty? (confirm/react {:event/type :booking-cancelled :room-id "101"}))))
