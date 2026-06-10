(ns hotel.rest-api.routes
  "DRIVING ADAPTER (base): translates HTTP requests into calls on the
   booking interface - and nothing else. Translation of transport shape
   (form fields, JSON encoding) lives HERE, outside the capability."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [hotel.booking.interface :as booking]
            [hotel.rest-api.task-forms :as forms]))

(defn- segments [uri] (vec (remove str/blank? (str/split uri #"/"))))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- ->response [result]
  (json-response (if (:error result) 409 200) result))

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

(defn handler [system {:keys [request-method uri params]}]
  (let [[root id task] (segments uri)]
    (cond
      ;; read side
      (and (= :get request-method) (= "rooms" root) (nil? id))
      (json-response 200 {:available (booking/available-rooms system)})

      ;; task screens
      (and (= :get request-method) (= "rooms" root) (= "book" task))
      {:status 200 :headers {"Content-Type" "text/html"}
       :body (forms/book-room-form id)}

      ;; task endpoints: one task = one command on the driving port
      (and (= :post request-method) (= "rooms" root) (= "book" task))
      (->response (booking/book-room! system (form->book-command params id)))

      (and (= :post request-method) (= "rooms" root) (= "cancel" task))
      (->response (booking/cancel-booking! system {:room-id id}))

      (and (= :post request-method) (= "rooms" root) (= "decommission" task))
      (->response (booking/decommission-room! system {:room-id id}))

      (and (= :post request-method) (= "moves" root) (= "start" task))
      (->response (booking/move-guest! system (form->move-command params id)))

      :else (json-response 404 {:error :no-such-task}))))
