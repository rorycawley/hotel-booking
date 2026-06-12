(ns hotel.document-store.contract
  "One behavioural spec every DocumentStore adapter must honour.
   In-memory runs in the fast suite; MinIO via Testcontainers runs in
   the integration suite. Same contract proves the hexagon."
  (:require [clojure.test :refer [is testing]]
            [hotel.document-store.interface :as ds]))

(defn- ->bytes [s] (.getBytes ^String s "UTF-8"))
(defn- ->str   [^bytes b] (String. b "UTF-8"))

(defn verify-contract
  "fresh-store: 0-arg fn returning an EMPTY store."
  [fresh-store]
  (testing "an unknown blob-id reads as nil"
    (is (nil? (ds/get-blob (fresh-store) "no-such-id"))))

  (testing "put + get round-trips the exact bytes"
    (let [store (fresh-store)
          payload (->bytes "hello world")]
      (ds/put-blob! store {:blob-id "doc-1" :bytes payload
                           :content-type "text/plain"
                           :metadata {"x" "y"}})
      (let [back (ds/get-blob store "doc-1")]
        (is (= "hello world" (->str (:bytes back))))
        (is (= "text/plain" (:content-type back)))
        (is (= "y" (get (:metadata back) "x"))))))

  (testing "head-blob returns metadata WITHOUT transferring the bytes"
    (let [store (fresh-store)]
      (ds/put-blob! store {:blob-id "doc-1" :bytes (->bytes "x")
                           :content-type "text/plain"
                           :metadata {"k" "v"}})
      (let [head (ds/head-blob store "doc-1")]
        (is (= "text/plain" (:content-type head)))
        (is (= "v" (get (:metadata head) "k")))
        (is (not (contains? head :bytes))))))

  (testing "put is idempotent on blob-id - re-puts overwrite"
    (let [store (fresh-store)]
      (ds/put-blob! store {:blob-id "doc-1" :bytes (->bytes "v1")
                           :content-type "text/plain"})
      (ds/put-blob! store {:blob-id "doc-1" :bytes (->bytes "v2")
                           :content-type "text/plain"})
      (is (= "v2" (->str (:bytes (ds/get-blob store "doc-1")))))))

  (testing "delete-blob! removes it"
    (let [store (fresh-store)]
      (ds/put-blob! store {:blob-id "doc-1" :bytes (->bytes "x")
                           :content-type "text/plain"})
      (ds/delete-blob! store "doc-1")
      (is (nil? (ds/get-blob store "doc-1"))))))
