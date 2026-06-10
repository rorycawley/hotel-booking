(ns hotel.booking.ordering-test
  "Behaviour: WHO WAS FIRST, court edition.
   1. Every accepted event is stamped with a UTC uncertainty interval.
   2. Overlapping intervals are temporally incomparable - clocks alone
      cannot answer 'who was first'.
   3. The event store's global order CAN, with no clock at all: legal
      order = order of acceptance by the authority.
   All in memory, no I/O."
  (:require [clojure.test :refer [deftest is]]
            [hotel.system.interface :as system]
            [hotel.booking.interface :as api]
            [hotel.event-store.interface :as es]
            [hotel.clock.interface :as clock]))

(defn- book [room] {:room-id room
                    :guest {:name "Ada" :email "ada@example.com"}
                    :check-in "2026-07-01" :check-out "2026-07-03"})

(deftest accepted-events-are-stamped-with-the-clock-interval
  (let [sys (system/test-system)]
    (clock/set-time! (:clock sys) 1750000000000)
    (clock/set-uncertainty! (:clock sys) 5)
    (let [[event] (:events (api/book-room! sys (book "101")))]
      (is (= {:earliest 1750000000000 :latest 1750000000005}
             (:recorded-at event))))))

(deftest overlapping-intervals-cannot-be-ordered-but-the-log-can
  (let [sys (system/test-system)]
    ;; two "nodes" book within each other's clock uncertainty:
    ;; node A at t=...000 with ±2s; node B 50ms later, same bound
    (clock/set-uncertainty! (:clock sys) 2000)
    (api/book-room! sys (book "101"))
    (clock/advance! (:clock sys) 50)
    (api/book-room! sys (book "102"))
    (let [[e1 e2] (es/read-all (:event-store sys))]
      (is (clock/overlapping? (:recorded-at e1) (:recorded-at e2))
          "by clock alone, who was first is UNDECIDABLE")
      (is (= ["101" "102"] (map :room-id [e1 e2]))
          "the store's global order decides - that is the legal order"))))

(deftest non-overlapping-intervals-are-a-court-safe-claim
  (let [a {:earliest 1000 :latest 1005}
        b {:earliest 1010 :latest 1015}]
    (is (true?  (clock/definitely-before? a b)))
    (is (false? (clock/overlapping? a b)))))
