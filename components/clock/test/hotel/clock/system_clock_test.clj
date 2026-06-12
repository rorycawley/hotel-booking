(ns hotel.clock.system-clock-test
  (:require [clojure.test :refer [deftest]]
            [hotel.clock.contract :as contract]
            [hotel.clock.system-clock :as sys]))

(deftest system-clock-honours-the-contract
  (contract/verify-contract #(sys/create {:uncertainty-ms 5})))
