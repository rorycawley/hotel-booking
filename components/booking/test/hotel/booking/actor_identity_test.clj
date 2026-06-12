(ns hotel.booking.actor-identity-test
  "Every accepted command carries an :actor-id, and every event the
   shell stamps preserves it. This is the audit-by-author property:
   you can ALWAYS answer 'who did this' for any row in the journal."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system]))

(deftest the-actor-id-on-the-command-is-stamped-on-every-event-it-produces
  (let [sys (system/test-system)
        actor "auth0|user-12345"]
    (api/book-room! sys {:room-id "101"
                         :guest {:name "Ada" :email "a@a.io"}
                         :check-in "x" :check-out "y"
                         :actor-id actor})
    (is (= [actor]
           (mapv :actor-id (es/read-stream (:event-store sys) "room-101"))))))

(deftest the-actor-id-propagates-through-process-managers
  ;; Audit-by-author MUST hold for an entire saga: the actor who started
  ;; a move is on EVERY event the PM produces and on every event of
  ;; rooms it touches. Otherwise compliance can't reconstruct who
  ;; authored a multi-step change.
  (let [sys (system/test-system)
        actor "auth0|filer-1"]
    (api/book-room! sys {:room-id "102" :guest {:name "X" :email "x@x"}
                         :check-in "x" :check-out "y" :actor-id actor})
    (api/move-guest! sys {:move-id "m1" :guest {:name "X" :email "x@x"}
                          :from-room "102" :to-room "103"
                          :check-in "x" :check-out "y"
                          :actor-id actor})
    (is (= [actor]
           (distinct (mapv :actor-id
                           (es/read-stream (:event-store sys) "move-m1"))))
        "every PM event carries the actor that started the move")
    (is (= [actor]
           (distinct (mapv :actor-id
                           (es/read-stream (:event-store sys) "room-103"))))
        "the new-room booking that the PM dispatched also carries it")))
