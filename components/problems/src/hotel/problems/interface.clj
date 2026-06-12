(ns hotel.problems.interface
  "DRIVEN PORT for operational problem recording. The shell calls this
   when a post-commit effect fails or a process manager hits a state
   that needs human attention. Adapters: in-memory (test), stderr (prod)."
  (:require [hotel.problems.protocol :as p]
            [hotel.problems.in-memory :as in-memory]
            [hotel.problems.stderr :as stderr]))

(def record-problem! p/record-problem!)

(defn in-memory [] (in-memory/create))
(defn stderr    [] (stderr/create))
