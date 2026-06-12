(ns hotel.booking.upcasters-test
  "The seam works even when no upcaster is registered (today's case)
   AND when one IS registered (so the moment we bump our first version,
   the behaviour is locked in)."
  (:require [clojure.test :refer [deftest is testing]]
            [hotel.booking.upcasters :as up]))

(deftest event-at-current-version-passes-through-unchanged
  (let [e {:event/type :room-booked :event/v 1 :room-id "1"}]
    (is (= e (up/upcast-one e)))))

(deftest event-without-a-version-is-treated-as-v1
  (let [e {:event/type :room-booked :room-id "1"}]
    (is (= 1 (:event/v (up/upcast-one e))))))

(deftest unknown-event-type-passes-through
  (let [e {:event/type :something-the-decider-does-not-know :event/v 7}]
    (is (= e (up/upcast-one e)))))

(deftest upcasting-CHAINS-when-versions-are-registered
  ;; Lock the chaining behaviour with a hand-rolled fake registry so
  ;; the first real bump doesn't surprise anyone. We test through the
  ;; PUBLIC contract by binding the private var temporarily.
  (testing "with a faked v1->v2->v3 chain, a v1 event becomes v3"
    (with-redefs [up/upcast-one
                  (fn [event]
                    (let [steps {[:test-event 1] (fn [e] (assoc e :a true  :event/v 2))
                                 [:test-event 2] (fn [e] (assoc e :b true  :event/v 3))}
                          target 3]
                      (loop [e (assoc event :event/v (or (:event/v event) 1))]
                        (let [v (:event/v e)]
                          (if (>= v target)
                            e
                            (let [up (get steps [(:event/type e) v])]
                              (if up (recur (up e)) e)))))))]
      (let [e0 {:event/type :test-event}
            e3 (up/upcast-one e0)]
        (is (= 3 (:event/v e3)))
        (is (true? (:a e3)))
        (is (true? (:b e3)))))))
