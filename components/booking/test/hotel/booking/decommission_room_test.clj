(ns hotel.booking.decommission-room-test
  "Behaviour: a room's life can END. The FSM has a designed terminal state."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.decider :as decider]
            [hotel.booking.room.fsm :as fsm]
            [hotel.booking.slices.decommission-room.decide :as decomm]))

(deftest an-available-room-can-be-decommissioned
  (is (= {:events [{:event/type :room-decommissioned :room-id "101"}]}
         (decider/decide decomm/decider [] {:room-id "101"}))))

(deftest a-room-with-a-guest-in-it-cannot-be-decommissioned
  (is (= {:error :room-still-booked}
         (decider/decide decomm/decider
                         [{:event/type :room-booked :room-id "101"}]
                         {:room-id "101"}))))

(deftest decommissioning-is-final
  ;; even the decommission decider itself is locked out afterwards
  (is (= {:error :stream-is-terminal}
         (decider/decide decomm/decider
                         [{:event/type :room-decommissioned :room-id "101"}]
                         {:room-id "101"}))))

(deftest the-fsm-says-when-a-stream-can-be-archived
  (is (false? (fsm/terminal? (fsm/evolve fsm/initial-state
                                         {:event/type :room-booked}))))
  (is (true?  (fsm/terminal? (fsm/evolve fsm/initial-state
                                         {:event/type :room-decommissioned})))))
