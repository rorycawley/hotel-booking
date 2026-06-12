(ns hotel.event-store.testcontainers
  "Adapter around the Testcontainers Java API. Each call returns a
   started container; callers run their test, then `.stop` it.

   `await-host-port!` exists because some Docker hosts (notably Rancher
   Desktop on macOS) install the host-side port-forwarding rule
   ASYNCHRONOUSLY - the container is healthy by Testcontainers' internal
   check, but the host can't reach the mapped port for a few hundred ms
   after .start() returns. Without this wait, tests get intermittent
   'Connection refused' on the very first JDBC/AMQP connect."
  (:import [java.net Socket]
           [org.testcontainers.containers
            ContainerLaunchException
            PostgreSQLContainer
            RabbitMQContainer]
           [org.testcontainers.utility DockerImageName]))

(defn- can-reach? [host port]
  (try (with-open [_ (Socket. ^String host (int port))] true)
       (catch Throwable _ false)))

(defn- await-host-port!
  "Block until `host:port` is TCP-reachable, up to timeout-ms. Throws
   ContainerLaunchException if it never opens up."
  [host port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (can-reach? host port) :ok
        (> (System/currentTimeMillis) deadline)
        (throw (ContainerLaunchException.
                (str "host could not reach " host ":" port
                     " within " timeout-ms "ms"
                     " (Docker host port-forwarding lag?)")))
        :else (do (Thread/sleep 100) (recur))))))

(defn start-postgres! ^PostgreSQLContainer []
  (let [pg (doto (PostgreSQLContainer.
                  (DockerImageName/parse "postgres:16.4-alpine"))
             (.withDatabaseName "booking")
             (.withUsername "booking")
             (.withPassword "booking")
             (.start))]
    (await-host-port! (.getHost pg) (.getFirstMappedPort pg) 10000)
    pg))

(defn jdbc-url
  "Build a jdbc URL with credentials. `getJdbcUrl` already contains a
   query string (loggerLevel=OFF) so we use & if needed."
  [^PostgreSQLContainer pg]
  (let [base (.getJdbcUrl pg)
        sep  (if (.contains base "?") "&" "?")]
    (str base sep "user=" (.getUsername pg) "&password=" (.getPassword pg))))

(defn start-rabbit! ^RabbitMQContainer []
  (let [rb (doto (RabbitMQContainer.
                  (DockerImageName/parse "rabbitmq:3.13.7-management-alpine"))
             (.start))]
    (await-host-port! (.getHost rb) (.getAmqpPort rb) 10000)
    rb))

(defn rabbit-uri [^RabbitMQContainer c]
  (.getAmqpUrl c))
