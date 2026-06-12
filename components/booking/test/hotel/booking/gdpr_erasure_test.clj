(ns hotel.booking.gdpr-erasure-test
  "The GDPR Article 17 contract: a data subject's right to be forgotten,
   on top of an IMMUTABLE event log."
  (:require [clojure.test :refer [deftest is]]
            [hotel.booking.interface :as api]
            [hotel.event-store.interface :as es]
            [hotel.system.interface :as system]))

(def ada-cmd
  {:room-id "101"
   :guest   {:name "Ada Lovelace" :email "ada@example.com"}
   :check-in "x" :check-out "y"
   :actor-id "auth0|admin"})

(deftest pii-is-stored-encrypted-not-plaintext
  (let [sys (system/test-system)]
    (api/book-room! sys ada-cmd)
    ;; PEEK at the raw event in the in-memory journal - the PII fields
    ;; must NOT contain the plaintext name or email.
    (let [raw (-> @(:db (:event-store sys)) :all first)]
      (is (not= "Ada Lovelace"    (-> raw :guest :name)))
      (is (not= "ada@example.com" (-> raw :guest :email)))
      (is (string? (-> raw :subject-id))
          "every PII event is stamped with an opaque subject-id"))))

(deftest a-normal-read-via-the-decider-RETURNS-plaintext
  (let [sys (system/test-system)
        cmd-id (java.util.UUID/randomUUID)]
    ;; first time it commits; cached replay must return the same shape
    (api/book-room! sys (assoc ada-cmd :command/id cmd-id))
    (let [replay (api/book-room! sys (assoc ada-cmd :command/id cmd-id))]
      (is (= "Ada Lovelace" (-> replay :events first :guest :name))
          "cached replay round-trips plaintext"))))

(deftest erasure-leaves-the-events-but-makes-pii-unreadable
  (let [sys (system/test-system)]
    (api/book-room! sys ada-cmd)
    (let [events-before (es/read-stream (:event-store sys) "room-101")
          subject-id    (api/erase-by-natural-id! sys "ada@example.com")
          events-after  (es/read-stream (:event-store sys) "room-101")]
      (is (some? subject-id))
      (is (= (count events-before) (count events-after))
          "events themselves remain - the journal stays immutable")
      (is (= (mapv :event/id events-before) (mapv :event/id events-after))
          "same event identities, no rewriting"))))

(deftest after-erasure-the-pii-fields-decrypt-to-the-erased-marker
  (let [sys    (system/test-system)
        cmd-id (java.util.UUID/randomUUID)]
    (api/book-room! sys (assoc ada-cmd :command/id cmd-id))
    (api/erase-by-natural-id! sys "ada@example.com")
    ;; Replay through the cached path. Cache hit -> decrypts -> :erased.
    (let [replay (api/book-room! sys (assoc ada-cmd :command/id cmd-id))]
      (is (= :erased (-> replay :events first :guest :name)))
      (is (= :erased (-> replay :events first :guest :email))))))

(deftest cannot-record-fresh-pii-about-an-erased-subject
  (let [sys (system/test-system)]
    (api/book-room! sys ada-cmd)
    (api/erase-by-natural-id! sys "ada@example.com")
    (is (thrown? Exception
                 (api/book-room! sys (assoc ada-cmd
                                            :command/id (java.util.UUID/randomUUID)
                                            :room-id "888")))
        "writing new PII about Ada must fail LOUDLY")))
