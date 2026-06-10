(ns hotel.system.interface
  "THE CONFIGURATOR: the only brick that sees every other brick's
   interface and plugs concrete impls into the ports (DI via Component)."
  (:require [hotel.system.core :as core]))

(defn in-memory-system [] (core/in-memory-system))
(defn prod-system [config] (core/prod-system config))
(defn test-system [] (core/test-system))
