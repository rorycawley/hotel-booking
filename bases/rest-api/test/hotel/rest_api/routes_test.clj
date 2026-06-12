(ns hotel.rest-api.routes-test
  "The HTTP adapter drives the same interface the test suite does."
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hotel.booking.interface :as booking]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system]
            [hotel.rest-api.routes :as routes]))

(def ^:private request-counter (atom 0))

(defn- uuid-for [s]
  (str (java.util.UUID/nameUUIDFromBytes (.getBytes s "UTF-8"))))

(defn- identity-headers []
  (let [n (swap! request-counter inc)]
    {"Idempotency-Key"  (uuid-for (str "command/" n))
     "X-Correlation-Id" (uuid-for (str "correlation/" n))}))

(defn- post
  ([system uri params] (post system uri params {}))
  ([system uri params headers]
   (routes/handler system {:request-method :post :uri uri
                           :params params
                           :headers (merge (identity-headers) headers)})))

(defn- get* [system uri]
  (routes/handler system {:request-method :get :uri uri :params {}}))

(defn- body-json [response]
  (json/parse-string (:body response) true))

(def booking-params
  {"guest-name" "Ada" "guest-email" "ada@example.com"
   "check-in" "2026-07-01" "check-out" "2026-07-03"})

(deftest booking-via-http-is-one-task-one-command
  (let [sys (system/test-system)
        response (post sys "/rooms/101/book" booking-params)]
    (is (= 200 (:status response)))
    (is (= ["102" "103"]
           (:available (body-json (get* sys "/rooms")))))))

(deftest cancelling-via-http-frees-the-room
  (let [sys (system/test-system)]
    (post sys "/rooms/101/book" booking-params)
    (let [response (post sys "/rooms/101/cancel" {})]
      (is (= 200 (:status response)))
      (is (= ["101" "102" "103"]
             (:available (body-json (get* sys "/rooms"))))))))

(deftest cancelling-an-unbooked-room-via-http-is-rejected
  (let [sys (system/test-system)
        response (post sys "/rooms/101/cancel" {})]
    (is (= 409 (:status response)))
    (is (= "room-not-booked" (:error (body-json response))))))

(deftest decommissioning-via-http-removes-the-room
  (let [sys (system/test-system)
        response (post sys "/rooms/103/decommission" {})]
    (is (= 200 (:status response)))
    (is (= ["101" "102"]
           (:available (body-json (get* sys "/rooms")))))))

(deftest decommissioning-a-booked-room-via-http-is-rejected
  (let [sys (system/test-system)]
    (post sys "/rooms/103/book" booking-params)
    (let [response (post sys "/rooms/103/decommission" {})]
      (is (= 409 (:status response)))
      (is (= "room-still-booked" (:error (body-json response))))
      (is (= ["101" "102"]
             (:available (body-json (get* sys "/rooms"))))))))

(deftest moving-a-guest-via-http-books-new-room-and-frees-old-room
  (let [sys (system/test-system)]
    (post sys "/rooms/102/book" booking-params)
    (let [response (post sys "/moves/m1/start"
                         (assoc booking-params
                                "from-room" "102"
                                "to-room" "103"))]
      (is (= 200 (:status response)))
      (is (= ["101" "102"]
             (:available (body-json (get* sys "/rooms"))))))))

(deftest moving-to-an-already-booked-room-via-http-records-process-failure
  (let [sys (system/test-system)]
    (post sys "/rooms/102/book" booking-params)
    (post sys "/rooms/103/book" (assoc booking-params
                                       "guest-name" "Bob"
                                       "guest-email" "bob@example.com"))
    (let [response (post sys "/moves/m1/start"
                         (assoc booking-params
                                "from-room" "102"
                                "to-room" "103"))]
      (is (= 200 (:status response)))
      (is (= ["101"]
             (:available (body-json (get* sys "/rooms"))))))))

