(ns hotel.booking.clock-quality-test
  "Decider/handle records clock anomalies via ProblemSink. Ordering is
   ALWAYS by log position (ADR-0001), so anomalies never fail the write;
   they are an evidence-quality signal, surfaced for ops."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.clock.interface :as clock]
            [hotel.system.interface :as system]))

(def cmd
  {:room-id "101" :guest {:name "Ada" :email "a@a.io"}
   :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest a-malformed-clock-interval-is-recorded-as-a-problem
  ;; A negative uncertainty inverts the interval (:earliest > :latest).
  ;; This is an INVARIANT violation we want to detect at runtime - the
  ;; write still succeeds (log position is the source of truth) but the
  ;; anomaly is recorded for ops.
  (let [sys (system/test-system)
        _   (clock/set-time! (:clock sys) 1000)
        _   (clock/set-uncertainty! (:clock sys) -50)
        result (api/book-room! sys cmd)]
    (is (:events result) "the write still succeeds despite a bad clock")
    (let [problems @(:problems (:problem-sink sys))]
      (is (some #(= :clock-interval-malformed (:problem %)) problems)
          "the malformed interval was recorded as a problem"))))

(deftest a-clock-that-went-backwards-vs-the-stream-is-recorded
  ;; Two writes against the same stream with a clock that JUMPED BACK
  ;; between them: the second write's whole interval precedes the first's.
  (let [sys (system/test-system)]
    (clock/set-time!        (:clock sys) 1000)
    (clock/set-uncertainty! (:clock sys) 0)
    (api/book-room! sys cmd)
    ;; turn the clock back to before the first event was recorded
    (clock/set-time! (:clock sys) 100)
    (api/cancel-booking! sys {:room-id "101"})
    (let [problems @(:problems (:problem-sink sys))]
      (is (some #(= :clock-went-backwards-vs-stream-history (:problem %))
                problems)
          "the backwards jump was recorded as a problem"))))

(deftest a-merely-OVERLAPPING-interval-is-NOT-flagged
  ;; ADR-0001: overlapping intervals are INCOMPARABLE by clock alone,
  ;; not anomalies. Only DEFINITELY-BEFORE (the strict precedence) is.
  (let [sys (system/test-system)]
    (clock/set-time!        (:clock sys) 1000)
    (clock/set-uncertainty! (:clock sys) 100)        ; widish interval
    (api/book-room! sys cmd)
    ;; new interval overlaps with the previous one - that's NORMAL
    (clock/set-time! (:clock sys) 1050)
    (api/cancel-booking! sys {:room-id "101"})
    (let [problems @(:problems (:problem-sink sys))]
      (is (not-any? #(= :clock-went-backwards-vs-stream-history (:problem %))
                    problems)
          "overlapping (not strictly backwards) intervals are fine"))))
