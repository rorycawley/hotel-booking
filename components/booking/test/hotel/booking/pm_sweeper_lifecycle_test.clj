(ns hotel.booking.pm-sweeper-lifecycle-test
  "PmSweeper Component honours the same auto-start? switch as the
   outbox relay and projector: wired in both flavours, started only
   in prod, drained-by-call in tests."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [hotel.system.interface :as system]))

(deftest in-memory-system-does-NOT-start-the-pm-sweeper-thread
  (let [sys (component/start (system/in-memory-system))]
    (try
      (is (nil? (:thread (:pm-sweeper sys)))
          "no background thread - tests drive recovery via the function")
      (finally (component/stop sys)))))
