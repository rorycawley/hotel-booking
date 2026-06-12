(ns hotel.booking.approve-decommission-test
  "Multi-party approval pattern: a sensitive change (decommission a
   room) requires N approvals from DISTINCT actors. The same pattern
   shape that a registry uses for dissolution, change of registered
   office, director removal, etc."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.decider :as decider]
            [hotel.booking.interface :as api]
            [hotel.booking.slices.approve-decommission.decide :as approve]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system]))

(def request-cmd
  {:decommission-id "d1"
   :room-id "101"
   :required-approvals 2
   :actor-id "manager-alice"})

(defn- workflow-state [sys decommission-id]
  (decider/current-state approve/decider
                         (es/read-stream (:event-store sys)
                                         (str "decommission-" decommission-id))))

(deftest two-distinct-approvers-execute-the-decommission
  (let [sys (system/test-system)]
    (api/request-decommission! sys request-cmd)
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-bob"})
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "after only ONE approval, 101 is still bookable (not decommissioned)")
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-carol"})
    (is (= ["102" "103"] (api/available-rooms sys))
        "after the SECOND approval, the decommission FIRED and 101 is gone")))

(deftest the-requester-CANNOT-self-approve
  (let [sys (system/test-system)
        _   (api/request-decommission! sys request-cmd)
        r   (api/approve-decommission! sys {:decommission-id "d1"
                                            :actor-id "manager-alice"})]
    (is (= :requester-cannot-self-approve (:error r))
        "separation of duties: the requester is not a valid approver")))

(deftest the-same-actor-cannot-approve-TWICE
  (let [sys (system/test-system)
        _   (api/request-decommission! sys request-cmd)
        _   (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-bob"})
        r2  (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-bob"})]
    (is (= :actor-already-approved (:error r2))
        "duplicate approvals from one actor are rejected")))

(deftest rejection-aborts-the-workflow-no-decommission-happens
  (let [sys (system/test-system)]
    (api/request-decommission! sys request-cmd)
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-bob"})
    (api/reject-decommission!  sys {:decommission-id "d1"
                                    :actor-id "manager-carol"
                                    :reason   "legal hold pending"})
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "the room is still bookable - the workflow was rejected")
    ;; Further approvals after rejection are no-ops (PM is terminal)
    (let [r (api/approve-decommission! sys {:decommission-id "d1"
                                            :actor-id "manager-dave"})]
      (is (= :stream-is-terminal (:error r))))))

(deftest downstream-decommission-rejection-marks-the-workflow-rejected
  (let [sys (system/test-system)]
    (api/book-room! sys {:room-id "101"
                         :guest {:name "Ada" :email "ada@example.com"}
                         :check-in "x" :check-out "y"})
    (api/request-decommission! sys (assoc request-cmd :required-approvals 1))
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-bob"})
    (let [state (workflow-state sys "d1")]
      (is (= :rejected (:status state)))
      (is (= :room-still-booked (:reject-reason state))))
    (is (not-any? #(= :room-decommissioned (:event/type %))
                  (es/read-stream (:event-store sys) "room-101"))
        "the booked room was not decommissioned")))

(deftest recovery-executes-a-fully-approved-workflow-after-a-crash
  (let [sys (system/test-system)]
    (api/request-decommission! sys (assoc request-cmd :required-approvals 1))
    ;; Commit approval events but simulate a crash before their reactor
    ;; dispatched :decommission-room.
    (api/approve-decommission! (assoc sys :reactors [])
                               {:decommission-id "d1" :actor-id "manager-bob"})
    (is (= :approved (:status (workflow-state sys "d1"))))
    (is (= ["101" "102" "103"] (api/available-rooms sys)))

    (is (= ["decommission-d1"] (api/recover-process-managers! sys)))
    (is (= :executed (:status (workflow-state sys "d1"))))
    (is (= ["102" "103"] (api/available-rooms sys)))))

(deftest recovery-leaves-workflows-waiting-for-human-approvals-alone
  (let [sys (system/test-system)]
    (api/request-decommission! sys request-cmd)
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "manager-bob"})
    (is (= :awaiting-approvals (:status (workflow-state sys "d1"))))
    (is (empty? (api/recover-process-managers! sys)))
    (is (= :awaiting-approvals (:status (workflow-state sys "d1"))))))

(deftest required-approvals-can-be-set-per-request
  ;; A workflow can require 1, 2, ... up to 10 approvals.
  (let [sys (system/test-system)]
    (api/request-decommission! sys (assoc request-cmd :required-approvals 3))
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "m1"})
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "m2"})
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "two approvals - still not enough for a 3-approver workflow")
    (api/approve-decommission! sys {:decommission-id "d1" :actor-id "m3"})
    (is (= ["102" "103"] (api/available-rooms sys))
        "after the 3rd approval, decommission fires")))
