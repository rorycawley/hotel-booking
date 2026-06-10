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
            [hotel.integration.interface :as pub]
            [hotel.booking.contracts :as contracts]))

(declare react-all!)

(defn- error-result [result]
  (when (and (map? result) (:error result))
    result))

(defn execute! [{:keys [guest-notifications publisher command-handlers] :as system} effect]
  (case (:effect/type effect)
    :notify-booking-confirmed
    (notify/confirm-booking! guest-notifications
                             (select-keys effect [:guest :room-id :check-in :check-out]))

    :publish
    (if (contracts/valid? (:topic effect) (:payload effect))
      (pub/publish! publisher (:topic effect) (:payload effect))
      {:error :invalid-integration-event
       :topic (:topic effect)})

    :dispatch-command
    (let [command (:command effect)
          handle  (get command-handlers (:command/type command))
          result  (handle system command)]
      (if (:error result)
        (if-let [failure (:on-failure effect)]
          (error-result
           (execute! system {:effect/type :dispatch-command
                             :command (assoc failure :reason (:error result))}))
          result)
        (or (error-result (react-all! system (:events result)))
            (when-let [success (:on-success effect)]
              (error-result
               (execute! system {:effect/type :dispatch-command
                                 :command success})))
            result)))))

(defn react-all!
  "For each new event, ask every reactor (pure) what should happen,
   then execute the resulting effects through the ports."
  [{:keys [reactors] :as system} events]
  (let [errors (vec (keep (comp error-result #(execute! system %))
                          (for [event   events
                                reactor reactors
                                effect  (reactor event)]
                            effect)))]
    (when (seq errors)
      {:error :effect-errors
       :errors errors})))
