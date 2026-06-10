(ns hotel.rest-api.main
  "Entry point of the ONE deployable unit. Reads config from the
   environment, starts the production system via the configurator,
   serves the task-based HTTP adapter."
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [com.stuartsierra.component :as component]
            [hotel.system.interface :as system]
            [hotel.rest-api.routes :as routes])
  (:gen-class))

(defn- env! [k]
  (or (System/getenv k)
      (throw (ex-info (str "Missing required environment variable: " k) {:var k}))))

(defn app [system]
  (wrap-params (fn [request] (routes/handler system request))))

(defn -main [& _]
  (let [config {:jdbc-url     (env! "DATABASE_URL")
                :rabbit-uri   (env! "RABBITMQ_URI")
                :sendgrid-key (env! "SENDGRID_API_KEY")
                :from-email   (env! "FROM_EMAIL")}
        port   (Integer/parseInt (or (System/getenv "PORT") "3000"))
        sys    (component/start (system/prod-system config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(component/stop sys)))
    (println (str "hotel-system listening on :" port))
    (jetty/run-jetty (app sys) {:port port :join? true})))
