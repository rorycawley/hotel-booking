(ns hotel.clock.interface
  "DRIVEN PORT: time as an UNCERTAINTY INTERVAL {:earliest ms :latest ms},
   never a bare instant (see docs/adr/0001). Includes the pure interval
   algebra. Impls: deterministic (test), system-clock (prod)."
  (:require [hotel.clock.protocol :as protocol]
            [hotel.clock.deterministic :as deterministic]
            [hotel.clock.system-clock :as system-clock]))

(def now-interval protocol/now-interval)

;; ---- pure interval algebra ----
(defn definitely-before?
  "TRUE only when a's whole bound precedes b's: a court-safe claim."
  [a b]
  (< (:latest a) (:earliest b)))

(defn overlapping?
  "Overlapping bounds are temporally INCOMPARABLE by clock alone."
  [a b]
  (not (or (definitely-before? a b)
           (definitely-before? b a))))

;; ---- impls ----
(defn deterministic
  ([] (deterministic/create))
  ([init] (deterministic/create init)))
(defn system-clock [{:keys [uncertainty-ms] :as config}] (system-clock/create config))

;; ---- deterministic-clock test helpers ----
(def set-time!        deterministic/set-time!)
(def advance!         deterministic/advance!)
(def set-uncertainty! deterministic/set-uncertainty!)
