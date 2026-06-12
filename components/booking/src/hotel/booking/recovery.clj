(ns hotel.booking.recovery
  "PROCESS-MANAGER RECOVERY. A process manager is a state machine spread
   across two streams: its own (move-<id>) and the room streams it
   touches. If the JVM dies between an inner command's commit and the
   PM's continuation, the PM stalls in a non-terminal state - that's the
   gap we close here.

   How recovery works (idempotency makes it safe to fire-and-forget):

     1. Enumerate stream-ids matching the PM's prefix (move-*).
     2. For each: read history; build the PM's current state.
     3. Skip if terminal OR the last event is recent (still in flight).
     4. Re-run the PM's pure reactor on the LAST event.
     5. Execute the resulting effects.

   Because dispatched commands now derive their :command/id
   deterministically from (source-event-id, command-type), every re-fire
   is idempotent: previously-completed steps return the cached result,
   and the chain continues from wherever it stopped."
  (:require [hotel.event-store.interface :as es]
            [hotel.clock.interface :as clock]
            [hotel.booking.decider :as decider]
            [hotel.booking.effects :as effects]
            [hotel.booking.pii :as pii]
            [hotel.booking.upcasters :as upcasters]
            [hotel.booking.slices.approve-decommission.decide :as approve-decom]
            [hotel.booking.slices.move-guest.process :as move-pm]))

(def ^:private move-prefix "move-")
(def ^:private decommission-prefix "decommission-")

(defn- latest-of [event]
  (or (some-> event :recorded-at :latest) 0))

(defn- stuck?
  [{:keys [decider]} history min-age-ms now-latest]
  (let [state (decider/current-state decider history)]
    (and (seq history)
         (not ((:terminal? decider) state))
         (>= (- now-latest (latest-of (last history))) min-age-ms))))

(defn- effects-for-last [react-fn history]
  (when-let [last-event (last history)]
    (mapv #(assoc % :source-event last-event)
          (react-fn last-event))))

(defn- hydrated-history [{:keys [event-store subject-keys]} stream-id]
  (->> (es/read-stream event-store stream-id)
       upcasters/upcast-all
       (pii/decrypt-events subject-keys)))

(defn- recover-one!
  "Re-fire the PM reactor on the LAST event of the stream. The reactor
   is pure; the effects flow back through the same execute-effects! the
   shell uses, so the recovery path and the normal path share
   ALL semantics (validation, idempotency, ProblemSink, etc.)."
  [system {:keys [react-fn]} stream-id]
  (let [history (hydrated-history system stream-id)
        effects (effects-for-last react-fn history)]
    (when (seq effects)
      (effects/execute-effects! system effects))))

(defn recover-stuck-processes!
  "Sweep one PM type. `pm` is {:decider :react-fn :prefix}; `min-age-ms`
   is how stale the last event must be before we treat the PM as stuck
   (so an in-flight step isn't pre-empted). Returns a vector of stream-ids
   that were swept."
  [{:keys [event-store clock] :as system}
   {:keys [decider react-fn prefix] :as pm}
   min-age-ms]
  (let [now-latest (:latest (clock/now-interval clock))
        candidates (es/stream-ids-with-prefix event-store prefix)]
    (vec
     (for [stream-id candidates
           :let [history (hydrated-history system stream-id)
                 runnable? (seq (effects-for-last react-fn history))]
           :when (and runnable?
                      (stuck? pm history min-age-ms now-latest))]
       (do (recover-one! system pm stream-id)
           stream-id)))))

(def move-guest-pm
  "Recovery spec for the move-guest PM."
  {:decider  move-pm/decider
   :react-fn move-pm/react
   :prefix   move-prefix})

(def decommission-approval-pm
  "Recovery spec for the multi-party decommission approval PM. Only
   streams whose last event has reactor work are swept, so workflows
   waiting for human approvals are not treated as stuck."
  {:decider  approve-decom/decider
   :react-fn approve-decom/react
   :prefix   decommission-prefix})

(defn recover-move-guest-processes!
  "Sweep stuck move-guest processes. Convenience wrapper."
  ([system]              (recover-move-guest-processes! system 0))
  ([system min-age-ms]   (recover-stuck-processes! system move-guest-pm min-age-ms)))

(defn recover-decommission-approval-processes!
  "Sweep approved decommission workflows whose downstream command did not run."
  ([system]            (recover-decommission-approval-processes! system 0))
  ([system min-age-ms] (recover-stuck-processes! system decommission-approval-pm min-age-ms)))

(defn recover-process-managers!
  "Sweep every process-manager type with automatic recovery work."
  ([system] (recover-process-managers! system 0))
  ([system min-age-ms]
   (vec (concat (recover-move-guest-processes! system min-age-ms)
                (recover-decommission-approval-processes! system min-age-ms)))))
