(ns hotel.document-store.unreachable-test
  "When MinIO (or any S3-compatible store) is unreachable, the adapter
   surfaces the error - never silently swallows a put. The application's
   upload flow depends on this: a thrown put-blob! aborts the event
   commit path, so we never end up with an event referencing a blob
   that does not exist in storage."
  (:require [clojure.test :refer [deftest is]])
  (:import [io.minio MinioClient BucketExistsArgs]
           [java.util.concurrent TimeUnit]
           [okhttp3 OkHttpClient$Builder]))

(deftest ^:integration the-minio-client-throws-when-endpoint-is-unreachable
  ;; NETWORK test: marked ^:integration. We give the underlying OkHttp
  ;; client a 1-second connect timeout (instead of the JDK default of
  ;; ~60s) so the assertion completes in ~1s. A misconfigured prod
  ;; endpoint will still fail at start through `ensure-bucket!`; this
  ;; test pins the LOUD-failure semantic of that call without paying
  ;; the default timeout cost.
  (let [http (-> (OkHttpClient$Builder.)
                 (.connectTimeout 1 TimeUnit/SECONDS)
                 (.readTimeout    1 TimeUnit/SECONDS)
                 (.writeTimeout   1 TimeUnit/SECONDS)
                 (.build))
        client (-> (MinioClient/builder)
                   (.endpoint "http://127.0.0.1:1")
                   (.credentials "x" "yyyyyyyy")
                   (.httpClient http)
                   (.build))]
    (is (thrown? Exception
                 (.bucketExists client
                                (-> (BucketExistsArgs/builder)
                                    (.bucket "test")
                                    (.build))))
        "talking to an unreachable endpoint must throw, never silently report success")))
