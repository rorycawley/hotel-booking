(ns hotel.problems.in-memory
  "TEST adapter: records problems to an atom so tests can assert what
   the seam emitted."
  (:require [hotel.problems.protocol :as p]))

(defrecord RecordingSink [problems]
  p/ProblemSink
  (record-problem! [_ problem]
    (swap! problems conj problem)))

(defn create [] (->RecordingSink (atom [])))
