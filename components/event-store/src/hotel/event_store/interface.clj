(ns hotel.event-store.interface
  "DRIVEN PORT, Polylith-style: this interface IS the port; the impl
   namespaces (in-memory, postgres) are the adapters. `poly check`
   enforces that other bricks touch nothing but this namespace."
  (:require [hotel.event-store.protocol :as protocol]
            [hotel.event-store.in-memory :as in-memory]
            [hotel.event-store.postgres :as postgres]))

;; the port operations
(def read-stream    protocol/read-stream)
(def append-events! protocol/append-events!)
(def read-all       protocol/read-all)

;; the adapters (the configurator picks one)
(defn in-memory [] (in-memory/create))
(defn postgres  [jdbc-url] (postgres/create jdbc-url))
