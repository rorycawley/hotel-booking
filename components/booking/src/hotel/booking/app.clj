(ns hotel.booking.app
  "Application-internal wiring - still INSIDE the hexagon: which pure
   reactors run on new events, and which use case serves each
   dispatchable command. Notice no adapter is mentioned anywhere here."
  (:require [hotel.booking.slices.send-confirmation.react :as confirm]
            [hotel.booking.slices.announce-booking.react  :as announce]
            [hotel.booking.slices.move-guest.process      :as move-pm]
            [hotel.booking.slices.book-room.handler       :as book]
            [hotel.booking.slices.cancel-booking.handler  :as cancel]
            [hotel.booking.slices.move-guest.handler      :as move]))

(def reactors [confirm/react announce/react move-pm/react])

(def command-handlers
  "Commands that processes may dispatch via the :dispatch-command effect."
  {:book-room                  book/handle
   :cancel-booking             cancel/handle
   :record-new-room-booked     move/record-step
   :record-new-room-failed     move/record-step
   :record-old-room-cancelled  move/record-step
   :record-old-room-failed     move/record-step
   :record-compensated         move/record-step})
