(ns hotel.clock.contract
  "One behavioural spec every Clock adapter must honour. The contract is
   small but critical: ordering and audit depend on intervals being
   WELL-FORMED ({:earliest, :latest} as longs, earliest <= latest).
   Both deterministic and system adapters must pass."
  (:require [clojure.test :refer [is testing]]
            [hotel.clock.interface :as clock]))

(defn verify-contract [fresh-clock]
  (testing "now-interval returns a map with :earliest and :latest longs"
    (let [i (clock/now-interval (fresh-clock))]
      (is (number? (:earliest i)))
      (is (number? (:latest i)))))

  (testing "intervals are well-formed: :earliest <= :latest"
    (let [c (fresh-clock)]
      (dotimes [_ 5]
        (let [{:keys [earliest latest]} (clock/now-interval c)]
          (is (<= earliest latest))))))

  (testing "intervals are MONOTONIC over a single clock instance"
    ;; Even with the uncertainty interval, :earliest may not move BACKWARDS
    ;; on the same source. (Wall-clock skew across processes is a separate
    ;; concern — ADR-0001 — and is exactly why ordering uses log position.)
    (let [c (fresh-clock)
          samples (repeatedly 5 #(clock/now-interval c))]
      (is (apply <= (map :earliest samples))
          "earliest never moves backwards"))))
