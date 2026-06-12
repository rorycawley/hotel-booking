(ns hotel.clock.dynamic-uncertainty-test
  "The prod clock can read its max-error LIVE from a sync-status
   source (chrony, PTP, ClockBound). When the live value crosses a
   threshold we want to KNOW about it - degraded sync = degraded
   evidence quality on the journal."
  (:require [clojure.test :refer [deftest is]]
            [hotel.clock.interface :as clock]
            [hotel.clock.system-clock :as sys-clock]))

(deftest a-live-uncertainty-fn-replaces-the-static-value
  (let [live   (atom 7)
        c      (sys-clock/create {:uncertainty-ms 5
                                  :uncertainty-fn (fn [] @live)})
        i1     (clock/now-interval c)
        _      (reset! live 42)
        i2     (clock/now-interval c)]
    (is (= 14 (- (:latest i1) (:earliest i1)))
        "first call uses the live 7ms => 14ms span")
    (is (= 84 (- (:latest i2) (:earliest i2)))
        "second call sees the new live 42ms")))

(deftest the-static-value-is-used-when-the-fn-throws-or-returns-nil
  (let [c (sys-clock/create {:uncertainty-ms 9
                             :uncertainty-fn (fn [] (throw (RuntimeException. "down")))})
        i (clock/now-interval c)]
    (is (= 18 (- (:latest i) (:earliest i))) "fallback static is honoured")))

(deftest crossing-the-degraded-threshold-fires-the-callback
  (let [calls    (atom [])
        c        (sys-clock/create
                  {:uncertainty-ms 5
                   :uncertainty-fn (fn [] 250)
                   :degraded-threshold-ms 100
                   :on-degraded #(swap! calls conj %)})]
    (clock/now-interval c)
    (clock/now-interval c)
    (is (= 2 (count @calls)) "callback fires every now-interval over threshold")
    (is (= 250 (-> @calls first :uncertainty-ms)))
    (is (= 100 (-> @calls first :threshold-ms)))))
