(ns hotel.clock.deterministic-test
  (:require [clojure.test :refer [deftest]]
            [hotel.clock.contract :as contract]
            [hotel.clock.deterministic :as det]))

(deftest deterministic-clock-honours-the-contract
  (contract/verify-contract det/create))
