(ns hotel.booking.slices.move-guest.process
  "PROCESS MANAGER for 'Move guest': a decider with ITS OWN stream
   (move-<id>) and FSM - the same pattern as a room, one level up.
   It remembers where the process is, so it can handle failures,
   compensation and (in a real system) retries and timeouts.
   This is the TOP rung of the machinery ladder (ADR-0002): cross-stream
   coordination is the only thing that earns it. Unlike aggregates
   (where an FSM is a frequent choice, not a rule), a process manager is
   ALWAYS an FSM: its whole job is knowing which protocol step it is in.

   FSM of one move:

   :not-started ──RequestMove──► :booking-new-room
        :booking-new-room ──NewRoomBooked──► :cancelling-old-room
        :booking-new-room ──NewRoomFailed──► :failed            (terminal)
        :cancelling-old-room ──OldRoomCancelled──► :completed   (terminal)
        :cancelling-old-room ──OldRoomFailed──► :compensating
        :compensating ──Compensated (new room re-cancelled)──► :failed (terminal)

   react (pure, below): PM event -> the NEXT domain command, as data,
   with :on-success/:on-failure continuations pointing back at the PM."
  (:require [hotel.booking.room.events :as room-events]))

;; ---------- the 7 elements ----------

(def initial-state {:status :not-started})

(defn evolve [state event]
  (case (:event/type event)
    :move-requested          (merge state
                                    {:status   :booking-new-room
                                     :move-id  (:move-id event)
                                     :from-room (:from-room event)
                                     :to-room   (:to-room event)
                                     :guest     (:guest event)
                                     :check-in  (:check-in event)
                                     :check-out (:check-out event)})
    :move-new-room-booked    (assoc state :status :cancelling-old-room)
    :move-new-room-failed    (assoc state :status :failed :reason (:reason event))
    :move-old-room-cancelled (assoc state :status :completed)
    :move-old-room-failed    (assoc state :status :compensating :reason (:reason event))
    :move-compensated        (assoc state :status :failed)
    state))

(defn terminal? [state] (contains? #{:completed :failed} (:status state)))

(def command-schema
  [:multi {:dispatch :command/type}
   [:request-move
    [:map
     [:command/type [:= :request-move]]
     [:move-id   :string]
     [:from-room :string]
     [:to-room   :string]
     [:guest     room-events/Guest]
     [:check-in  :string]
     [:check-out :string]]]
   [:record-new-room-booked    [:map [:command/type [:= :record-new-room-booked]]    [:move-id :string]]]
   [:record-new-room-failed    [:map [:command/type [:= :record-new-room-failed]]    [:move-id :string] [:reason {:optional true} :keyword]]]
   [:record-old-room-cancelled [:map [:command/type [:= :record-old-room-cancelled]] [:move-id :string]]]
   [:record-old-room-failed    [:map [:command/type [:= :record-old-room-failed]]    [:move-id :string] [:reason {:optional true} :keyword]]]
   [:record-compensated        [:map [:command/type [:= :record-compensated]]        [:move-id :string]]]])

(defn- step
  "Step results are only valid in the state that awaits them."
  [state awaited-status event]
  (if (= awaited-status (:status state))
    {:events [event]}
    {:error :unexpected-step-result}))

(def decider
  {:command-schema command-schema
   :initial-state  initial-state
   :evolve         evolve
   :terminal?      terminal?
   :decide
   (fn [state {:command/keys [type] :as command}]
     (case type
       :request-move
       (if (= :not-started (:status state))
         {:events [{:event/type :move-requested
                    :move-id   (:move-id command)
                    :from-room (:from-room command)
                    :to-room   (:to-room command)
                    :guest     (:guest command)
                    :check-in  (:check-in command)
                    :check-out (:check-out command)}]}
         {:error :move-already-started})

       ;; events denormalize from PM state, so react needs no lookups
       :record-new-room-booked
       (step state :booking-new-room
             {:event/type :move-new-room-booked
              :move-id (:move-id state)
              :from-room (:from-room state)
              :guest (:guest state)})

       :record-new-room-failed
       (step state :booking-new-room
             {:event/type :move-new-room-failed
              :move-id (:move-id state) :reason (:reason command)})

       :record-old-room-cancelled
       (step state :cancelling-old-room
             {:event/type :move-old-room-cancelled :move-id (:move-id state)})

       :record-old-room-failed
       (step state :cancelling-old-room
             {:event/type :move-old-room-failed
              :move-id (:move-id state) :to-room (:to-room state)
              :guest (:guest state)
              :reason (:reason command)})

       :record-compensated
       (step state :compensating
             {:event/type :move-compensated :move-id (:move-id state)})))})

;; ---------- the PM's reactor: PM event -> next domain command ----------

(defn react [event]
  (case (:event/type event)
    ;; step 1: book the NEW room first (a failed step 1 changes nothing)
    :move-requested
    [{:effect/type :dispatch-command
      :command    {:command/type :book-room
                   :room-id   (:to-room event)
                   :guest     (:guest event)
                   :check-in  (:check-in event)
                   :check-out (:check-out event)}
      :on-success {:command/type :record-new-room-booked :move-id (:move-id event)}
      :on-failure {:command/type :record-new-room-failed :move-id (:move-id event)}}]

    ;; step 2: cancel the OLD room
    :move-new-room-booked
    [{:effect/type :dispatch-command
      :command    {:command/type :cancel-booking
                   :room-id (:from-room event)
                   :guest (:guest event)}
      :on-success {:command/type :record-old-room-cancelled :move-id (:move-id event)}
      :on-failure {:command/type :record-old-room-failed    :move-id (:move-id event)}}]

    ;; COMPENSATION: step 2 failed -> undo step 1 (re-cancel the new room).
    ;; Carry :guest so a re-booked to-room (raced by another guest while
    ;; the PM was mid-flight) is refused by the ownership guard rather
    ;; than silently cancelled. If compensation itself fails, a real
    ;; system alerts a human/operator.
    :move-old-room-failed
    [{:effect/type :dispatch-command
      :command    {:command/type :cancel-booking
                   :room-id (:to-room event)
                   :guest   (:guest event)}
      :on-success {:command/type :record-compensated :move-id (:move-id event)}}]

    nil))
