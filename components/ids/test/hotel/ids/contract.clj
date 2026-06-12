(ns hotel.ids.contract
  (:require [clojure.test :refer [is testing]]
            [hotel.ids.interface :as ids]))

(defn verify-contract [fresh-ids]
  (testing "new-id returns UUIDs"
    (is (uuid? (ids/new-id (fresh-ids)))))
  (testing "successive new-id calls do not collide"
    (let [source (fresh-ids)]
      (is (not= (ids/new-id source) (ids/new-id source)))))
  (testing "name-id is stable for the same source and name"
    (let [source (fresh-ids)]
      (is (= (ids/name-id source "document/abc")
             (ids/name-id source "document/abc"))))))