(deftest a-rejected-task-maps-to-409
  (let [sys (system/test-system)]
    (post sys "/rooms/101/book" booking-params)
    (is (= 409 (:status (post sys "/rooms/101/book" booking-params))))))

(deftest crud-shaped-routes-do-not-exist
  (let [sys (system/test-system)]
    (is (= 404 (:status (routes/handler sys {:request-method :put
                                             :uri "/rooms/101" :params {}}))))))

;; ---- identity headers (RFC-style idempotency + tracing) ---------------

(deftest idempotency-key-header-deduplicates-retries
  (let [sys      (system/test-system)
        cmd-id   (uuid-for "retry-command")
        corr-id  (uuid-for "retry-correlation")
        retry    #(post sys "/rooms/101/book" booking-params
                        {"Idempotency-Key" cmd-id
                         "X-Correlation-Id" corr-id})
        r1 (retry)
        outbox-after-first (es/outbox-depth (:event-store sys))
        r2 (retry)
        outbox-after-replay (es/outbox-depth (:event-store sys))]
    (is (= 200 (:status r1) (:status r2)) "both requests look the same to the client")
    (is (= outbox-after-first outbox-after-replay)
        "replay must NOT add a second outbox row (no double-publish)")
    (is (= 1 (count (es/read-stream (:event-store sys) "room-101")))
        "replay must NOT append a second event")))

(deftest state-changing-routes-require-idempotency-and-correlation-ids
  (let [sys (system/test-system)
        r   (routes/handler sys {:request-method :post
                                 :uri "/rooms/101/book"
                                 :params booking-params
                                 :headers {}})]
    (is (= 400 (:status r)))
    (is (= "missing-identity-headers" (:error (body-json r))))))

(deftest malformed-identity-headers-are-rejected
  (let [sys (system/test-system)
        r   (routes/handler sys {:request-method :post
                                 :uri "/rooms/101/book"
                                 :params booking-params
                                 :headers {"Idempotency-Key" "not-a-uuid"
                                           "X-Correlation-Id" (uuid-for "corr")}})]
    (is (= 400 (:status r)))
    (is (= "invalid-identity-headers" (:error (body-json r))))
    (is (= "Idempotency-Key" (:header (body-json r))))))

