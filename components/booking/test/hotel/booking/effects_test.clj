(ns hotel.booking.effects-test
  "Behaviour of the effect interpreter under the OUTBOX architecture:
     - :publish goes through the TRANSACTIONAL outbox (asserted in the
       partitioning pure step and in the integration flow tests).
     - :notify-booking-confirmed goes through the GuestNotifications port.
     - :dispatch-command runs the next use case and threads identity."
  (:require [clojure.test :refer [deftest is]]
            [hotel.system.interface :as system]
            [hotel.event-store.interface :as es]
            [hotel.booking.effects :as effects]
            [hotel.booking.interface :as api]))

(def booking-command
  {:room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01"
   :check-out "2026-07-03"})

(def booked-event
  {:event/type :room-booked
   :event/id (java.util.UUID/randomUUID)
   :correlation-id (java.util.UUID/randomUUID)
   :room-id "101"
   :guest {:name "Ada" :email "ada@example.com"}
   :check-in "2026-07-01"
   :check-out "2026-07-03"})

;; --- partitioning (PURE) -----------------------------------------------

(deftest valid-publish-effects-go-to-the-publish-bucket
  (let [sys {:reactors [(fn [event]
                          [{:effect/type :publish
                            :topic       "booking.room-booked"
                            :payload     {:type "RoomBooked"
                                          :version 1
                                          :room-id (:room-id event)
                                          :check-in (:check-in event)}}])]}
        {:keys [publish other invalid]} (effects/partition-reactor-output
                                         sys [booked-event])]
    (is (= 1 (count publish)))
    (is (empty? other))
    (is (empty? invalid))))

(deftest invalid-publish-effects-go-to-the-invalid-bucket
  (let [sys {:reactors [(fn [_]
                          [{:effect/type :publish
                            :topic       "booking.room-booked"
                            :payload     {:type "RoomBooked"}}])]}     ; missing fields
        {:keys [publish invalid]} (effects/partition-reactor-output
                                   sys [booked-event])]
    (is (empty? publish))
    (is (= 1 (count invalid)))))

(deftest unknown-topic-publish-effects-go-to-the-invalid-bucket
  (let [sys {:reactors [(fn [_]
                          [{:effect/type :publish
                            :topic       "booking.no-such-topic"
                            :payload     {:type "Whatever" :version 1}}])]}
        {:keys [invalid]} (effects/partition-reactor-output sys [booked-event])]
    (is (= 1 (count invalid)))))

(deftest non-publish-effects-go-to-the-other-bucket
  (let [sys {:reactors [(fn [_]
                          [{:effect/type :notify-booking-confirmed}])]}
        {:keys [other]} (effects/partition-reactor-output sys [booked-event])]
    (is (= 1 (count other)))))

;; --- end-to-end through the outbox -------------------------------------

(deftest a-valid-publish-effect-becomes-an-outbox-message
  (let [sys (system/test-system)]
    (api/book-room! sys booking-command)
    (let [pending (es/claim-outbox-messages (:event-store sys) 100)]
      (is (= 1 (count pending)))
      (is (= "booking.room-booked" (-> pending first :topic)))
      (is (uuid? (-> pending first :message-id)))
      (is (uuid? (-> pending first :correlation-id))))))

(deftest draining-the-relay-publishes-to-the-integration-port
  (let [sys (system/test-system)]
    (api/book-room! sys booking-command)
    (is (= [] @(:published (:publisher sys)))   "not yet - the relay hasn't run")
    (system/flush! sys)
    (is (= 1 (count @(:published (:publisher sys)))))))

(deftest notify-effects-go-through-the-guest-notifications-port
  (let [sys (system/test-system)
        booking {:guest {:name "Ada" :email "ada@example.com"}
                 :room-id "101" :check-in "2026-07-01" :check-out "2026-07-03"}]
    (effects/execute! sys (assoc booking :effect/type :notify-booking-confirmed))
    (is (= [booking] @(:sent (:guest-notifications sys))))))

;; --- dispatch-command (process manager continuation) -------------------

(deftest dispatch-command-runs-the-next-use-case
  (let [sys (system/test-system)]
    (api/book-room! sys booking-command)
    (effects/execute! sys {:effect/type :dispatch-command
                           :source-event booked-event
                           :command {:command/type :cancel-booking
                                     :room-id "101"}})
    (is (= ["101" "102" "103"] (api/available-rooms sys))
        "the dispatched CancelBooking freed the room")))

(deftest dispatch-command-continuation-errors-are-returned
  (let [sys {:reactors []
             :command-handlers
             {:first  (fn [_ _] {:events []})
              :second (fn [_ _] {:error :continuation-failed})}}
        result (effects/execute! sys {:effect/type :dispatch-command
                                      :source-event booked-event
                                      :command   {:command/type :first}
                                      :on-success {:command/type :second}})]
    (is (= :continuation-failed (:error result)))))

;; --- ProblemSink: post-commit failures are RECORDED, not swallowed ----

(deftest invalid-publishes-get-reported-to-the-problem-sink
  (let [sys (assoc (system/test-system)
                   :reactors [(fn [_]
                                [{:effect/type :publish
                                  :topic       "booking.no-such-topic"
                                  :payload     {}}])])]
    (api/book-room! sys booking-command)
    (let [problems @(:problems (:problem-sink sys))]
      (is (= 1 (count problems)))
      (is (= :invalid-publish-effect-blocked-at-contract
             (-> problems first :problem))))))

(deftest post-commit-effect-failures-get-reported-to-the-problem-sink
  ;; A dispatch-command effect whose inner command fails -> recorded
  (let [sys (assoc (system/test-system)
                   :reactors [(fn [event]
                                (when (= :room-booked (:event/type event))
                                  [{:effect/type :dispatch-command
                                    :command {:command/type :cancel-booking
                                              :room-id "999"}}]))])]
    (api/book-room! sys booking-command)
    (let [problems @(:problems (:problem-sink sys))]
      (is (some #(= :post-commit-effect-failed (:problem %)) problems)
          "the failed inner cancel-booking surfaces as a structured problem"))))
