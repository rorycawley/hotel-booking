(ns hotel.booking.decider
  "The Decider pattern (thinkbeforecoding, 2021-12-17), plus Malli validation.
   A decider is the seven elements + its command schema:
     {:command-schema malli-schema
      :initial-state  s
      :decide         (fn [state command] -> {:events [..]} | {:error ..})
      :evolve         (fn [state event]   -> state)
      :terminal?      (fn [state]         -> bool)}
   Everything here is generic - it knows no hotel.
   NOTE on {:error ..}: rejections here are EPHEMERAL - no log position,
   no timestamp. That is the correct weight for this domain. When a
   rejection is itself a business/legal fact (a refused registry
   application), it must be a recorded EVENT instead - criterion and
   mechanics in docs/adr/0003."
  (:require [hotel.event-store.interface :as es]
            [hotel.clock.interface :as clock]
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

(defn handle
  "The only impure step, identical for every decider:
   load events -> pure decide -> STAMP -> append (optimistic concurrency).
   Stamping happens here, in the shell: decide stays pure (no clock),
   and every accepted event records WHEN the authority accepted it, as
   an uncertainty interval. Order still comes from the store, not time."
  [decider {:keys [event-store clock]} stream-id command]
  (let [history (es/read-stream event-store stream-id)
        result  (decide decider history command)]
    (if (:error result)
      result
      (let [stamped (mapv #(assoc % :recorded-at (clock/now-interval clock))
                          (:events result))]
        (try
          (es/append-events! event-store stream-id (count history) stamped)
          {:events stamped}
          (catch ExceptionInfo e
            (if (concurrency-conflict? e)
              {:error :concurrency-conflict}
              (throw e))))))))
