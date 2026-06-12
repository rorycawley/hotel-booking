(ns hotel.ids.random-test
  (:require [clojure.test :refer [deftest]]
            [hotel.ids.contract :as contract]
            [hotel.ids.interface :as ids]))

(deftest random-ids-honour-the-contract
  (contract/verify-contract ids/random))
