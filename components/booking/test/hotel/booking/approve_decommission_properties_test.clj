(ns hotel.booking.approve-decommission-properties-test
  "Property-based tests for the multi-party approval PM. This is where
   property tests pay rent: the threshold counting + distinct-approver
   logic + self-approval guard has multiple interacting rules whose
   joint behaviour is hard to fully cover with example-based tests."
  (:require [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hotel.booking.slices.approve-decommission.decide :as decide]))

;; ---- helpers -----------------------------------------------------------

(defn- apply-commands
  "Run commands through decide+evolve threading state - the same loop the
   shell does, but in memory. Returns the final state."
  [initial-state commands]
  (reduce (fn [state cmd]
            (let [result ((:decide decide/decider) state cmd)]
              (if (:events result)
                (reduce (:evolve decide/decider) state (:events result))
                state)))
          initial-state
          commands))

(defn- request-cmd [required requester]
  {:command/type        :request-decommission
   :decommission-id     "d1"
   :room-id             "1"
   :required-approvals  required
   :actor-id            requester})

(defn- approve-cmd [actor]
  {:command/type    :approve-decommission
   :decommission-id "d1"
   :actor-id        actor})

(defn- reject-cmd [actor reason]
  {:command/type    :reject-decommission
   :decommission-id "d1"
   :actor-id        actor
   :reason          reason})

;; ---- generators --------------------------------------------------------

(def requester-gen (gen/return "requester"))

(def non-requester-actor-gen
  (gen/such-that #(not= "requester" %)
                 (gen/fmap #(str "actor-" %) gen/string-alphanumeric)
                 100))

;; ---- properties --------------------------------------------------------

(defspec n-distinct-non-requester-approvers-always-reaches-approved 100
  (prop/for-all [required (gen/choose 1 5)
                 approvers (gen/such-that #(>= (count (set %)) 5)
                                          (gen/vector non-requester-actor-gen 5 8)
                                          200)]
                (let [distinct-approvers (vec (distinct approvers))
                      chosen             (take required distinct-approvers)
                      commands           (cons (request-cmd required "requester")
                                               (map approve-cmd chosen))
                      final              (apply-commands decide/initial-state commands)]
                  (= :approved (:status final)))))

(defspec fewer-than-N-distinct-approvers-does-NOT-reach-approved 100
  (prop/for-all [required (gen/choose 2 5)
                 raw-approvers (gen/vector non-requester-actor-gen 1 5)]
                (let [distinct-approvers (distinct raw-approvers)
                      n-approvers        (count distinct-approvers)
                      commands           (cons (request-cmd required "requester")
                                               (map approve-cmd distinct-approvers))
                      final              (apply-commands decide/initial-state commands)]
      ;; Property: status reaches :approved IFF distinct approvers >= required.
                  (= (>= n-approvers required) (= :approved (:status final))))))

(defspec same-actor-approving-multiple-times-counts-once 100
  (prop/for-all [required (gen/choose 2 4)
                 actor non-requester-actor-gen
                 repeats (gen/choose 2 6)]
                (let [commands (cons (request-cmd required "requester")
                                     (repeat repeats (approve-cmd actor)))
                      final    (apply-commands decide/initial-state commands)]
                  (and (= :awaiting-approvals (:status final))
                       (= 1 (count (:approvals final)))))))

(defspec the-requester-can-never-self-approve 200
  (prop/for-all [required (gen/choose 1 5)]
                (let [state-after-request
                      (reduce (:evolve decide/decider)
                              decide/initial-state
                              (:events ((:decide decide/decider)
                                        decide/initial-state
                                        (request-cmd required "requester"))))
                      self-approve-result
                      ((:decide decide/decider)
                       state-after-request
                       (approve-cmd "requester"))]
                  (= :requester-cannot-self-approve (:error self-approve-result)))))

(defspec reject-from-awaiting-state-is-terminal 100
  (prop/for-all [required  (gen/choose 1 5)
                 approvers (gen/vector non-requester-actor-gen 0 5)
                 rejecter  non-requester-actor-gen
                 reason    gen/string-alphanumeric]
                (let [;; we want the workflow NOT to have reached :approved yet
                      partial-approvers (take (dec required) (distinct approvers))
                      commands (concat [(request-cmd required "requester")]
                                       (map approve-cmd partial-approvers)
                                       [(reject-cmd rejecter reason)])
                      final (apply-commands decide/initial-state commands)]
                  (and (= :rejected (:status final))
                       ((:terminal? decide/decider) final)))))

(defspec approvals-after-approved-do-not-go-backwards 50
  ;; Once approved, additional approve commands hit the terminal guard
  ;; in the GENERIC decider (decider/handle wraps terminal? before the
  ;; slice's decide). Here we just verify the slice's decide doesn't
  ;; itself produce a state-changing event when status is :approved.
  ;; `gen/let` is used because the `:approvers` generator depends on
  ;; the value drawn for `:required` - prop/for-all alone would not
  ;; thread the binding through to the inner predicate.
  (prop/for-all [{:keys [required approvers]}
                 (gen/let [required (gen/choose 1 3)
                           approvers (gen/such-that
                                      #(>= (count (distinct %)) (inc required))
                                      (gen/vector non-requester-actor-gen 5 8)
                                      200)]
                   {:required required :approvers approvers})]
                (let [distinct-approvers (distinct approvers)
                      required-cmds (concat [(request-cmd required "requester")]
                                            (map approve-cmd (take required distinct-approvers)))
                      state-at-approved (apply-commands decide/initial-state required-cmds)
          ;; another distinct approver tries to approve after :approved
                      extra-approver (nth distinct-approvers required)
                      result ((:decide decide/decider) state-at-approved (approve-cmd extra-approver))]
                  (is (= :not-awaiting-approvals (:error result))
                      "an approval submitted after the threshold is already met is rejected"))))
