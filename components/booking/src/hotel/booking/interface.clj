(ns hotel.booking.interface
  "DRIVING PORT of the booking capability - its input pins. Every use
   case the capability OFFERS, in one namespace; `poly check` makes it
   the only namespace other bricks may touch. Each state-changing use
   case runs the full slice: decide+evolve via the handler, then REACT
   to the new events (notifications, integration events, processes)."
  (:require [hotel.booking.effects :as effects]
            [hotel.booking.app :as app]
            [hotel.booking.slices.book-room.handler :as book]
            [hotel.booking.slices.cancel-booking.handler :as cancel]
            [hotel.booking.slices.decommission-room.handler :as decommission]
            [hotel.booking.slices.move-guest.handler :as move]
            [hotel.booking.slices.available-rooms.query :as available]))

(defn- run-use-case! [handle system command]
  (let [result (handle system command)]
    (effects/react-all! system (:events result))
    result))

;; ---- state-changing use cases (one task = one command) ----
(defn book-room!         [system command] (run-use-case! book/handle system command))
(defn cancel-booking!    [system command] (run-use-case! cancel/handle system command))
(defn decommission-room! [system command] (run-use-case! decommission/handle system command))
(defn move-guest!        [system command] (run-use-case! move/handle system command))

;; ---- queries (read side) ----
(defn available-rooms [system] (available/handle system))

;; ---- wiring the configurator needs (pure values, no adapters) ----
(def reactors app/reactors)
(def command-handlers app/command-handlers)
