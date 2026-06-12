(ns hotel.document-store.minio-test
  "Integration: the MinIO adapter satisfies the same contract the
   in-memory one does. ^:integration -> Testcontainers boots a real
   MinIO."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [hotel.document-store.contract :as contract]
            [hotel.document-store.minio :as minio])
  (:import [java.net Socket]
           [org.testcontainers.containers MinIOContainer]
           [org.testcontainers.utility DockerImageName]))

(def ^:dynamic *minio* nil)

(defn- await-host-port! [^String host port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (try (with-open [_ (Socket. host (int port))] true)
             (catch Throwable _ false)) :ok
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "MinIO host port not reachable" {:host host :port port}))
        :else (do (Thread/sleep 100) (recur))))))

(defn- start-minio! ^MinIOContainer []
  ;; MinIO requires the root password to be >= 8 chars; this is the
  ;; common pitfall when adopting MinIOContainer.
  (let [c (doto (MinIOContainer.
                 (DockerImageName/parse "minio/minio:RELEASE.2024-08-17T01-24-54Z"))
            (.withUserName "minioadmin")
            (.withPassword "minioadmin123")
            (.start))]
    (await-host-port! (.getHost c) (.getFirstMappedPort c) 10000)
    c))

(defn- with-minio [f]
  (let [c (start-minio!)]
    (try (binding [*minio* c] (f))
         (finally (.stop c)))))

(use-fixtures :once with-minio)

(deftest ^:integration minio-adapter-honours-the-document-store-contract
  (let [endpoint (str "http://" (.getHost ^MinIOContainer *minio*)
                      ":" (.getFirstMappedPort ^MinIOContainer *minio*))
        started-stores (atom [])
        fresh-store (fn []
                      (let [store (component/start
                                   (minio/create
                                    {:endpoint endpoint
                                     :access-key "minioadmin"
                                     :secret-key "minioadmin123"
                                     :bucket (str "test-" (System/currentTimeMillis))}))]
                        (swap! started-stores conj store)
                        store))]
    (try
      (contract/verify-contract fresh-store)
      (is true)
      (finally
        (doseq [store @started-stores]
          (component/stop store))))))
