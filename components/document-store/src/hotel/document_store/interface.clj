(ns hotel.document-store.interface
  "DRIVEN PORT for opaque blob storage. Adapters: in-memory (test),
   minio (prod / S3-compatible).

   The port is intentionally crypto-agnostic. The booking shell layers
   envelope encryption on top with subject-scoped keys so destroying
   the subject's key crypto-shreds every one of their blobs."
  (:require [hotel.document-store.protocol :as p]
            [hotel.document-store.in-memory :as in-memory]
            [hotel.document-store.minio :as minio]))

(def put-blob!    p/put-blob!)
(def get-blob     p/get-blob)
(def head-blob    p/head-blob)
(def delete-blob! p/delete-blob!)

(defn in-memory [] (in-memory/create))
(defn minio     [config] (minio/create config))
