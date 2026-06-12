(ns hotel.document-store.in-memory
  "TEST adapter: blobs live in an atom keyed by blob-id."
  (:require [hotel.document-store.protocol :as p]))

(defrecord InMemoryDocumentStore [blobs]
  p/DocumentStore
  (put-blob! [_ {:keys [blob-id bytes content-type metadata]}]
    (swap! blobs assoc blob-id
           {:bytes        bytes
            :content-type content-type
            :metadata     (or metadata {})})
    {:blob-id blob-id})
  (get-blob [_ blob-id]
    (get @blobs blob-id))
  (head-blob [_ blob-id]
    (when-let [b (get @blobs blob-id)]
      (select-keys b [:content-type :metadata])))
  (delete-blob! [_ blob-id]
    (swap! blobs dissoc blob-id)))

(defn create [] (->InMemoryDocumentStore (atom {})))
