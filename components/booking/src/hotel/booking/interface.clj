(ns hotel.booking.interface
  "DRIVING PORT of the booking capability - its input pins. Every use
   case the capability OFFERS, in one namespace; `poly check` makes it
   the only namespace other bricks may touch.

   Each state-changing use case runs the full slice: idempotency check ->
   decide -> evolve -> stamp -> pure react -> ATOMIC write (events +
   outbox + processed_commands) -> execute post-commit effects."
  (:require [hotel.booking.effects :as effects]
            [hotel.booking.app :as app]
            [hotel.booking.recovery :as recovery]
            [hotel.booking.slices.book-room.handler :as book]
            [hotel.booking.slices.cancel-booking.handler :as cancel]
            [hotel.booking.slices.decommission-room.handler :as decommission]
            [hotel.booking.slices.move-guest.handler :as move]
            [hotel.booking.slices.approve-decommission.handler :as approve-decom]
            [hotel.booking.slices.available-rooms.query :as available]
            [hotel.booking.slices.documents.handler :as documents]
            [hotel.booking.pii :as pii]))

(defn- run-use-case! [handle system command]
  (let [result (handle system command)]
    (if (:error result)
      result
      (if-let [effect-result (effects/execute-effects!
                              system (:other-effects result))]
        (assoc result :effect-errors (:errors effect-result))
        result))))

;; ---- state-changing use cases (one task = one command) ----
(defn book-room!         [system command] (run-use-case! book/handle system command))
(defn cancel-booking!    [system command] (run-use-case! cancel/handle system command))
(defn decommission-room! [system command] (run-use-case! decommission/handle system command))
(defn move-guest!        [system command] (run-use-case! move/handle system command))

;; ---- multi-party approval use cases ----
(defn request-decommission! [system command]
  (run-use-case! approve-decom/request-decommission system command))
(defn approve-decommission! [system command]
  (run-use-case! approve-decom/approve-decommission system command))
(defn reject-decommission!  [system command]
  (run-use-case! approve-decom/reject-decommission system command))

;; ---- queries (read side) ----
(defn available-rooms [system] (available/handle system))

;; ---- supporting documents (upload + retrieve) ----
(def upload-document!   documents/upload!)
(def download-document  documents/download)

;; ---- recovery (PM sweep) ----
(def recover-move-guest-processes! recovery/recover-move-guest-processes!)
(def recover-process-managers!     recovery/recover-process-managers!)

;; ---- GDPR right to erasure ----
(defn erase-by-natural-id!
  "GDPR Art. 17. All historical events stay in the journal (immutable)
   but their PII leaves and any blobs they reference become unreadable
   forever."
  [{:keys [subject-keys]} natural-id]
  (pii/erase-by-natural-id! subject-keys natural-id))

;; ---- wiring the configurator needs (pure values, no adapters) ----
(def reactors app/reactors)
(def command-handlers app/command-handlers)
(def room-view-projection app/room-view-projection)
