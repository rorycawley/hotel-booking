(ns hotel.document-store.in-memory-test
  (:require [clojure.test :refer [deftest]]
            [hotel.document-store.contract :as contract]
            [hotel.document-store.in-memory :as mem]))

(deftest in-memory-store-honours-the-contract
  (contract/verify-contract mem/create))
