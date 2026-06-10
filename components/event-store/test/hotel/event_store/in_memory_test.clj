(ns hotel.event-store.in-memory-test
  (:require [clojure.test :refer [deftest]]
            [hotel.event-store.contract :as contract]
            [hotel.event-store.in-memory :as mem]))

(deftest in-memory-store-honours-the-event-store-contract
  (contract/verify-contract mem/create))
