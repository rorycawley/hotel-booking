(ns hotel.rest-api.routes-test
  "The HTTP adapter drives the same interface the test suite does."
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hotel.system.interface :as system]
            [hotel.rest-api.routes :as routes]))

(defn- post [system uri params]
  (routes/handler system {:request-method :post :uri uri :params params}))

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
