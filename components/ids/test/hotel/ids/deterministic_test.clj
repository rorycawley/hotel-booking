(ns hotel.ids.deterministic-test
  (:require [clojure.test :refer [deftest is]]
            [hotel.ids.contract :as contract]
            [hotel.ids.interface :as ids]))

(deftest deterministic-ids-honour-the-contract
  (contract/verify-contract ids/deterministic))

(deftest deterministic-sources-replay-the-same-sequence
  (let [a (ids/deterministic "replay")
        b (ids/deterministic "replay")]
    (is (= [(ids/new-id a) (ids/new-id a)]
           [(ids/new-id b) (ids/new-id b)]))))
