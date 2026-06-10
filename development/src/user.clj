(ns user
  "REPL-driven development - the REPL is just another DRIVING ADAPTER.
   Start with:  clojure -M:dev   then:  (start!)
   Everything below runs the in-memory system: no Postgres, RabbitMQ
   or Twilio needed. Swap hotel.system.interface/prod-system for real I/O."
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :as tn]
            [hotel.system.interface :as system]
            [hotel.booking.interface :as api]      ; <- the input pins
            [hotel.booking.effects :as effects]
            [hotel.booking.decider :as decider]
            [hotel.booking.room.fsm :as fsm]
            [hotel.booking.slices.book-room.decide :as book-core]
            [hotel.booking.slices.move-guest.process :as pm]
            [hotel.event-store.interface :as es]
            [hotel.clock.interface :as clock]
            [hotel.clock.deterministic :as det-clock]))

(tn/set-refresh-dirs "components" "bases" "development")

(defonce the-system nil)

(defn start! []
  (alter-var-root #'the-system
                  (fn [s] (or s (component/start (system/in-memory-system)))))
  :started)

(defn stop! []
  (alter-var-root #'the-system #(some-> % component/stop))
  :stopped)

(defn reset
  "The 'reloaded' workflow: stop, reload changed namespaces, start again."
  []
  (stop!)
  (tn/refresh :after 'user/start!))

(defn run!*
  "Call a use case on the booking interface (it reacts internally)."
  [use-case command]
  (use-case the-system command))

(comment
  ;; ---- 1. boot ----
  (start!)

  ;; ---- 2. drive use cases through the DRIVING PORT ----
  (run!* booking/book-room!
         {:room-id "102"
          :guest {:name "Ada" :email "ada@example.com"}
          :check-in "2026-07-01" :check-out "2026-07-03"})

  (booking/available-rooms the-system)            ; => ["101" "103"]
  @(:sent (:guest-notifications the-system))  ; domain notifications, no prose
  @(:published (:publisher the-system))       ; integration events
  @(:db (:event-store the-system))            ; peek at the whole event store

  ;; ---- 3. poke the PURE core directly: no system needed at all ----
  (decider/decide book-core/decider
                  [{:event/type :room-booked :room-id "102"}]
                  {:room-id "102"
                   :guest {:name "Bob" :email "bob@example.com"}
                   :check-in "2026-07-04" :check-out "2026-07-05"})
  ;; => {:error :room-already-booked}

  (decider/decide book-core/decider [] {:room-id 102})
  ;; => {:error :invalid-command, :explain {...}}   (Malli)

  (-> fsm/initial-state
      (fsm/evolve {:event/type :room-booked :room-id "102"})
      (fsm/evolve {:event/type :booking-cancelled :room-id "102"}))

  ;; ---- 4. run the PROCESS MANAGER: two streams, no distributed tx ----
  (run!* booking/move-guest! {:move-id "m1"
                          :guest {:name "Ada" :email "ada@example.com"}
                          :from-room "102" :to-room "103"
                          :check-in "2026-07-01" :check-out "2026-07-03"})
  (booking/available-rooms the-system)            ; 103 booked, 102 freed
  (es/read-stream (:event-store the-system) "move-m1")   ; the PM's own story
  (decider/current-state pm/decider
    (es/read-stream (:event-store the-system) "move-m1"))

  ;; ---- 5. time: stamped as evidence, never used for order ----
  (clock/set-uncertainty! (:clock the-system) 2000)
  (run!* booking/book-room! {:room-id "101"
                         :guest {:name "Eve" :email "eve@example.com"}
                         :check-in "2026-07-05" :check-out "2026-07-06"})
  (map :recorded-at (es/read-all (:event-store the-system)))
  (clock/overlapping? {:earliest 0 :latest 2000}
                      {:earliest 50 :latest 2050})   ; => true: incomparable

  ;; ---- 6. other use cases ----
  (run!* booking/cancel-booking! {:room-id "103"})
  (run!* booking/decommission-room! {:room-id "103"})
  (run!* booking/book-room! {:room-id "103"      ; terminal!
                         :guest {:name "Eve" :email "eve@example.com"}
                         :check-in "2026-07-01" :check-out "2026-07-02"})
  ;; => {:error :stream-is-terminal}

  ;; ---- 7. after editing any src file ----
  (reset)

  ;; ---- 8. the whole fast suite, in-REPL ----
  (require '[clojure.test :as t])
  (t/run-all-tests #"booking\..*-test|adapters\..*-test"))