;; ---- ops endpoints (#4 ops floor) -------------------------------------

(deftest health-returns-up-when-the-process-is-running
  (let [sys (system/test-system)
        r   (get* sys "/health")]
    (is (= 200 (:status r)))
    (is (= "up" (:status (body-json r))))))

(deftest ready-returns-200-when-the-event-store-is-reachable
  (let [sys (system/test-system)
        r   (get* sys "/ready")]
    (is (= 200 (:status r)))
    (is (true? (:ready (body-json r))))))

(deftest metrics-emits-prometheus-text
  (let [sys (system/test-system)
        r   (get* sys "/metrics")]
    (is (= 200 (:status r)))
    (is (re-find #"text/plain" (get-in r [:headers "Content-Type"])))
    (is (re-find #"hotel_outbox_pending" (:body r)))
    (is (re-find #"hotel_projection_lag_rooms" (:body r)))))

(deftest correlation-id-header-is-echoed-on-the-response
  (let [sys  (system/test-system)
        corr (uuid-for "echoed-correlation")
        r    (post sys "/rooms/101/book" booking-params
                   {"Idempotency-Key" (uuid-for "echoed-command")
                    "X-Correlation-Id" corr})]
    (is (= corr (get-in r [:headers "X-Correlation-Id"]))
        "tracing header round-trips so callers can stitch logs together")))

;; ---- document upload / download (supporting documents) ---------------

(deftest documents-post-uploads-and-get-returns-the-same-bytes
  (let [sys     (system/test-system)
        payload "FAKE-PASSPORT-BYTES"
        body    (java.io.ByteArrayInputStream. (.getBytes ^String payload "UTF-8"))
        upload  (routes/handler sys {:request-method :post :uri "/documents"
                                     :headers (merge (identity-headers)
                                                     {"Content-Type" "application/pdf"
                                                      "X-Actor-Id" "auth0|registrar-1"
                                                      "X-Subject-Natural-Id" "ada@example.com"})
                                     :body body})
        doc-id  (-> upload :body (json/parse-string true) :document-id)
        get-r   (routes/handler sys {:request-method :get
                                     :uri (str "/documents/" doc-id)
                                     :params {}})]
    (is (= 201 (:status upload)))
    (is (string? doc-id))
    (is (= 200 (:status get-r)))
    (is (= "application/pdf" (get-in get-r [:headers "Content-Type"])))
    (is (= payload (String. ^bytes (:body get-r) "UTF-8")))))

(deftest document-upload-idempotency-key-deduplicates-retries
  (let [sys     (system/test-system)
        headers (merge (identity-headers)
                       {"Content-Type" "application/pdf"
                        "X-Actor-Id" "auth0|registrar-1"
                        "X-Subject-Natural-Id" "ada@example.com"})
        upload  #(routes/handler sys {:request-method :post :uri "/documents"
                                      :headers headers
                                      :body (java.io.ByteArrayInputStream.
                                             (.getBytes "FAKE-PASSPORT-BYTES" "UTF-8"))})
        r1      (upload)
        r2      (upload)
        doc-id  (:document-id (body-json r1))]
    (is (= 201 (:status r1) (:status r2)))
    (is (= doc-id (:document-id (body-json r2))))
    (is (= 1 (count (es/read-stream (:event-store sys) (str "document-" doc-id))))
        "replay must NOT append a second document event")))

(deftest documents-post-without-identity-headers-is-rejected
  (let [sys (system/test-system)
        r   (routes/handler sys {:request-method :post :uri "/documents"
                                 :headers {"Content-Type" "application/pdf"}
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "x" "UTF-8"))})]
    (is (= 400 (:status r)))
    (is (= "missing-identity-headers" (:error (body-json r))))))

(deftest a-document-for-an-erased-subject-returns-410-gone
  (let [sys     (system/test-system)
        upload  (routes/handler sys {:request-method :post :uri "/documents"
                                     :headers (merge (identity-headers)
                                                     {"Content-Type" "application/pdf"
                                                      "X-Actor-Id" "auth0|registrar-1"
                                                      "X-Subject-Natural-Id" "ada@example.com"})
                                     :body (java.io.ByteArrayInputStream.
                                            (.getBytes "PII-BYTES" "UTF-8"))})
        doc-id  (-> upload :body (json/parse-string true) :document-id)]
    (booking/erase-by-natural-id! sys "ada@example.com")
    (let [r (routes/handler sys {:request-method :get
                                 :uri (str "/documents/" doc-id) :params {}})]
      (is (= 410 (:status r)) "erasure -> 410 Gone is the right HTTP semantic"))))

;; ---- URL identifier validation (stream-id abuse prevention) -----------

(deftest a-1000-character-room-id-is-rejected-before-the-capability
  (let [sys (system/test-system)
        too-long (apply str (repeat 1000 "a"))
        r   (post sys (str "/rooms/" too-long "/book") booking-params)]
    (is (= 400 (:status r)))
    (is (= "invalid-identifier" (:error (body-json r))))
    (is (empty? (es/read-stream (:event-store sys) (str "room-" too-long)))
        "no stream was ever created")))

(deftest a-room-id-with-illegal-characters-is-rejected
  (let [sys (system/test-system)
        ;; characters outside [A-Za-z0-9_-]
        r   (post sys "/rooms/101%20OR%201%3D1/book" booking-params)]
    (is (= 400 (:status r)))))

(deftest valid-room-ids-with-allowed-charset-go-through
  (let [sys (system/test-system)]
    ;; underscore and dash are allowed
    (is (#{200 409}
         (:status (post sys "/rooms/A-1/book" booking-params)))
        "allowed charset reaches the capability (200 booked or 409 if room unknown)")))
