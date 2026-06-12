(ns hotel.booking.decider
  "The Decider pattern (thinkbeforecoding, 2021-12-17), plus Malli validation,
   plus the enterprise-integration shell: idempotency, identity (event-id /
   correlation-id / causation-id), and the TRANSACTIONAL OUTBOX.

   A decider is the seven elements + its command schema:
     {:command-schema malli-schema
      :initial-state  s
      :decide         (fn [state command] -> {:events [..]} | {:error ..})
      :evolve         (fn [state event]   -> state)
      :terminal?      (fn [state]         -> bool)}

   The PURE core (decide/evolve/terminal?) is unchanged. All the new
   machinery lives in `handle` - the only impure step. In order:

     1. command idempotency  - if `:command/id` was processed, return cache
     2. read history          - via the EventStore port
     3. pure decide           - history + command -> {:events ..} | {:error ..}
     4. stamp identity        - event-id, correlation-id, causation-id, recorded-at
     5. pure react            - reactors over new events -> effect data
     6. partition effects     - :publish (transactional) vs the rest (post-commit)
     7. atomic write          - events + outbox + processed_commands, ONE TX
     8. return                - {:events .. :other-effects [..] :correlation-id ..}

   The post-commit effects are run by the caller (booking/interface) - keeps
   the write boundary tiny and the post-commit failure mode explicit
   (publish is durable via outbox; notify/dispatch are best-effort)."
  (:require [hotel.event-store.interface :as es]
            [hotel.clock.interface :as clock]
            [hotel.ids.interface :as ids]
            [hotel.problems.interface :as problems]
            [hotel.booking.effects :as effects]
            [hotel.booking.pii :as pii]
            [hotel.booking.upcasters :as upcasters]
            [malli.core :as m]
            [malli.error :as me])
  (:import [clojure.lang ExceptionInfo]))

(defn- concurrency-conflict? [e]
  (= :concurrency-conflict
     (:hotel.event-store/error (ex-data e))))

(defn current-state
  "fold: replay history through evolve from the initial state."
  [{:keys [initial-state evolve]} history]
  (reduce evolve initial-state history))

(defn decide
  "PURE. Given past events and a command, what happens?
   1. malformed command  -> {:error :invalid-command :explain ..}
   2. terminal stream    -> {:error :stream-is-terminal}
   3. otherwise          -> the slice's own decision"
  [{:keys [terminal? command-schema] :as decider} history command]
  (if (and command-schema (not (m/validate command-schema command)))
    {:error   :invalid-command
     :explain (me/humanize (m/explain command-schema command))}
    (let [state (current-state decider history)]
      (if (terminal? state)
        {:error :stream-is-terminal}
        ((:decide decider) state command)))))

(defn- stamp [id-source events {:keys [correlation-id causation-id recorded-at actor-id]}]
  (mapv (fn [e]
          (assoc e
                 :event/id       (ids/new-id id-source)
                 :event/v        (or (:event/v e) 1)
                 :correlation-id correlation-id
                 :causation-id   causation-id
                 :recorded-at    recorded-at
                 :actor-id       actor-id))
        events))

(defn- check-clock-quality!
  "Record (don't fail) clock anomalies via the ProblemSink. Order is
   always by log position - this is purely evidence quality. We catch:
     - malformed interval (:earliest > :latest)
     - definitely-before vs the last event on the stream, i.e. the
       wall clock has gone BACKWARDS (a court-safe sign of skew, not
       just incomparability)."
  [{:keys [problem-sink]} stream-id correlation-id history recorded-at]
  (when problem-sink
    (let [{:keys [earliest latest]} recorded-at
          last-recorded (some-> history last :recorded-at)]
      (cond
        (or (nil? earliest) (nil? latest) (> earliest latest))
        (problems/record-problem!
         problem-sink
         {:problem        :clock-interval-malformed
          :stream-id      stream-id
          :correlation-id correlation-id
          :interval       recorded-at})

        (and last-recorded (clock/definitely-before? recorded-at last-recorded))
        (problems/record-problem!
         problem-sink
         {:problem        :clock-went-backwards-vs-stream-history
          :stream-id      stream-id
          :correlation-id correlation-id
          :now            recorded-at
          :previous       last-recorded})))))

(defn- outbox-message-from
  "Build one outbox row from a :publish effect emitted by a reactor.
   The reactor stays pure; the shell mints the message id and copies the
   identity headers from the source event so traces stay coherent."
  [id-source {:keys [topic payload source-event]}]
  {:message-id     (ids/new-id id-source)
   :correlation-id (:correlation-id source-event)
   :causation-id   (:event/id source-event)
   :topic          topic
   :payload        payload})

