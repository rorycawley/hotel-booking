(ns hotel.system.core
  "THE CONFIGURATOR (Cockburn): the only brick that sees every other
   brick's interface and plugs concrete impls into the ports at startup -
   constructor-style dependency injection via Component. (His dynamic
   option - a broker looked up per call - is for runtime adapter
   selection we don't need.)"
  (:require [com.stuartsierra.component :as component]
            [hotel.booking.interface :as booking]
            [hotel.event-store.interface :as event-store]
            [hotel.integration.interface :as integration]
            [hotel.notifications.interface :as notifications]
            [hotel.clock.interface :as clock]))

(defn in-memory-system
  "Every driven port gets its TEST impl - used by tests and the REPL."
  []
  (component/system-map
   :event-store         (event-store/in-memory)
   :publisher           (integration/in-memory)
   :guest-notifications (notifications/recording)
   :clock               (clock/deterministic)
   :reactors            booking/reactors
   :command-handlers    booking/command-handlers
   :all-room-ids        ["101" "102" "103"]))

(defn prod-system [{:keys [jdbc-url rabbit-uri sendgrid-key from-email]}]
  (component/system-map
   :event-store         (event-store/postgres jdbc-url)
   :publisher           (integration/rabbitmq {:uri rabbit-uri :exchange "booking"})
   :guest-notifications (notifications/twilio {:api-key sendgrid-key :from from-email})
   :clock               (clock/system-clock {:uncertainty-ms 5}) ; NTP-class
   :reactors            booking/reactors
   :command-handlers    booking/command-handlers
   :all-room-ids        ["101" "102" "103"]))

(defn test-system
  "A STARTED in-memory system, ready to use."
  []
  (component/start (in-memory-system)))
