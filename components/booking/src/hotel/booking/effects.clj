(ns hotel.booking.effects
  "Reactors return EFFECT DESCRIPTIONS as data. This namespace owns:
   - the PURE step that runs reactors and partitions output into
     :publish (transactional - written to the outbox in the same TX as
     the events) and :other (post-commit - notify, dispatch-command)
   - the impure interpreter for the post-commit effects.

   Only this namespace and `decider/handle` touch ports.

   Post-commit failures (notify down, dispatch-command rejected) are
   non-fatal but MUST be observable - we record them through the
   ProblemSink port so ops can see and act on them. Without the sink
   call these failures would only appear in the return value and could
   be swallowed at the HTTP layer.

   :dispatch-command may carry continuation COMMANDS (plain data):
     :on-success - dispatched after the command succeeds
     :on-failure - dispatched (with :reason) after it fails
   Process managers use these to record each step's outcome on their own
   stream, which is what makes compensation possible."
  (:require [hotel.notifications.interface :as notify]
            [hotel.problems.interface :as problems]
            [hotel.booking.contracts :as contracts]))

(declare execute-effects!)

(defn- error-result [result]
  (when (and (map? result) (:error result))
    result))

(defn- attach-source-event [effect event]
  (assoc effect :source-event event))

(defn- run-reactors
  "PURE. Each reactor sees each event; effects are tagged with the source
   event so the shell can thread identity (correlation / causation) when
   building outbox messages or dispatching continuation commands."
  [reactors events]
  (vec
   (for [event   events
         reactor reactors
         effect  (reactor event)]
     (attach-source-event effect event))))

(defn partition-reactor-output
  "PURE. Run every reactor over the just-stamped events and split the
   resulting effects into the two enterprise-pattern buckets:
     :publish  - go to the OUTBOX in the same TX as the events
     :other    - executed AFTER the TX commits (notify, dispatch-command).

   Publish effects are also validated against the contracts schema right
   here, in the pure step, so a bad contract NEVER ends up in the outbox.
   Unknown topics / invalid payloads fail loudly at this boundary."
  [{:keys [reactors]} stamped-events]
  (let [all-effects (run-reactors reactors stamped-events)
        publish?    #(= :publish (:effect/type %))
        valid-publish?
        (fn [e]
          (and (contracts/known-topic? (:topic e))
               (contracts/valid? (:topic e) (:payload e))))]
    {:publish (vec (filter #(and (publish? %) (valid-publish? %)) all-effects))
     :other   (vec (remove publish? all-effects))
     :invalid (vec (filter #(and (publish? %) (not (valid-publish? %))) all-effects))}))

(defn report-invalid-publishes!
  "When the contract gate rejects a publish (unknown topic / invalid
   payload), record it - it's a PROGRAMMER ERROR, never a runtime
   condition, and silently dropping would let it linger forever."
  [{:keys [problem-sink]} invalid]
  (when problem-sink
    (doseq [eff invalid]
      (problems/record-problem!
       problem-sink
       {:problem        :invalid-publish-effect-blocked-at-contract
        :topic          (:topic eff)
        :payload        (:payload eff)
        :correlation-id (-> eff :source-event :correlation-id)}))))

(defn- derive-command-id
  "DETERMINISTIC command-id for a dispatched command: a v3 (name-based)
   UUID from (source-event-id, command-type). The whole point is that
   a sweeper re-running react on the same source event produces the
   SAME command-id, so the idempotency cache short-circuits the second
   run. Production-grade PM recovery depends on this. Assumes each
   source event dispatches at most one command per type - true for our
   move-guest PM."
  [source-event command]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str (:event/id source-event) "/" (name (:command/type command)))
              "UTF-8")))

(defn- enrich-dispatched-command
  "When a process-manager event dispatches another command, propagate
   correlation-id (same workflow), actor-id (audit-by-author through
   the whole saga), and set causation-id to the source event's id.
   The command-id is DETERMINISTIC so sweeper re-fires are idempotent."
  [command source-event]
  (cond-> command
    (not (:correlation-id command))
    (assoc :correlation-id (:correlation-id source-event))
    (not (:causation-id command))
    (assoc :causation-id (:event/id source-event))
    (not (:actor-id command))
    (assoc :actor-id (:actor-id source-event))
    (not (:command/id command))
    (assoc :command/id (derive-command-id source-event command))))

(defn execute!
  "Impure interpreter for ONE post-commit effect. Public for tests; the
   normal path is `execute-effects!` (a batch over the decider's
   :other-effects). :publish is intentionally NOT a case here - it is
   handled transactionally via the outbox at append time."
  [{:keys [guest-notifications command-handlers] :as system} effect]
  (case (:effect/type effect)
    :notify-booking-confirmed
    (notify/confirm-booking! guest-notifications
                             (select-keys effect [:guest :room-id :check-in :check-out]))

    :dispatch-command
    (let [source-event (:source-event effect)
          command      (enrich-dispatched-command (:command effect) source-event)
          handle       (get command-handlers (:command/type command))
          result       (handle system command)]
      (if (:error result)
        (if-let [failure (:on-failure effect)]
          (error-result
           (execute! system
                     {:effect/type  :dispatch-command
                      :source-event source-event
                      :command      (assoc failure :reason (:error result))}))
          result)
        ;; Command succeeded: drain its OTHER effects (publish already went
        ;; to outbox), then advance the process via :on-success. Carry the
        ;; source-event so the continuation also gets correlation/causation.
        (let [other-err   (error-result
                           (execute-effects! system (:other-effects result)))
              success-err (when-let [success (:on-success effect)]
                            (error-result
                             (execute! system
                                       {:effect/type  :dispatch-command
                                        :source-event source-event
                                        :command      success})))]
          (or success-err other-err result))))))

(defn execute-effects!
  "Impure. Run every post-commit effect; collect errors. Each failure is
   ALSO recorded to the ProblemSink so it cannot vanish silently.
   Returns nil on full success, {:error :effect-errors :errors [..]} otherwise."
  [{:keys [problem-sink] :as system} effects]
  (let [errors (vec (keep (comp error-result #(execute! system %)) effects))]
    (doseq [err errors :when problem-sink]
      (problems/record-problem! problem-sink
                                {:problem :post-commit-effect-failed
                                 :error   err}))
    (when (seq errors)
      {:error :effect-errors
       :errors errors})))
