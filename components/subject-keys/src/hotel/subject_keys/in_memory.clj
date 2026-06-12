(ns hotel.subject-keys.in-memory
  "TEST adapter: keys live in an atom. erase-subject! REPLACES the key
   with nil, mirroring the prod semantics where a KMS / HSM rotates the
   key out of existence."
  (:require [hotel.subject-keys.protocol :as p]
            [hotel.subject-keys.crypto :as crypto]))

(defrecord InMemorySubjectKeys [state] ; atom {subject-id -> key-bytes-or-nil}
  p/SubjectKeys
  (get-or-create-key [_ subject-id]
    (let [existing (get @state subject-id ::absent)]
      (cond
        (= existing ::absent)
        (let [k (crypto/new-key-bytes)]
          (swap! state (fn [s] (if (contains? s subject-id) s
                                   (assoc s subject-id k))))
          (get @state subject-id))
        :else existing)))                ; may be nil if erased
  (erase-subject! [_ subject-id]
    (swap! state assoc subject-id nil))
  (erased? [_ subject-id]
    (and (contains? @state subject-id)
         (nil? (get @state subject-id)))))

(defn create [] (->InMemorySubjectKeys (atom {})))
