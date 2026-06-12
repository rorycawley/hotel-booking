(ns hotel.event-store.migrations
  "Tiny migration runner. No Flyway/Migratus dep: this scales to dozens
   of versioned files and is honest about what's happening.

   Convention: resources/event-store/migrations/V<n>__<name>.sql, applied
   in numeric order. Each file runs in ONE transaction. `schema_migrations`
   records applied versions so re-running is safe and idempotent.

   Concurrency: the runner takes a pg_advisory_xact_lock at the start, so
   two processes booting simultaneously cannot apply the same migration
   twice. The lock is released when the transaction ends."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc])
  (:import [java.io File]
           [java.net JarURLConnection]))

(def ^:private migrations-resource "event-store/migrations")
(def ^:private lock-key 7421431)             ; arbitrary const advisory-lock id

(defn- parse-version [filename]
  (when-let [[_ v] (re-find #"^V(\d+)__" filename)]
    (Long/parseLong v)))

(defn- list-from-dir [^File dir]
  (for [^File f (.listFiles dir)
        :when (str/ends-with? (.getName f) ".sql")
        :let [v (parse-version (.getName f))]
        :when v]
    {:version v :name (.getName f) :sql (slurp f)}))

(defn- list-from-jar [^java.net.URL url]
  (let [conn (.openConnection url)]
    (when (instance? JarURLConnection conn)
      (let [^JarURLConnection jconn conn
            jar    (.getJarFile jconn)
            prefix (str (.getEntryName jconn) "/")]
        (for [entry (enumeration-seq (.entries jar))
              :let [name (.getName entry)]
              :when (and (str/starts-with? name prefix)
                         (str/ends-with? name ".sql"))
              :let [base (subs name (count prefix))
                    v    (parse-version base)]
              :when v]
          {:version v
           :name    base
           :sql     (with-open [in (.getInputStream jar entry)] (slurp in))})))))

(defn- discover-migrations
  "Find all V<n>__*.sql resources on the classpath, ordered by version.
   Works for both filesystem (dev/REPL) and uberjar (prod)."
  []
  (let [url (io/resource migrations-resource)]
    (when-not url
      (throw (ex-info "No migrations resource on classpath"
                      {:resource migrations-resource})))
    (->> (case (.getProtocol url)
           "file" (list-from-dir (io/file url))
           "jar"  (list-from-jar url)
           (throw (ex-info "Unsupported migrations resource protocol"
                           {:url url})))
         (sort-by :version)
         vec)))

(defn- ensure-meta-table! [tx]
  (jdbc/execute!
   tx
   ["create table if not exists schema_migrations (
       version     bigint      primary key,
       name        text        not null,
       applied_at  timestamptz not null default now()
     )"]))

(defn- already-applied [tx]
  (->> (jdbc/execute! tx ["select version from schema_migrations"])
       (map :schema_migrations/version)
       set))

(defn apply-migrations!
  "Apply every migration with a higher version than what's already in
   schema_migrations. Returns the vec of applied {:version, :name}. Safe
   to call on every startup."
  [datasource]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx ["select pg_advisory_xact_lock(?)" lock-key])
    (ensure-meta-table! tx)
    (let [applied (already-applied tx)]
      (reduce
       (fn [acc {:keys [version name sql]}]
         (if (applied version)
           acc
           (do
             (jdbc/execute! tx [sql])
             (jdbc/execute! tx
                            ["insert into schema_migrations (version, name) values (?, ?)"
                             version name])
             (conj acc {:version version :name name}))))
       []
       (discover-migrations)))))
