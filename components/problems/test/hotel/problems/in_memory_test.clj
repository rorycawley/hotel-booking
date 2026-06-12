(ns hotel.problems.in-memory-test
  (:require [clojure.test :refer [deftest]]
            [hotel.problems.contract :as contract]
            [hotel.problems.in-memory :as mem]))

(deftest in-memory-sink-honours-the-contract
  (contract/verify-contract mem/create
                            (fn [sink] @(:problems sink))))
