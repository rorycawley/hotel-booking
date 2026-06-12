(ns hotel.problems.contract
  "One behavioural spec every ProblemSink adapter must honour."
  (:require [clojure.test :refer [is testing]]
            [hotel.problems.interface :as problems]))

(defn verify-contract
  "fresh-sink: 0-arg fn returning an EMPTY sink + 1-arg :read fn that
   returns the recorded problems. Provided per-adapter (atom snapshot,
   stderr capture, etc.)."
  [fresh-sink read-fn]
  (testing "record-problem! accepts a structured map with at minimum :problem"
    (let [sink (fresh-sink)]
      (problems/record-problem! sink {:problem :effect-failure
                                      :correlation-id "abc"})
      (let [recorded (read-fn sink)]
        (is (= 1 (count recorded)))
        (is (= :effect-failure (:problem (first recorded))))
        (is (= "abc" (:correlation-id (first recorded))))))))
