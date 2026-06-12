(ns hotel.booking.app
  "Application-internal wiring - still INSIDE the hexagon: which pure
   reactors run on new events, which use case serves each dispatchable
   command, and the room-view projection (the CQRS read model's evolve
   fn + name). Notice no adapter is mentioned anywhere here."
  (:require [hotel.booking.slices.send-confirmation.react :as confirm]
            [hotel.booking.slices.announce-booking.react  :as announce]
            [hotel.booking.slices.move-guest.process      :as move-pm]
            [hotel.booking.slices.approve-decommission.decide :as approve-decom]
            [hotel.booking.slices.approve-decommission.handler :as approve-decom-handler]
            [hotel.booking.slices.book-room.handler       :as book]
            [hotel.booking.slices.cancel-booking.handler  :as cancel]
            [hotel.booking.slices.decommission-room.handler :as decommission]
            [hotel.booking.slices.move-guest.handler      :as move]
            [hotel.booking.slices.available-rooms.projection :as room-view]))

(def reactors [confirm/react announce/react move-pm/react approve-decom/react])

(def command-handlers
  "Commands that processes may dispatch via the :dispatch-command effect."
  {:book-room                       book/handle
   :cancel-booking                  cancel/handle
   :decommission-room               decommission/handle
   :record-new-room-booked          move/record-step
   :record-new-room-failed          move/record-step
   :record-old-room-cancelled       move/record-step
   :record-old-room-failed          move/record-step
   :record-compensated              move/record-step
   :record-decommission-executed    approve-decom-handler/record-step
   :reject-decommission             approve-decom-handler/record-step})

(def room-view-projection
  "Wire shape the configurator hands to the projector COMPONENT.
   The evolve fn is pure and lives in the slice; the projection-name
   is the checkpoint key."
  {:projection-name "rooms"
   :evolve-fn       room-view/evolve})
