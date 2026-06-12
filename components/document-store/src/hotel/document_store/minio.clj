(ns hotel.document-store.minio
  "PRODUCTION adapter: MinIO (and any S3-compatible: AWS S3, Cloudflare
   R2, Backblaze B2). One bucket per install; the blob-id becomes the
   object key. Object metadata stores small per-document state - we use
   it for the envelope-wrapped per-document encryption key.

   The bucket is auto-created on start if it does not yet exist. For
   production, prefer pre-creating the bucket with the right lifecycle
   rules, versioning, and KMS settings - this start-up create is
   convenience, not policy."
  (:require [com.stuartsierra.component :as component]
            [hotel.document-store.protocol :as p])
  (:import [io.minio MinioClient PutObjectArgs GetObjectArgs StatObjectArgs
            RemoveObjectArgs BucketExistsArgs MakeBucketArgs]
           [java.io ByteArrayInputStream]))

(defn- build-client ^MinioClient [{:keys [endpoint access-key secret-key]}]
  (-> (MinioClient/builder)
      (.endpoint ^String endpoint)
      (.credentials ^String access-key ^String secret-key)
      (.build)))

(defn- ensure-bucket! [^MinioClient client ^String bucket]
  (when-not (.bucketExists client
                           (-> (BucketExistsArgs/builder) (.bucket bucket) (.build)))
    (.makeBucket client
                 (-> (MakeBucketArgs/builder) (.bucket bucket) (.build)))))

(defn- ->str-map ^java.util.Map [m]
  (let [hm (java.util.HashMap.)]
    (doseq [[k v] m] (.put hm (str k) (str v)))
    hm))

(defrecord MinioDocumentStore [endpoint access-key secret-key bucket client]
  component/Lifecycle
  (start [this]
    (if client
      this
      (let [c (build-client {:endpoint endpoint
                             :access-key access-key
                             :secret-key secret-key})]
        (ensure-bucket! c bucket)
        (assoc this :client c))))
  (stop [this]
    (when client (.close ^MinioClient client))
    (assoc this :client nil))

  p/DocumentStore
  (put-blob! [_ {:keys [blob-id bytes content-type metadata]}]
    (with-open [in (ByteArrayInputStream. bytes)]
      (.putObject ^MinioClient client
                  (-> (PutObjectArgs/builder)
                      (.bucket bucket)
                      (.object ^String blob-id)
                      (.contentType ^String (or content-type "application/octet-stream"))
                      (.userMetadata (->str-map (or metadata {})))
                      (.stream in (long (alength ^bytes bytes)) -1)
                      (.build)))
      {:blob-id blob-id}))
  (get-blob [_ blob-id]
    (try
      (let [stat (.statObject client
                              (-> (StatObjectArgs/builder)
                                  (.bucket bucket) (.object ^String blob-id) (.build)))]
        (with-open [in (.getObject client
                                   (-> (GetObjectArgs/builder)
                                       (.bucket bucket) (.object ^String blob-id) (.build)))]
          {:bytes        (.readAllBytes in)
           :content-type (.contentType stat)
           :metadata     (into {} (.userMetadata stat))}))
      (catch Exception _ nil)))
  (head-blob [_ blob-id]
    (try
      (let [stat (.statObject client
                              (-> (StatObjectArgs/builder)
                                  (.bucket bucket) (.object ^String blob-id) (.build)))]
        {:content-type (.contentType stat)
         :metadata     (into {} (.userMetadata stat))})
      (catch Exception _ nil)))
  (delete-blob! [_ blob-id]
    (.removeObject ^MinioClient client
                   (-> (RemoveObjectArgs/builder)
                       (.bucket bucket) (.object ^String blob-id) (.build)))))

(defn create
  "config: {:endpoint :access-key :secret-key :bucket}"
  [config]
  (map->MinioDocumentStore config))
