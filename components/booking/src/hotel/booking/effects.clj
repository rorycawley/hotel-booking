(ns hotel.booking.effects
  "react (pure) returns EFFECT DESCRIPTIONS as data. This is the single
   interpreter that turns them into port calls or - for PROCESSES - into
   the NEXT command of the process. Reactors never touch I/O.

   :dispatch-command may carry continuation COMMANDS (plain data):
     :on-success - dispatched after the command succeeds
     :on-failure - dispatched (with :reason) after it fails
   Process managers use these to record each step's outcome on their
   own stream, which is what makes compensation possible."
  (:require [hotel.notifications.interface :as notify]
            [hotel.integration.interface :as pub]))

(declare react-all!)

(defn execute! [{:keys [guest-notifications publisher command-handlers] :as system} effect]
  (case (:effect/type effect)
    :notify-booking-confirmed
    (notify/confirm-booking! guest-notifications
                             (select-keys effect [:guest :room-id :check-in :check-out]))

    :publish
    (pub/publish! publisher (:topic effect) (:payload effect))

    :dispatch-command
    (let [command (:command effect)
          handle  (get command-handlers (:command/type command))
          result  (handle system command)]
      (if (:error result)
        (when-let [failure (:on-failure effect)]
          (execute! system {:effect/type :dispatch-command
                            :command (assoc failure :reason (:error result))}))
        (do (react-all! system (:events result))
            (when-let [success (:on-success effect)]
              (execute! system {:effect/type :dispatch-command
                                :command success}))))
      result)))

(defn react-all!
  "For each new event, ask every reactor (pure) what should happen,
   then execute the resulting effects through the ports."
  [{:keys [reactors] :as system} events]
  (doseq [event   events
          reactor reactors
          effect  (reactor event)]
    (execute! system effect)))
