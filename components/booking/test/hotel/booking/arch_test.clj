(ns hotel.booking.arch-test
  "Architecture fitness tests — guard the CLAUDE.md invariants that
   `clojure -M:poly check` can't see. Nothing under test is executed;
   each rule is a static scan of source files in the booking brick.

   Covered:
   1. Pure core (invariant #2): decide / react / projection / process /
      fsm / events may not :require a port interface or low-level I/O,
      AND may not call clock / randomness statics inline.
   2. Port-touching allowlist (invariant #2 corollary): only effects.clj,
      decider.clj, and slices/*/query.clj may :require a port interface.
   3. Contract gate stays wired (invariant #7): effects.clj must still
      validate publish topic + payload against hotel.booking.contracts —
      that runtime check is what actually keeps domain events from
      leaking; without it, the contract scheme is theater."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private booking-src "components/booking/src/hotel/booking")

(defn- clj-files []
  (->> (file-seq (io/file booking-src))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))

(defn- ns-form [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false] (read r))))

(defn- required-nses
  "Set of namespace symbols (as strings) that `file` :requires."
  [file]
  (->> (ns-form file)
       (filter seq?)
       (filter #(= :require (first %)))
       first
       rest
       (keep #(cond
                (symbol? %)     %
                (sequential? %) (first %)))
       (map str)
       set))

(defn- rel [file]
  (-> (.getPath file)
      (str/replace (str booking-src "/") "")))

(defn- matches-any? [patterns path]
  (some #(re-find % path) patterns))

;; ---------------------------------------------------------------------------
;; 1. Pure core
;; ---------------------------------------------------------------------------

(def ^:private pure-patterns
  [#"^slices/[^/]+/decide\.clj$"
   #"^slices/[^/]+/react\.clj$"
   #"^slices/[^/]+/projection\.clj$"
   #"^slices/[^/]+/process\.clj$"
   #"^room/fsm\.clj$"
   #"^room/events\.clj$"])

(def ^:private port-interfaces
  #{"hotel.event-store.interface"
    "hotel.clock.interface"
    "hotel.notifications.interface"
    "hotel.integration.interface"
    "hotel.problems.interface"
    "hotel.subject-keys.interface"
    "hotel.document-store.interface"
    "hotel.ids.interface"})

(def ^:private core-forbidden
  (into port-interfaces #{"clojure.java.io" "java.io" "java.net"}))

(def ^:private forbidden-call-patterns
  "Inline calls that bypass a port or introduce nondeterminism. A
   :require check can't see these — FQN calls and core fns need no
   import — so the pure-core rule mirrors itself in the file body."
  [#"\bInstant/now\b"
   #"\bLocalDate/now\b"
   #"\bLocalDateTime/now\b"
   #"\bZonedDateTime/now\b"
   #"\bSystem/currentTimeMillis\b"
   #"\bSystem/nanoTime\b"
   #"\bMath/random\b"
   #"\((?:rand|rand-int|rand-nth)[\s)]"
   #"\bhotel\.(?:event-store|clock|notifications|integration|problems|subject-keys|document-store|ids)\.interface/"])

(defn- forbidden-calls [file]
  (let [src (slurp file)]
    (keep #(re-find % src) forbidden-call-patterns)))

(deftest core-stays-pure
  (testing "decide / react / projection / process / fsm / events: no port or I/O imports"
    (doseq [f (clj-files) :when (matches-any? pure-patterns (rel f))]
      (let [bad (filter core-forbidden (required-nses f))]
        (is (empty? bad)
            (str (rel f) " imports " (vec bad)
                 " — the core must be pure (CLAUDE.md invariant #2).")))))
  (testing "core files contain no inline clock / randomness calls"
    (doseq [f (clj-files) :when (matches-any? pure-patterns (rel f))]
      (let [bad (forbidden-calls f)]
        (is (empty? bad)
            (str (rel f) " contains forbidden inline call(s) " (vec bad)
                 " — clock goes through hotel.clock.interface; "
                 "randomness has no place in the core."))))))

;; ---------------------------------------------------------------------------
;; 2. Port-touching allowlist
;; ---------------------------------------------------------------------------

(def ^:private port-allowed-names
  ;; effects.clj: post-commit effect interpreter
  ;; decider.clj: the single impure shell of every state-changing slice
  ;; recovery.clj: the shell for the PM-recovery sweep
  ;; pii.clj:     the envelope-encrypt seam for PII fields
  #{"effects.clj" "decider.clj" "recovery.clj" "pii.clj"})

(defn- port-allowed? [path]
  (or (port-allowed-names (.getName (io/file path)))
      (re-find #"^slices/[^/]+/query\.clj$" path)
      ;; documents/handler.clj is a shell for blob storage + envelope
      ;; encryption - same allow-list rationale as decider.clj.
      (= "slices/documents/handler.clj" path)))

(deftest only-seams-touch-ports
  (testing "only effects.clj, decider.clj, and slices/*/query.clj may import a port"
    (doseq [f (clj-files)
            :let [imported (filter port-interfaces (required-nses f))]
            :when (seq imported)]
      (is (port-allowed? (rel f))
          (str (rel f) " imports port " (vec imported)
               " — only effects.clj, decider.clj, and query.clj may "
               "(CLAUDE.md invariant #2).")))))

;; ---------------------------------------------------------------------------
;; 3. Contract gate stays wired
;; ---------------------------------------------------------------------------

(deftest effects-gate-stays-wired
  (testing "effects.clj still validates publishes against hotel.booking.contracts"
    (let [src (slurp (io/file booking-src "effects.clj"))]
      (is (re-find #"contracts/known-topic\?" src)
          "effects.clj must call contracts/known-topic? to gate the publish topic.")
      (is (re-find #"contracts/valid\?" src)
          (str "effects.clj must call contracts/valid? on the publish payload — "
               "this runtime check is what enforces invariant #7. "
               "Without it the contract scheme is theater.")))))
