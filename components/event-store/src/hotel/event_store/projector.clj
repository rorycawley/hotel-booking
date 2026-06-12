(ns hotel.event-store.projector
  "Generic projector COMPONENT: hands a pure `evolve-fn` (and a projection
   name) to the RoomView port and drains events past the checkpoint in
   batches. One worker thread per projector, idempotent shutdown.

   This is INFRASTRUCTURE: the evolve fn is owned by the consuming brick
   (booking/slices/available-rooms/projection.clj), the projector just
   loops `advance-room-view!`."
  (:require [com.stuartsierra.component :as component]
            [hotel.event-store.protocol :as es]))

(def ^:private default-batch-size 500)
(def ^:private default-poll-ms    50)

(defn- run-loop! [{:keys [event-store running? projection-name evolve-fn
                          batch-size poll-ms]}]
  (try
    (while @running?
      (let [n (try
                (es/advance-room-view! event-store projection-name
                                       evolve-fn batch-size)
                (catch Throwable t
                  (.println System/err
                            (str "[projector " projection-name "] "
                                 (.getMessage t)))
                  0))]
        (when (zero? n) (Thread/sleep poll-ms))))
    (catch InterruptedException _ nil)))

(defrecord Projector [event-store projection-name evolve-fn
                      batch-size poll-ms auto-start? running? thread]
  component/Lifecycle
  (start [this]
    (let [self (assoc this
                      :batch-size (or batch-size default-batch-size)
                      :poll-ms    (or poll-ms default-poll-ms))]
      (cond
        thread self
        ;; auto-start? false -> no worker thread; queries advance the
        ;; view synchronously, useful in tests
        (false? auto-start?) self
        :else
        (let [running? (atom true)
              self     (assoc self :running? running?)
              t        (doto (Thread. ^Runnable #(run-loop! self)
                                      (str "hotel.projector." projection-name))
                         (.setDaemon true)
                         (.start))]
          (assoc self :thread t)))))
  (stop [this]
    (when running? (reset! running? false))
    (when thread   (.interrupt ^Thread thread))
    (assoc this :running? nil :thread nil)))

(defn create [config]
  (map->Projector config))
