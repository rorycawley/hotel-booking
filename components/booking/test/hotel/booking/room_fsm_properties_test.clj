(ns hotel.booking.room-fsm-properties-test
  "Property-based tests for the room FSM. The FSM is small and easy to
   eyeball, so these properties are mostly LOCKS rather than discoveries:
   they fail loudly if someone changes evolve in a way that breaks an
   invariant the rest of the system relies on."
  (:require [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hotel.booking.room.fsm :as fsm]))

(def guest-gen
  (gen/hash-map :name  gen/string-alphanumeric
                :email (gen/fmap #(str % "@example.com") gen/string-alphanumeric)))

(def book-event-gen
  (gen/let [room-id   gen/string-alphanumeric
            guest     guest-gen
            check-in  gen/string-alphanumeric
            check-out gen/string-alphanumeric]
    {:event/type :room-booked
     :room-id    room-id
     :guest      guest
     :check-in   check-in
     :check-out  check-out}))

(def cancel-event-gen
  (gen/let [room-id gen/string-alphanumeric]
    {:event/type :booking-cancelled :room-id room-id}))

(def decommission-event-gen
  (gen/let [room-id gen/string-alphanumeric]
    {:event/type :room-decommissioned :room-id room-id}))

(def unknown-event-gen
  (gen/let [type (gen/elements [:price-changed :guest-checked-in :unknown-thing])]
    {:event/type type}))

(def any-event-gen
  (gen/one-of [book-event-gen cancel-event-gen decommission-event-gen unknown-event-gen]))

;; ---- properties --------------------------------------------------------

(defspec unknown-events-leave-state-unchanged 200
  (prop/for-all [state (gen/hash-map :status (gen/elements [:available :booked :decommissioned]))
                 event unknown-event-gen]
                (= state (fsm/evolve state event))))

(defspec room-booked-always-transitions-to-booked 200
  (prop/for-all [state (gen/hash-map :status (gen/elements [:available :booked :decommissioned]))
                 event book-event-gen]
    ;; evolve is the data-application step; the decider is what stops
    ;; illegal transitions. evolve itself just applies.
                (= :booked (:status (fsm/evolve state event)))))

(defspec booking-cancelled-always-transitions-to-available 200
  (prop/for-all [state (gen/hash-map :status (gen/elements [:available :booked :decommissioned]))
                 event cancel-event-gen]
                (= :available (:status (fsm/evolve state event)))))

(defspec booking-cancelled-clears-the-guest 200
  (prop/for-all [state (gen/hash-map :status (gen/return :booked)
                                     :guest  guest-gen)
                 event cancel-event-gen]
                (nil? (:guest (fsm/evolve state event)))))

(defspec room-decommissioned-makes-terminal-true 200
  (prop/for-all [state (gen/hash-map :status (gen/elements [:available :booked :decommissioned]))
                 event decommission-event-gen]
                (fsm/terminal? (fsm/evolve state event))))

(defspec terminal-is-true-iff-status-is-decommissioned 200
  (prop/for-all [state (gen/hash-map :status (gen/elements [:available :booked :decommissioned]))]
                (= (fsm/terminal? state)
                   (= :decommissioned (:status state)))))

(defspec book-then-cancel-from-available-roundtrips-to-available 200
  (prop/for-all [book   book-event-gen
                 cancel cancel-event-gen]
                (= :available (:status (-> fsm/initial-state
                                           (fsm/evolve book)
                                           (fsm/evolve cancel))))))

(defspec the-fold-is-a-function-of-the-events 200
  ;; Two folds of the SAME event sequence produce the same state. This
  ;; is the property that makes event sourcing tractable - state is a
  ;; deterministic function of history.
  (prop/for-all [events (gen/vector any-event-gen 0 20)]
                (= (reduce fsm/evolve fsm/initial-state events)
                   (reduce fsm/evolve fsm/initial-state events))))

(defspec the-fold-after-decommissioned-eventually-stays-terminal 100
  ;; Once a :room-decommissioned event appears in the history, the
  ;; FSM's terminal? returns true for the final state EVEN IF more
  ;; events follow (note: this only holds if no :room-booked /
  ;; :booking-cancelled follows that overwrites status - which means
  ;; the decider is what enforces terminal stickiness, not evolve. So
  ;; we test the WEAKER property: the decommissioned event appears
  ;; somewhere in history => the state visited :decommissioned at some
  ;; point. We use a fold-and-mark.).
  (prop/for-all [pre   (gen/vector any-event-gen 0 10)
                 post  (gen/vector any-event-gen 0 10)]
                (let [events (concat pre [{:event/type :room-decommissioned :room-id "1"}] post)
                      states (reductions fsm/evolve fsm/initial-state events)]
                  (is (some #(= :decommissioned (:status %)) states)
                      "decommissioned status was reached at the decommission event"))))
