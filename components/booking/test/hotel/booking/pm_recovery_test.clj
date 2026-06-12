(ns hotel.booking.pm-recovery-test
  "Process-manager mid-process CRASH RECOVERY. We simulate the
   real-world failure mode (event committed; reactor never ran the
   dispatched command) by INJECTING a PM event directly into the
   event-store, then prove `recover-move-guest-processes!` drives the
   PM to a terminal state - safely, with no duplicate writes."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.booking.decider :as decider]
            [hotel.booking.slices.move-guest.process :as pm]
            [hotel.clock.interface :as clock]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system])
  (:import [java.util UUID]))

(def move-cmd
  {:move-id "m1" :guest {:name "Ada" :email "a@a.io"}
   :from-room "102" :to-room "103"
   :check-in "x" :check-out "y"})

(defn- pm-state [sys move-id]
  (:status (decider/current-state pm/decider
                                  (es/read-stream (:event-store sys)
                                                  (str "move-" move-id)))))

(defn- inject-stuck-pm!
  "Simulate the JVM crashing AFTER `:move-requested` was committed but
   BEFORE its reactor dispatched book-room. Writes the event straight
   to the PM stream via the event-store port."
  [sys cmd]
  (let [event (-> cmd
                  (assoc :event/type      :move-requested
                         :event/id        (UUID/randomUUID)
                         :correlation-id  (UUID/randomUUID)
                         :causation-id    (UUID/randomUUID)
                         :recorded-at     (clock/now-interval (:clock sys))))]
    (es/transactional-append!
     (:event-store sys)
     {:stream-id        (str "move-" (:move-id cmd))
      :expected-version 0
      :events           [event]
      :outbox           []
      :command-record   nil})))

(deftest a-pm-stuck-after-move-requested-is-recovered-to-completion
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "102" :guest (:guest move-cmd)
                         :check-in "x" :check-out "y"})
    ;; SIMULATED CRASH: event committed, reactor never ran.
    (inject-stuck-pm! sys move-cmd)
    (is (= :booking-new-room (pm-state sys "m1")) "PM is stuck")

    (api/recover-move-guest-processes! sys)

    (is (= :completed (pm-state sys "m1"))
        "after recovery the PM ran the full protocol to completion")
    (is (= ["101" "102"] (api/available-rooms sys))
        "the world is what a successful move produces")))

(deftest recovery-decrypts-persisted-pm-events-before-reacting
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "102" :guest (:guest move-cmd)
                         :check-in "x" :check-out "y"})
    ;; Use the real command path so the stuck PM event is encrypted at
    ;; rest, but disable reactors only for this call to simulate a crash
    ;; before the first dispatched command ran.
    (api/move-guest! (assoc sys :reactors []) move-cmd)
    (is (= :booking-new-room (pm-state sys "m1")) "PM is stuck")
    (is (not= "Ada" (-> (es/read-stream (:event-store sys) "move-m1")
                        first :guest :name))
        "the persisted PM event has encrypted PII")

    (api/recover-move-guest-processes! sys)

    (is (= :completed (pm-state sys "m1"))
        "recovery decrypts the PM event before re-running the reactor")
    (is (= ["101" "102"] (api/available-rooms sys)))))

(deftest recovery-is-IDEMPOTENT-and-does-not-double-book
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "102" :guest (:guest move-cmd)
                         :check-in "x" :check-out "y"})
    (inject-stuck-pm! sys move-cmd)
    ;; Recover THREE times - any subsequent re-fires must hit the
    ;; idempotency cache (deterministic command-ids) and add nothing.
    (dotimes [_ 3] (api/recover-move-guest-processes! sys))
    (is (= :completed (pm-state sys "m1")))
    (is (= 1 (count (filter #(= :room-booked (:event/type %))
                            (es/read-stream (:event-store sys) "room-103"))))
        "exactly one :room-booked on the new room - no duplicates")
    (is (= 1 (count (filter #(= :booking-cancelled (:event/type %))
                            (es/read-stream (:event-store sys) "room-102"))))
        "exactly one :booking-cancelled on the old room")))

(deftest recovery-respects-MIN-AGE-ms-to-avoid-pre-empting-in-flight-steps
  (let [sys (system/test-system)
        clk (:clock sys)]
    (clock/set-time! clk 1000)
    (clock/set-uncertainty! clk 0)
    (api/book-room! sys {:room-id "102" :guest (:guest move-cmd)
                         :check-in "x" :check-out "y"})
    (inject-stuck-pm! sys move-cmd)
    ;; Only 1 ms after the event - the original dispatch could still
    ;; be in flight. With min-age-ms=10s the sweeper leaves it alone.
    (clock/set-time! clk 1001)
    (is (empty? (api/recover-move-guest-processes! sys 10000))
        "fresh stuck PM is left alone - we don't race the original")
    ;; jump the clock past the threshold and try again
    (clock/set-time! clk 50000)
    (is (= ["move-m1"] (api/recover-move-guest-processes! sys 10000))
        "now stale, sweeper picks it up")
    (is (= :completed (pm-state sys "m1")))))

(deftest recovery-leaves-terminal-PMs-alone
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "102" :guest (:guest move-cmd)
                         :check-in "x" :check-out "y"})
    (api/move-guest! sys move-cmd)
    (is (= :completed (pm-state sys "m1")) "PM ran normally")
    (let [events-before (es/read-all (:event-store sys))]
      (api/recover-move-guest-processes! sys)
      (is (= events-before (es/read-all (:event-store sys)))
          "recovery added NO events to a terminal PM"))))

(deftest recovery-also-handles-the-FAIL-path
  ;; If the inner book-room rejects (room already booked), the PM should
  ;; transition to :failed via the :on-failure continuation. Recovery
  ;; must follow that same path.
  (let [sys (system/test-system)]
    ;; both rooms already booked - book-room will reject
    (api/book-room! sys {:room-id "102" :guest (:guest move-cmd)
                         :check-in "x" :check-out "y"})
    (api/book-room! sys {:room-id "103" :guest {:name "Eve" :email "e@e.io"}
                         :check-in "x" :check-out "y"})
    (inject-stuck-pm! sys move-cmd)

    (api/recover-move-guest-processes! sys)

    (is (= :failed (pm-state sys "m1")) "recovery drove the PM down the failure path")
    (is (= ["101"] (api/available-rooms sys)))))
