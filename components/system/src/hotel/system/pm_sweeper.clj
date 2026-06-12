(ns hotel.system.pm-sweeper
  "Component that periodically calls `recover-process-managers!` so PMs
   stranded by a crash (event committed, dispatch never ran) get driven
   to a terminal state without operator intervention.

   Same auto-start? semantics as the outbox relay and projector:
   in tests the Component is wired but the worker is not started;
   tests call the function directly for determinism."
  (:require [com.stuartsierra.component :as component]
            [hotel.booking.interface :as booking]))

(def ^:private default-poll-ms     5000)
(def ^:private default-min-age-ms 30000)

(defn- run-loop! [{:keys [running? min-age-ms poll-ms] :as self}]
  (try
    (while @running?
      (try
        (booking/recover-process-managers! self min-age-ms)
        (catch Throwable t
          (.println System/err
                    (str "[pm-sweeper] " (.getMessage t)))))
      (Thread/sleep poll-ms))
    (catch InterruptedException _ nil)))

(defrecord PmSweeper [event-store clock command-handlers reactors
                      problem-sink
                      poll-ms min-age-ms auto-start?
                      running? thread]
  component/Lifecycle
  (start [this]
    (let [self (assoc this
                      :poll-ms    (or poll-ms default-poll-ms)
                      :min-age-ms (or min-age-ms default-min-age-ms))]
      (cond
        thread self
        (false? auto-start?) self
        :else
        (let [running? (atom true)
              self     (assoc self :running? running?)
              t        (doto (Thread. ^Runnable #(run-loop! self)
                                      "hotel.pm-sweeper")
                         (.setDaemon true)
                         (.start))]
          (assoc self :thread t)))))
  (stop [this]
    (when running? (reset! running? false))
    (when thread (.interrupt ^Thread thread))
    (assoc this :running? nil :thread nil)))

(defn create [config] (map->PmSweeper config))
