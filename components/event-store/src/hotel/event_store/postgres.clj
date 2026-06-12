(ns hotel.event-store.postgres
  "PRODUCTION adapter implementing every port the brick exposes against
   ONE Postgres datasource. The decisive property: transactional-append!
   wraps event INSERTs, outbox INSERTs, and the processed_commands INSERT
   in ONE Postgres transaction. With this, the broker is no longer in the
   critical path - the relay catches up later, but nothing is ever lost
   if RabbitMQ is down.

   Schema in resources/event-store/schema.sql. The schema is applied on
   start - safe because every CREATE is `if not exists`."
  (:require [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [hotel.event-store.protocol :as es]
            [hotel.event-store.migrations :as migrations])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.sql SQLException]
           [java.util UUID]
           [org.postgresql.util PGobject]))

;; ---------- jsonb <-> EDN ----------

(defn- ->jsonb [m]
  (doto (PGobject.) (.setType "jsonb") (.setValue (json/generate-string m))))

(defn- jsonb-> [^PGobject o]
  (some-> o .getValue (json/parse-string true)))

(defn- ->uuid [^String s] (when s (UUID/fromString s)))

(def ^:private uuid-regex
  #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

(defn- string->uuid? [s]
  (and (string? s) (re-matches uuid-regex s)))

(defn- rehydrate-uuids
  "JSONB serialization stringifies UUIDs. Walk the tree once on read so
   callers see the same types both adapters store: real java.util.UUIDs.
   Conservative: only converts strings that look exactly like a UUID."
  [x]
  (cond
    (string? x)  (if (string->uuid? x) (UUID/fromString x) x)
    (map? x)     (into (empty x) (map (fn [[k v]] [k (rehydrate-uuids v)])) x)
    (sequential? x) (into (empty x) (map rehydrate-uuids) x)
    :else        x))

;; rows -> events that look like the in-memory ones
(defn- row->event [row]
  (let [payload (-> row :events/payload jsonb->)]
    (-> payload
        (update :event/type keyword)
        (assoc :event/id        (some-> row :events/event_id str ->uuid)
               :event/v         (or (:events/payload_v row) 1)
               :correlation-id  (some-> row :events/correlation_id str ->uuid)
               :causation-id    (some-> row :events/causation_id   str ->uuid)
               :actor-id        (:events/actor_id row)
               :global-position (:events/global_position row)))))

;; ---------- errors ----------

(defn- conflict! [stream-id expected-version actual-version]
  (throw (ex-info "Concurrency conflict"
                  {:hotel.event-store/error :concurrency-conflict
                   :stream stream-id
                   :expected-version expected-version
                   :actual-version actual-version})))

(defn- unique-violation? [^SQLException e]
  (= "23505" (.getSQLState e)))

;; Schema bootstrap delegates to the migration runner; safe on every start.

;; ---------- write paths ----------

(defn- insert-events! [tx stream-id expected-version events]
  (let [actual (-> (jdbc/execute-one!
                    tx ["select count(*) as n from events where stream_id = ?" stream-id]
                    {:builder-fn rs/as-unqualified-lower-maps})
                   :n)]
    (when (not= actual expected-version)
      (conflict! stream-id expected-version actual)))
  (doseq [[i e] (map-indexed vector events)]
    (jdbc/execute!
     tx
     ["insert into events
        (stream_id, version, event_id, correlation_id, causation_id,
         actor_id, payload_v, payload)
       values (?, ?, ?, ?, ?, ?, ?, ?)"
      stream-id
      (+ expected-version i 1)
      (:event/id e)
      (:correlation-id e)
      (:causation-id e)
      (:actor-id e)
      (or (:event/v e) 1)
      (->jsonb (dissoc e :event/id :correlation-id :causation-id
                       :actor-id :event/v))])))

(defn- insert-outbox! [tx messages]
  (doseq [m messages]
    (jdbc/execute!
     tx
     ["insert into outbox
        (message_id, correlation_id, causation_id, topic, payload)
       values (?, ?, ?, ?, ?)"
      (:message-id m)
      (:correlation-id m)
      (:causation-id m)
      (:topic m)
      (->jsonb (:payload m))])))

(defn- insert-command-record! [tx {:keys [command-id command-type result]}]
  (jdbc/execute!
   tx
   ["insert into processed_commands (command_id, command_type, result)
     values (?, ?, ?)"
    command-id (name command-type) (->jsonb result)]))

;; ---------- the record ----------

(defrecord PostgresEventStore [jdbc-url pool-size connection-timeout-ms datasource]
  component/Lifecycle
  (start [this]
    (if datasource
      this
      ;; HikariCP pool: prod gets real concurrency, tests get a tiny pool.
      ;; Default pool-size 10 is fine for the relay + a few request threads.
      ;; `connectionTimeout` defaults to HikariCP's 30 000 ms; the
      ;; pool-exhaustion test passes a short value (1 000 ms) so the
      ;; assertion completes quickly AND its claim is sharper - "fails
      ;; within the configured timeout" rather than "either succeeds or
      ;; throws within the default 30 s window."
      ;; If migrations throw, CLOSE the pool before propagating -
      ;; otherwise a failed startup leaks the pool's connections.
      (let [ds (connection/->pool HikariDataSource
                                  {:jdbcUrl           jdbc-url
                                   :maximumPoolSize   (or pool-size 10)
                                   :connectionTimeout (or connection-timeout-ms 30000)
                                   :poolName          "hotel-event-store"})]
        (try
          (migrations/apply-migrations! ds)
          (assoc this :datasource ds)
          (catch Throwable t
            (.close ^HikariDataSource ds)
            (throw t))))))
  (stop [this]
    (when datasource (.close ^HikariDataSource datasource))
    (assoc this :datasource nil))

  es/EventStore
  (read-stream [_ stream-id]
    (mapv row->event
          (jdbc/execute! datasource
                         ["select global_position, event_id, correlation_id, causation_id, actor_id, payload_v, payload
                           from events where stream_id = ? order by version" stream-id])))
  (read-all [_]
    (mapv row->event
          (jdbc/execute! datasource
                         ["select global_position, event_id, correlation_id, causation_id, actor_id, payload_v, payload
                           from events order by global_position"])))
  (read-after [_ position limit]
    (mapv row->event
          (jdbc/execute! datasource
                         ["select global_position, event_id, correlation_id, causation_id, actor_id, payload_v, payload
                           from events where global_position > ? order by global_position
                           limit ?" position limit])))
  (stream-ids-with-prefix [_ prefix]
    (mapv :events/stream_id
          (jdbc/execute!
           datasource
           ["select distinct stream_id from events where stream_id like ?
             order by stream_id"
            (str prefix "%")])))
  (transactional-append! [_ {:keys [stream-id expected-version events outbox command-record]
                             :as op}]
    (try
      (jdbc/with-transaction [tx datasource]
        ;; Claim command-id FIRST. If another transaction already handled
        ;; this command, the unique key aborts before events/outbox can be
        ;; written, and the shell replays the cached result.
        (when command-record
          (insert-command-record! tx command-record))
        (when (and stream-id (seq events))
          (insert-events! tx stream-id expected-version events))
        (when (seq outbox)
          (insert-outbox! tx outbox)))
      op
      (catch SQLException e
        (if (unique-violation? e)
          (conflict! stream-id expected-version nil)
          (throw e)))))

  es/ProcessedCommands
  (command-result [_ command-id]
    (some-> (jdbc/execute-one!
             datasource
             ["select result from processed_commands where command_id = ?" command-id])
            :processed_commands/result
            jsonb->
            rehydrate-uuids))

  es/Outbox
  (claim-outbox-messages [_ limit]
    ;; ATOMIC claim. The UPDATE-RETURNING locks the rows it touches for
    ;; the duration of the UPDATE statement; the inner SELECT FOR UPDATE
    ;; SKIP LOCKED ensures concurrent claimers get DISJOINT sets. Once
    ;; the UPDATE commits, claimed_at marks the row as in-flight; the
    ;; claim filter excludes it until mark-published / mark-failed
    ;; clears it (or 10 minutes pass, in which case we reclaim - so a
    ;; relay that died mid-publish doesn't strand its messages).
    (let [stale-ms 600000]
      (mapv (fn [row]
              {:id             (:outbox/id row)
               :message-id     (some-> row :outbox/message_id str ->uuid)
               :correlation-id (some-> row :outbox/correlation_id str ->uuid)
               :causation-id   (some-> row :outbox/causation_id str ->uuid)
               :topic          (:outbox/topic row)
               :payload        (jsonb-> (:outbox/payload row))
               :attempts       (:outbox/attempts row)})
            (jdbc/execute!
             datasource
             ["update outbox
                set claimed_at = now()
                where id in (
                  select id from outbox
                  where published_at is null
                    and next_attempt_at <= now()
                    and (claimed_at is null
                         or claimed_at < now() - (? * interval '1 millisecond'))
                  order by id
                  limit ?
                  for update skip locked
                )
                returning id, message_id, correlation_id, causation_id,
                          topic, payload, attempts"
              (long stale-ms) limit]))))
  (mark-outbox-published! [_ id]
    (jdbc/execute! datasource
                   ["update outbox set published_at = now() where id = ?" id]))
  (mark-outbox-failed! [_ id error backoff-ms]
    (jdbc/with-transaction [tx datasource]
      (let [row (jdbc/execute-one!
                 tx
                 ["update outbox
                    set attempts = attempts + 1,
                        last_error = ?,
                        claimed_at = null,
                        next_attempt_at = now() + (? * interval '1 millisecond')
                    where id = ?
                    returning id, message_id, correlation_id, causation_id,
                              topic, payload, attempts, max_attempts, last_error"
                  (str error) (long backoff-ms) id])]
        (when (and row (>= (:outbox/attempts row) (:outbox/max_attempts row)))
          (jdbc/execute!
           tx
           ["insert into dead_letter_outbox
              (outbox_id, message_id, correlation_id, causation_id,
               topic, payload, attempts, last_error)
             values (?, ?, ?, ?, ?, ?, ?, ?)"
            (:outbox/id row)
            (:outbox/message_id row)
            (:outbox/correlation_id row)
            (:outbox/causation_id row)
            (:outbox/topic row)
            (:outbox/payload row)
            (:outbox/attempts row)
            (:outbox/last_error row)])
          (jdbc/execute! tx ["delete from outbox where id = ?" id])))))
  (dead-letter-messages [_ limit]
    (mapv (fn [row]
            {:id             (:dead_letter_outbox/id row)
             :outbox-id      (:dead_letter_outbox/outbox_id row)
             :message-id     (some-> row :dead_letter_outbox/message_id str ->uuid)
             :correlation-id (some-> row :dead_letter_outbox/correlation_id str ->uuid)
             :causation-id   (some-> row :dead_letter_outbox/causation_id str ->uuid)
             :topic          (:dead_letter_outbox/topic row)
             :payload        (jsonb-> (:dead_letter_outbox/payload row))
             :attempts       (:dead_letter_outbox/attempts row)
             :last-error     (:dead_letter_outbox/last_error row)})
          (jdbc/execute!
           datasource
           ["select * from dead_letter_outbox
             order by dead_lettered_at desc limit ?" limit])))
  (outbox-depth [_]
    (-> (jdbc/execute-one!
         datasource
         ["select count(*) as n from outbox where published_at is null"])
        :n))

  es/Inbox
  (record-inbound! [_ {:keys [message-id topic payload]}]
    (let [inserted (some-> (jdbc/execute-one!
                            datasource
                            ["insert into inbox (message_id, topic, payload)
                              values (?, ?, ?)
                              on conflict (message_id) do nothing
                              returning message_id" message-id topic (->jsonb payload)])
                           :inbox/message_id)]
      (if inserted
        :recorded
        (if (some-> (jdbc/execute-one!
                     datasource
                     ["select processed_at from inbox where message_id = ?" message-id])
                    :inbox/processed_at)
          :already-seen
          :recorded))))
  (mark-inbound-processed! [_ message-id]
    (jdbc/execute! datasource
                   ["update inbox set processed_at = now() where message_id = ?" message-id]))

  es/RoomView
  (read-room-view [_]
    (into {}
          (map (fn [row]
                 [(:room_view/room_id row)
                  {:status      (keyword (:room_view/status row))
                   :guest-name  (:room_view/guest_name row)
                   :guest-email (:room_view/guest_email row)
                   :check-in    (:room_view/check_in row)
                   :check-out   (:room_view/check_out row)}]))
          (jdbc/execute! datasource ["select * from room_view"])))
  (advance-room-view! [_ projection-name evolve-fn batch-size]
    (jdbc/with-transaction [tx datasource]
      ;; Serialize concurrent projector workers on this projection.
      ;; FOR UPDATE on the projection_checkpoints row would be enough
      ;; AFTER the first run - but on first run the row doesn't exist
      ;; yet, and FOR UPDATE on a missing row locks nothing. An advisory
      ;; lock keyed by projection-name covers BOTH cases. Released at
      ;; commit (the `xact_lock` variant).
      (jdbc/execute! tx ["select pg_advisory_xact_lock(hashtext(?))"
                         projection-name])
      (let [from-pos (or (some-> (jdbc/execute-one!
                                  tx ["select last_position from projection_checkpoints
                                       where projection_name = ? for update"
                                      projection-name])
                                 :projection_checkpoints/last_position)
                         0)
            current-view (into {}
                               (map (fn [row]
                                      [(:room_view/room_id row)
                                       {:status      (keyword (:room_view/status row))
                                        :guest-name  (:room_view/guest_name row)
                                        :guest-email (:room_view/guest_email row)
                                        :check-in    (:room_view/check_in row)
                                        :check-out   (:room_view/check_out row)}]))
                               (jdbc/execute! tx ["select * from room_view"]))
            batch    (mapv row->event
                           (jdbc/execute!
                            tx
                            ["select global_position, event_id, correlation_id, causation_id, actor_id, payload_v, payload
                              from events where global_position > ?
                              order by global_position limit ?" from-pos batch-size]))]
        (if (empty? batch)
          0
          (let [new-view (reduce evolve-fn current-view batch)
                new-pos  (apply max from-pos (map :global-position batch))]
            ;; Incremental: only write rows whose value actually changed,
            ;; only delete rows that disappeared. O(diff), not O(view).
            (doseq [[room-id v] new-view
                    :when (not= v (get current-view room-id))]
              (jdbc/execute!
               tx
               ["insert into room_view
                  (room_id, status, guest_name, guest_email, check_in, check_out)
                 values (?, ?, ?, ?, ?, ?)
                 on conflict (room_id) do update set
                  status     = excluded.status,
                  guest_name = excluded.guest_name,
                  guest_email = excluded.guest_email,
                  check_in   = excluded.check_in,
                  check_out  = excluded.check_out"
                room-id
                (name (:status v))
                (:guest-name v) (:guest-email v) (:check-in v) (:check-out v)]))
            (doseq [room-id (remove (set (keys new-view)) (keys current-view))]
              (jdbc/execute!
               tx ["delete from room_view where room_id = ?" room-id]))
            (jdbc/execute!
             tx
             ["insert into projection_checkpoints (projection_name, last_position, updated_at)
               values (?, ?, now())
               on conflict (projection_name)
               do update set last_position = excluded.last_position, updated_at = now()"
              projection-name new-pos])
            (count batch))))))
  (projection-lag [_ projection-name]
    (let [highest    (or (-> (jdbc/execute-one!
                              datasource
                              ["select coalesce(max(global_position), 0) as p from events"])
                             :p) 0)
          checkpoint (or (some-> (jdbc/execute-one!
                                  datasource
                                  ["select last_position from projection_checkpoints
                                    where projection_name = ?" projection-name])
                                 :projection_checkpoints/last_position)
                         0)]
      (max 0 (- highest checkpoint)))))

(defn create
  ([jdbc-url] (create jdbc-url 10))
  ([jdbc-url pool-size] (create jdbc-url pool-size nil))
  ([jdbc-url pool-size connection-timeout-ms]
   (map->PostgresEventStore {:jdbc-url              jdbc-url
                             :pool-size             pool-size
                             :connection-timeout-ms connection-timeout-ms})))
