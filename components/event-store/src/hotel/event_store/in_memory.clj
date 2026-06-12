(ns hotel.event-store.in-memory
  "TEST adapter implementing every port the brick exposes. State lives in
   one atom shaped to mirror the Postgres schema; transactional-append!
   uses a single swap! so events, outbox, and the processed-command row
   commit together or not at all (atom semantics give us that for free)."
  (:require [hotel.event-store.protocol :as es]))

;; The atom shape:
;;   {:streams {stream-id [events]}
;;    :all      [events-in-global-order]
;;    :positions {event-id -> global-position}
;;    :outbox   {id -> row}     ; row = {:id :message-id :topic :payload :correlation-id
;;                              ;        :causation-id :published-at :attempts :last-error}
;;    :next-outbox-id 1
;;    :processed-commands {command-id -> {:command-type :result}}
;;    :inbox {message-id -> {:topic :payload :processed?}}
;;    :checkpoints {projection-name -> last-position}
;;    :room-view {room-id -> {:status :guest-name :guest-email :check-in :check-out}}}

(defn- conflict! [stream-id expected-version actual]
  (throw (ex-info "Concurrency conflict"
                  {:hotel.event-store/error :concurrency-conflict
                   :stream stream-id
                   :expected-version expected-version
                   :actual-version actual})))

(defn- command-conflict! [command-id]
  (throw (ex-info "Command already processed"
                  {:hotel.event-store/error :concurrency-conflict
                   :command-id command-id})))

(defn- do-append [s {:keys [stream-id expected-version events outbox command-record]}]
  (when (and command-record
             (contains? (:processed-commands s)
                        (:command-id command-record)))
    (command-conflict! (:command-id command-record)))
  (let [s (if command-record
            (assoc-in s [:processed-commands (:command-id command-record)]
                      (:result command-record))
            s)
        s (if (and stream-id (seq events))
            (let [current (get-in s [:streams stream-id] [])]
              (when (not= (count current) expected-version)
                (conflict! stream-id expected-version (count current)))
              (let [start-pos (count (:all s []))
                    positions (into {} (map-indexed
                                        (fn [i e] [(:event/id e) (+ start-pos i 1)])
                                        events))]
                (-> s
                    (update-in [:streams stream-id] (fnil into []) events)
                    (update :all (fnil into []) events)
                    (update :positions (fnil merge {}) positions))))
            s)
        s (reduce (fn [acc msg]
                    (let [id (get acc :next-outbox-id 1)]
                      (-> acc
                          (assoc-in [:outbox id]
                                    (assoc msg
                                           :id id
                                           :published-at nil
                                           :attempts 0
                                           :max-attempts 10
                                           :next-attempt-at 0))
                          (assoc :next-outbox-id (inc id)))))
                  s
                  outbox)]
    s))

(defrecord InMemoryEventStore [db]
  es/EventStore
  (read-stream [_ stream-id]
    (get-in @db [:streams stream-id] []))
  (read-all [_]
    (:all @db []))
  (read-after [_ position limit]
    (let [all (:all @db [])
          positions (:positions @db {})]
      (->> all
           (filter #(> (get positions (:event/id %) 0) position))
           (take limit)
           (mapv (fn [e] (assoc e :global-position (get positions (:event/id e))))))))
  (stream-ids-with-prefix [_ prefix]
    (->> (keys (:streams @db {}))
         (filter #(.startsWith ^String % ^String prefix))
         sort
         vec))
  (transactional-append! [_ op]
    (swap! db do-append op)
    op)

  es/ProcessedCommands
  (command-result [_ command-id]
    (get-in @db [:processed-commands command-id]))

  es/Outbox
  (claim-outbox-messages [_ limit]
    ;; ATOMIC claim via a SINGLE swap!: pick eligible rows AND stamp
    ;; claimed-at in one transition so two threads can't see the same
    ;; row. The chosen rows are captured in `claimed` from inside the
    ;; swap-fn - on retry, the swap-fn runs again so `claimed` holds
    ;; the rows from the FINAL successful CAS.
    (let [claimed  (atom nil)
          stale-ms 600000]
      (swap! db
             (fn [s]
               (let [now (System/currentTimeMillis)
                     candidates (->> (vals (:outbox s {}))
                                     (remove :published-at)
                                     (filter #(<= (get % :next-attempt-at 0) now))
                                     (filter #(let [c (get % :claimed-at)]
                                                (or (nil? c)
                                                    (< c (- now stale-ms)))))
                                     (sort-by :id)
                                     (take limit))]
                 (reset! claimed candidates)
                 (reduce (fn [s' row]
                           (assoc-in s' [:outbox (:id row) :claimed-at] now))
                         s
                         candidates))))
      (mapv #(assoc % :claimed-at (System/currentTimeMillis)) @claimed)))
  (mark-outbox-published! [_ id]
    (swap! db assoc-in [:outbox id :published-at] (System/currentTimeMillis)))
  (mark-outbox-failed! [_ id error backoff-ms]
    (swap! db
           (fn [s]
             (let [row (-> (get-in s [:outbox id])
                           (update :attempts (fnil inc 0))
                           (assoc :last-error (str error)
                                  :claimed-at nil   ;; release the claim
                                  :next-attempt-at
                                  (+ (System/currentTimeMillis) (long backoff-ms))))
                   max (get row :max-attempts 10)]
               (if (>= (:attempts row) max)
                 ;; dead-letter it
                 (-> s
                     (update :outbox dissoc id)
                     (update :dead-letter (fnil conj []) (assoc row :outbox-id id)))
                 (assoc-in s [:outbox id] row))))))
  (dead-letter-messages [_ limit]
    (->> (:dead-letter @db [])
         reverse
         (take limit)
         vec))
  (outbox-depth [_]
    (count (remove :published-at (vals (:outbox @db {})))))

  es/Inbox
  (record-inbound! [_ {:keys [message-id topic payload]}]
    (let [result (atom nil)]
      (swap! db (fn [s]
                  (if-let [existing (get-in s [:inbox message-id])]
                    (do (reset! result (if (:processed? existing)
                                         :already-seen
                                         :recorded))
                        s)
                    (do (reset! result :recorded)
                        (assoc-in s [:inbox message-id]
                                  {:topic topic :payload payload :processed? false})))))
      @result))
  (mark-inbound-processed! [_ message-id]
    (swap! db assoc-in [:inbox message-id :processed?] true))

  es/RoomView
  (read-room-view [_]
    (:room-view @db {}))
  (advance-room-view! [this projection-name evolve-fn batch-size]
    (let [from-pos (get-in @db [:checkpoints projection-name] 0)
          batch    (es/read-after this from-pos batch-size)]
      (if (empty? batch)
        0
        (do
          (swap! db (fn [s]
                      (let [new-view (reduce evolve-fn
                                             (:room-view s {})
                                             batch)
                            new-pos  (apply max
                                            from-pos
                                            (map :global-position batch))]
                        (-> s
                            (assoc :room-view new-view)
                            (assoc-in [:checkpoints projection-name] new-pos)))))
          (count batch)))))
  (projection-lag [_ projection-name]
    (let [highest    (count (:all @db []))
          checkpoint (get-in @db [:checkpoints projection-name] 0)]
      (max 0 (- highest checkpoint)))))

(defn create [] (->InMemoryEventStore (atom {:next-outbox-id 1})))
