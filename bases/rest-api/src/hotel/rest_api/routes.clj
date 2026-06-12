(ns hotel.rest-api.routes
  "DRIVING ADAPTER (base): translates HTTP requests into calls on the
   booking interface - and nothing else. Translation of transport shape
   (form fields, JSON encoding, identity headers) lives HERE, outside
   the capability.

   Required identity headers on every state-changing request:
     Idempotency-Key   -> :command/id      (RFC draft idempotency-keys)
     X-Correlation-Id  -> :correlation-id  (the workflow trace id)

   Optional identity headers:
     X-Causation-Id    -> :causation-id    (the upstream event id)
     X-Actor-Id        -> :actor-id        (the principal acting; tied to
                                            the auth subject in a real
                                            deployment - here it is just
                                            the header value)

   Input validation policy:
     URL path identifiers become event-store stream-ids. Without a cap
     a caller could spray garbage stream-ids forever (storage abuse,
     index bloat). We cap them to 1..64 chars of [A-Za-z0-9_-]. Bad
     inputs return 400 before the capability sees them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hotel.booking.interface :as booking]
            [hotel.event-store.interface :as es]
            [hotel.rest-api.task-forms :as forms])
  (:import [java.util UUID]))

(def ^:private identifier-pattern #"^[A-Za-z0-9_-]{1,64}$")
(defn- valid-identifier? [s] (some? (and s (re-matches identifier-pattern s))))

(defn- segments [uri] (vec (remove str/blank? (str/split uri #"/"))))

(defn- header [headers k]
  (or (get headers k) (get headers (str/lower-case k))))

(defn- ->uuid [s]
  (try (some-> s UUID/fromString) (catch Exception _ nil)))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- command-identity-of [{:keys [headers]}]
  (let [idempotency-key (header headers "Idempotency-Key")
        correlation-id  (header headers "X-Correlation-Id")
        causation-id    (header headers "X-Causation-Id")
        actor-id        (header headers "X-Actor-Id")
        command-id      (->uuid idempotency-key)
        correlation     (->uuid correlation-id)
        causation       (->uuid causation-id)]
    (cond
      (or (str/blank? idempotency-key) (str/blank? correlation-id))
      {:error :missing-identity-headers
       :reason "Idempotency-Key and X-Correlation-Id are required"}

      (nil? command-id)
      {:error :invalid-identity-headers
       :header "Idempotency-Key"
       :reason "must be a UUID"}

      (nil? correlation)
      {:error :invalid-identity-headers
       :header "X-Correlation-Id"
       :reason "must be a UUID"}

      (and (some? causation-id) (nil? causation))
      {:error :invalid-identity-headers
       :header "X-Causation-Id"
       :reason "must be a UUID"}

      :else
      {:identity
       (cond-> {:command/id command-id
                :correlation-id correlation}
         causation
         (assoc :causation-id causation)
         actor-id
         (assoc :actor-id actor-id))})))

(defn- with-command-identity [request f]
  (let [identity (command-identity-of request)]
    (if (:error identity)
      (json-response 400 identity)
      (f (:identity identity)))))

(defn- ->response [result]
  (let [status  (if (:error result) 409 200)
        headers (cond-> {"Content-Type" "application/json"}
                  (:correlation-id result)
                  (assoc "X-Correlation-Id" (str (:correlation-id result))))]
    {:status status :headers headers :body (json/generate-string result)}))

(defn- form->book-command [params room-id]
  {:room-id   room-id
   :guest     {:name (get params "guest-name") :email (get params "guest-email")}
   :check-in  (get params "check-in")
   :check-out (get params "check-out")})

(defn- form->move-command [params move-id]
  {:move-id   move-id
   :guest     {:name (get params "guest-name") :email (get params "guest-email")}
   :from-room (get params "from-room")
   :to-room   (get params "to-room")
   :check-in  (get params "check-in")
   :check-out (get params "check-out")})

(defn- health-response
  "Liveness: the process is up and serving."
  [_]
  (json-response 200 {:status "up"}))

(defn- readiness-response
  "Readiness: every CRITICAL dependency is reachable. We cheaply probe
   the event store. RabbitMQ is intentionally NOT a readiness gate:
   the transactional outbox means we can accept bookings even with the
   broker down - readiness should reflect 'can serve traffic', not
   'is everything healthy'."
  [system]
  (try
    (es/outbox-depth (:event-store system))   ; cheap round-trip to Postgres
    (json-response 200 {:ready true})
    (catch Throwable t
      (json-response 503 {:ready false :reason (.getMessage t)}))))

(defn- metrics-response
  "Prometheus text exposition. Cheap, no extra dependency, copy-paste
   into a Grafana board with one scrape rule."
  [system]
  (let [outbox    (try (es/outbox-depth (:event-store system)) (catch Throwable _ -1))
        proj-lag  (try (es/projection-lag (:event-store system) "rooms")
                       (catch Throwable _ -1))
        body (str
              "# HELP hotel_outbox_pending Pending outbox rows (transactional)\n"
              "# TYPE hotel_outbox_pending gauge\n"
              "hotel_outbox_pending " outbox "\n"
              "# HELP hotel_projection_lag_rooms Events behind the rooms projection\n"
              "# TYPE hotel_projection_lag_rooms gauge\n"
              "hotel_projection_lag_rooms " proj-lag "\n")]
    {:status 200
     :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
     :body body}))

(defn- upload-document-response [system {:keys [headers body]} identity]
  (let [actor      (header headers "X-Actor-Id")
        natural-id (header headers "X-Subject-Natural-Id")
        ctype      (or (header headers "Content-Type")
                       "application/octet-stream")]
    (cond
      (or (str/blank? actor) (str/blank? natural-id))
      (json-response 400 {:error :missing-identity-headers
                          :reason "X-Actor-Id and X-Subject-Natural-Id are required"})

      (nil? body)
      (json-response 400 {:error :empty-body})

      :else
      (let [bytes (with-open [in (io/input-stream body)]
                    (.readAllBytes in))
            r (booking/upload-document!
               system (merge identity
                             {:bytes bytes :content-type ctype
                              :natural-id natural-id :actor-id actor}))]
        (if (:error r)
          (json-response 409 r)
          (json-response 201 {:document-id   (:document-id r)
                              :content-hash  (:content-hash r)
                              :content-type  ctype
                              :size-bytes    (alength bytes)}))))))

(defn- download-document-response [system document-id]
  (let [r (booking/download-document system document-id)]
    (cond
      (nil? r)      (json-response 404 {:error :no-such-document})
      (= :erased r) {:status 410
                     :headers {"Content-Type" "application/json"}
                     :body (json/generate-string {:error :erased
                                                  :reason "data subject was erased"})}
      :else         {:status 200
                     :headers {"Content-Type" (:content-type r)
                               "X-Content-Hash" (:content-hash r)}
                     :body   (:bytes r)})))

(defn handler [system {:keys [request-method uri params] :as request}]
  (let [[root id task] (segments uri)
        bad-id         #(json-response 400 {:error :invalid-identifier
                                            :reason "must be 1..64 chars of [A-Za-z0-9_-]"})]
    (cond
      ;; ops probes (Kubernetes / Prometheus)
      (and (= :get request-method) (= "health"  root) (nil? id))
      (health-response system)

      (and (= :get request-method) (= "ready"   root) (nil? id))
      (readiness-response system)

      (and (= :get request-method) (= "metrics" root) (nil? id))
      (metrics-response system)

      ;; documents (supporting evidence: scanned IDs, signed PDFs, ...)
      (and (= :post request-method) (= "documents" root) (nil? id))
      (with-command-identity
        request
        #(upload-document-response system request %))

      (and (= :get request-method) (= "documents" root) (some? id))
      (download-document-response system id)

      ;; read side
      (and (= :get request-method) (= "rooms" root) (nil? id))
      (json-response 200 {:available (booking/available-rooms system)})

      ;; every task endpoint takes an id - reject malformed ones up front
      ;; so the capability never sees them.
      (and (some? id) (not (valid-identifier? id)))
      (bad-id)

      ;; task screens
      (and (= :get request-method) (= "rooms" root) (= "book" task))
      {:status 200 :headers {"Content-Type" "text/html"}
       :body (forms/book-room-form id)}

      ;; task endpoints: one task = one command on the driving port
      (and (= :post request-method) (= "rooms" root) (= "book" task))
      (with-command-identity
        request
        #(->response (booking/book-room! system (merge (form->book-command params id) %))))

      (and (= :post request-method) (= "rooms" root) (= "cancel" task))
      (with-command-identity
        request
        #(->response (booking/cancel-booking! system (merge {:room-id id} %))))

      (and (= :post request-method) (= "rooms" root) (= "decommission" task))
      (with-command-identity
        request
        #(->response (booking/decommission-room! system (merge {:room-id id} %))))

      (and (= :post request-method) (= "moves" root) (= "start" task))
      (with-command-identity
        request
        #(->response (booking/move-guest! system (merge (form->move-command params id) %))))

      :else (json-response 404 {:error :no-such-task}))))