(defn- check-backpressure!
  "Outbox depth check: if the relay has fallen so far behind that the
   outbox is N pending rows deep, REJECT new commands so it cannot grow
   unbounded. Below the hard cap but above the warning, record a problem
   so ops gets early notice. `outbox-backpressure` is wired by the
   configurator: {:warn-at .. :reject-at ..}; absent disables the check."
  [{:keys [event-store outbox-backpressure problem-sink]} correlation-id]
  (when-let [{:keys [warn-at reject-at]} outbox-backpressure]
    (let [depth (es/outbox-depth event-store)]
      (cond
        (and reject-at (>= depth reject-at))
        {:error :system-overloaded :outbox-depth depth}

        (and warn-at (>= depth warn-at))
        (do (when problem-sink
              (problems/record-problem!
               problem-sink
               {:problem :outbox-depth-over-warning
                :outbox-depth depth
                :warn-at warn-at
                :correlation-id correlation-id}))
            nil)))))

(defn cached-command-result [event-store subject-keys command-id]
  (some-> (es/command-result event-store command-id)
          (update :events #(pii/decrypt-events subject-keys %))))

(defn handle
  "load events -> pure decide -> STAMP -> pure react -> ATOMIC append
   (events + outbox + processed_commands) -> return post-commit effects.
   The single impure step; identical for every decider."
  [decider {:keys [event-store clock subject-keys] :as system} stream-id command]
  (let [id-source      (:ids system)
        command-id     (or (:command/id command) (ids/new-id id-source))
        correlation-id (or (:correlation-id command) (ids/new-id id-source))
        cached         (cached-command-result event-store subject-keys command-id)
        overloaded     (check-backpressure! system correlation-id)]
    (cond
      cached
      ;; Idempotent replay. NB: events come back from the cache encrypted
      ;; (we stored them post-encryption), so decrypt on the way out so
      ;; the caller sees the same shape it would have seen first time.
      ;; Rejected commands are intentionally NOT cached - ADR-0003.
      cached

      overloaded overloaded

      :else
      (let [history (->> (es/read-stream event-store stream-id)
                         upcasters/upcast-all
                         (pii/decrypt-events subject-keys))
            result  (decide decider history command)]
        (if (:error result)
          result
          (let [recorded-at    (clock/now-interval clock)
                _              (check-clock-quality! system stream-id
                                                     correlation-id
                                                     history recorded-at)
                stamped        (stamp id-source (:events result)
                                      {:correlation-id correlation-id
                                       :causation-id   command-id
                                       :recorded-at    recorded-at
                                       :actor-id       (:actor-id command)})
                {:keys [publish other invalid]}
                (effects/partition-reactor-output system stamped)
                _              (effects/report-invalid-publishes! system invalid)
                outbox-msgs    (mapv #(outbox-message-from id-source %) publish)
                ;; Encrypt JUST before persistence: reactors and the
                ;; caller see plaintext; the journal stores ciphertext.
                ;; Erasing the subject's key later renders the stored
                ;; payload permanently unreadable (GDPR Art. 17).
                encrypted      (pii/encrypt-events subject-keys stamped)
                command-result {:events encrypted
                                :correlation-id correlation-id}]
            (try
              (es/transactional-append!
               event-store
               {:stream-id        stream-id
                :expected-version (count history)
                :events           encrypted
                :outbox           outbox-msgs
                :command-record   {:command-id   command-id
                                   :command-type (or (:command/type command)
                                                     :anonymous-command)
                                   :result       command-result}})
              ;; The caller sees the PLAINTEXT events the decider just
              ;; emitted - what they expect from a successful command.
              (-> command-result
                  (assoc :events stamped
                         :other-effects other
                         :command-id    command-id))
              (catch ExceptionInfo e
                (if (concurrency-conflict? e)
                  ;; A conflict here can mean two things:
                  ;;   1. A different command raced to the same stream.
                  ;;   2. A retry with the SAME :command/id raced through
                  ;;      the cached check before the first attempt
                  ;;      committed. The shell promises idempotency on
                  ;;      :command/id, so re-read the cache: if the first
                  ;;      attempt has now committed, return its result.
                  ;;      Otherwise it's a real conflict.
                  (or (cached-command-result event-store subject-keys command-id)
                      {:error :concurrency-conflict})
                  (throw e))))))))))
