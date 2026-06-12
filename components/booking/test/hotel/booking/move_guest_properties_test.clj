(ns hotel.booking.move-guest-properties-test
  "Property-based tests for the move-guest PM. The saga has four
   in-flight states (booking-new-room, cancelling-old-room,
   compensating, plus the implicit 'not-started') and reaches one of
   two terminal states (completed / failed). These properties lock the
   shape of the FSM so a future change to the protocol must update
   the spec, not silently break it."
  (:require [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hotel.booking.slices.move-guest.process :as pm]))

;; ---- generators --------------------------------------------------------

(def guest-gen
  (gen/hash-map :name  gen/string-alphanumeric
                :email (gen/fmap #(str % "@example.com") gen/string-alphanumeric)))

(def request-move-cmd-gen
  (gen/let [move-id    gen/string-alphanumeric
            from-room  gen/string-alphanumeric
            to-room    gen/string-alphanumeric
            guest      guest-gen
            check-in   gen/string-alphanumeric
            check-out  gen/string-alphanumeric]
    {:command/type :request-move
     :move-id   move-id
     :from-room from-room
     :to-room   to-room
     :guest     guest
     :check-in  check-in
     :check-out check-out}))

;; happy path: request -> book ok -> cancel old ok -> done
;; failure path: request -> book FAIL -> done
;; compensation path: request -> book ok -> cancel old FAIL -> compensate -> done
(def step-sequence-gen
  (gen/elements [[:record-new-room-booked :record-old-room-cancelled]
                 [:record-new-room-failed]
                 [:record-new-room-booked :record-old-room-failed :record-compensated]]))

(defn- apply-events
  "Push a sequence of events through the PM's evolve."
  [events]
  (reduce pm/evolve pm/initial-state events))

(defn- step-event-for
  "Synthesise the event a record-step command would emit when the PM is
   in the right awaiting state. The events carry the keys the evolve
   function consumes."
  [command-type request-event]
  (case command-type
    :record-new-room-booked
    {:event/type :move-new-room-booked
     :move-id    (:move-id request-event)
     :from-room  (:from-room request-event)
     :guest      (:guest request-event)}

    :record-new-room-failed
    {:event/type :move-new-room-failed
     :move-id    (:move-id request-event)
     :reason     :downstream-rejected}

    :record-old-room-cancelled
    {:event/type :move-old-room-cancelled
     :move-id    (:move-id request-event)}

    :record-old-room-failed
    {:event/type :move-old-room-failed
     :move-id    (:move-id request-event)
     :to-room    (:to-room request-event)
     :guest      (:guest request-event)
     :reason     :downstream-rejected}

    :record-compensated
    {:event/type :move-compensated
     :move-id    (:move-id request-event)}))

;; ---- properties --------------------------------------------------------

(defspec a-fresh-pm-starts-not-started 100
  (prop/for-all [_ (gen/return nil)]
                (= :not-started (:status pm/initial-state))))

(defspec request-move-from-not-started-emits-move-requested 100
  (prop/for-all [cmd request-move-cmd-gen]
                (let [result ((:decide pm/decider) pm/initial-state cmd)]
                  (and (vector? (:events result))
                       (= :move-requested (-> result :events first :event/type))))))

(defspec a-pm-that-completes-the-happy-path-reaches-:completed-and-is-terminal 100
  (prop/for-all [request-cmd request-move-cmd-gen]
                (let [request-event (-> ((:decide pm/decider) pm/initial-state request-cmd)
                                        :events first)
                      events [request-event
                              (step-event-for :record-new-room-booked request-event)
                              (step-event-for :record-old-room-cancelled request-event)]
                      final (apply-events events)]
                  (and (= :completed (:status final))
                       (pm/terminal? final)))))

(defspec a-pm-whose-first-step-fails-reaches-:failed-and-is-terminal 100
  (prop/for-all [request-cmd request-move-cmd-gen]
                (let [request-event (-> ((:decide pm/decider) pm/initial-state request-cmd)
                                        :events first)
                      events [request-event
                              (step-event-for :record-new-room-failed request-event)]
                      final (apply-events events)]
                  (and (= :failed (:status final))
                       (pm/terminal? final)))))

(defspec compensation-always-reaches-:failed-terminal 100
  (prop/for-all [request-cmd request-move-cmd-gen]
                (let [request-event (-> ((:decide pm/decider) pm/initial-state request-cmd)
                                        :events first)
                      events [request-event
                              (step-event-for :record-new-room-booked request-event)
                              (step-event-for :record-old-room-failed request-event)
                              (step-event-for :record-compensated request-event)]
                      final (apply-events events)]
                  (and (= :failed (:status final))
                       (pm/terminal? final)))))

(defspec every-canonical-PM-run-reaches-a-terminal-state 100
  (prop/for-all [request-cmd request-move-cmd-gen
                 step-types  step-sequence-gen]
                (let [request-event (-> ((:decide pm/decider) pm/initial-state request-cmd)
                                        :events first)
                      step-events   (map #(step-event-for % request-event) step-types)
                      events        (cons request-event step-events)
                      final         (apply-events events)]
                  (is (pm/terminal? final)
                      "every canonical step sequence reaches a terminal state"))))

(defspec a-step-result-in-the-WRONG-awaiting-state-is-rejected 100
  ;; The decider has step-precondition guards. A :record-new-room-booked
  ;; while we are in :cancelling-old-room (i.e. that step already came
  ;; back) should be :unexpected-step-result. Generate any unexpected
  ;; combination and confirm.
  (prop/for-all [request-cmd request-move-cmd-gen]
                (let [request-event (-> ((:decide pm/decider) pm/initial-state request-cmd)
                                        :events first)
                      after-book-ok (apply-events [request-event
                                                   (step-event-for :record-new-room-booked request-event)])
          ;; we are now :cancelling-old-room; another :record-new-room-booked
          ;; is an unexpected step result
                      result ((:decide pm/decider) after-book-ok
                                                   {:command/type :record-new-room-booked
                                                    :move-id (:move-id request-cmd)})]
                  (= :unexpected-step-result (:error result)))))
