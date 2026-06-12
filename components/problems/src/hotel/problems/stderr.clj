(ns hotel.problems.stderr
  "PRODUCTION default adapter: emits one JSON line per problem on stderr,
   timestamped, ready for log aggregators (CloudWatch, GCP Logging,
   Datadog, etc.). Easy to swap for a real sink later - the application
   only knows the port."
  (:require [cheshire.core :as json]
            [hotel.problems.protocol :as p]))

(defrecord StderrSink []
  p/ProblemSink
  (record-problem! [_ problem]
    (.println System/err
              (json/generate-string
               (assoc problem :ts (System/currentTimeMillis))))))

(defn create [] (->StderrSink))
