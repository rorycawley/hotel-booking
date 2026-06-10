(ns hotel.rest-api.routes-test
  "The HTTP adapter drives the same interface the test suite does."
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hotel.system.interface :as system]
            [hotel.rest-api.routes :as routes]))

(defn- post [system uri params]
  (routes/handler system {:request-method :post :uri uri :params params}))

(deftest booking-via-http-is-one-task-one-command
  (let [sys (system/test-system)
        response (post sys "/rooms/101/book"
                       {"guest-name" "Ada" "guest-email" "ada@example.com"
                        "check-in" "2026-07-01" "check-out" "2026-07-03"})]
    (is (= 200 (:status response)))
    (let [rooms (routes/handler sys {:request-method :get :uri "/rooms" :params {}})]
      (is (= ["102" "103"]
             (:available (json/parse-string (:body rooms) true)))))))

(deftest a-rejected-task-maps-to-409
  (let [sys (system/test-system)
        params {"guest-name" "Ada" "guest-email" "ada@example.com"
                "check-in" "2026-07-01" "check-out" "2026-07-03"}]
    (post sys "/rooms/101/book" params)
    (is (= 409 (:status (post sys "/rooms/101/book" params))))))

(deftest crud-shaped-routes-do-not-exist
  (let [sys (system/test-system)]
    (is (= 404 (:status (routes/handler sys {:request-method :put
                                             :uri "/rooms/101" :params {}}))))))
