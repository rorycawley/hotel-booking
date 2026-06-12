(ns hotel.booking.slices.approve-decommission.decide
  "PROCESS MANAGER for 'Decommission a room with N approvals'.

   This is the registry-grade pattern of multi-party approval: a
   sensitive change (decommission, dissolution, change of registered
   address) requires approvals from N DISTINCT actors before it
   executes. Rejection by any approver aborts the process.

   FSM of one approval workflow:

     :not-started ──Request──► :awaiting-approvals
        :awaiting-approvals ──Approval (<Nth)──► :awaiting-approvals
        :awaiting-approvals ──Approval (Nth)──► :approved
        :awaiting-approvals ──Reject──► :rejected            (terminal)
        :approved ──Executed (decommission-room done)──► :executed (terminal)

   Constraints baked into `decide`:
     - the same actor cannot approve twice
     - the requester cannot approve their own request (separation of duties)
     - the Nth approval ALSO emits `:decommission-fully-approved` so
       the pure reactor can dispatch the actual decommission without
       reading state")

(def initial-state {:status :not-started})

(defn evolve [state event]
  (case (:event/type event)
    :decommission-requested
    (merge state {:status            :awaiting-approvals
                  :decommission-id   (:decommission-id event)
                  :room-id           (:room-id event)
                  :required-approvals (:required-approvals event)
                  :requested-by      (:requested-by event)
                  :approvals         #{}})

    :decommission-approved
    (update state :approvals conj (:approver event))

    :decommission-fully-approved
    (assoc state :status :approved)

    :decommission-rejected
    (assoc state :status :rejected
           :rejected-by   (:rejected-by event)
           :reject-reason (:reason event))

    :decommission-executed
    (assoc state :status :executed)

    state))

(defn terminal? [state]
  (contains? #{:rejected :executed} (:status state)))

(def command-schema
  [:multi {:dispatch :command/type}
   [:request-decommission
    [:map
     [:command/type [:= :request-decommission]]
     [:decommission-id    :string]
     [:room-id            :string]
     [:required-approvals [:int {:min 1 :max 10}]]
     [:actor-id           :string]]]
   [:approve-decommission
    [:map
     [:command/type [:= :approve-decommission]]
     [:decommission-id :string]
     [:actor-id        :string]]]
   [:reject-decommission
    [:map
     [:command/type [:= :reject-decommission]]
     [:decommission-id :string]
     [:actor-id        :string]
     [:reason          [:or :string :keyword]]]]
   [:record-decommission-executed
    [:map
     [:command/type [:= :record-decommission-executed]]
     [:decommission-id :string]]]])

(def decider
  {:command-schema command-schema
   :initial-state  initial-state
   :evolve         evolve
   :terminal?      terminal?
   :decide
   (fn [state {:command/keys [type] :as command}]
     (case type
       :request-decommission
       (if (= :not-started (:status state))
         {:events [{:event/type        :decommission-requested
                    :decommission-id   (:decommission-id command)
                    :room-id           (:room-id command)
                    :required-approvals (:required-approvals command)
                    :requested-by      (:actor-id command)}]}
         {:error :already-started})

       :approve-decommission
       (cond
         (not= :awaiting-approvals (:status state))
         {:error :not-awaiting-approvals}

         (= (:actor-id command) (:requested-by state))
         {:error :requester-cannot-self-approve}

         (contains? (:approvals state #{}) (:actor-id command))
         {:error :actor-already-approved}

         :else
         (let [new-count (inc (count (:approvals state #{})))
               required  (:required-approvals state)
               threshold-crossed? (>= new-count required)]
           {:events (cond-> [{:event/type      :decommission-approved
                              :decommission-id (:decommission-id state)
                              :approver        (:actor-id command)}]
                      threshold-crossed?
                      (conj {:event/type :decommission-fully-approved
                             :decommission-id (:decommission-id state)
                             :room-id    (:room-id state)
                             :guest      ::no-guest-on-decommission}))}))

       :reject-decommission
       (if (contains? #{:awaiting-approvals :approved} (:status state))
         {:events [{:event/type :decommission-rejected
                    :decommission-id (:decommission-id state)
                    :rejected-by (:actor-id command)
                    :reason (:reason command)}]}
         {:error :not-awaiting-approvals})

       :record-decommission-executed
       (if (= :approved (:status state))
         {:events [{:event/type :decommission-executed
                    :decommission-id (:decommission-id state)}]}
         {:error :not-in-approved-state})))})

(defn react
  "PURE. Only the threshold-crossing event triggers the real
   `:decommission-room` command. on-success records execution on this
   PM's own stream so the FSM advances to :executed (terminal)."
  [event]
  (case (:event/type event)
    :decommission-fully-approved
    [{:effect/type :dispatch-command
      :command    {:command/type :decommission-room
                   :room-id      (:room-id event)}
      :on-success {:command/type :record-decommission-executed
                   :decommission-id (:decommission-id event)}
      :on-failure {:command/type :reject-decommission
                   :decommission-id (:decommission-id event)
                   :reason "downstream-rejected"}}]
    nil))
