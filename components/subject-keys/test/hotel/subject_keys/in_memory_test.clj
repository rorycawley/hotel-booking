(ns hotel.subject-keys.in-memory-test
  (:require [clojure.test :refer [deftest is]]
            [hotel.subject-keys.interface :as keys]
            [hotel.subject-keys.crypto :as crypto]))

(deftest get-or-create-key-is-idempotent-per-subject
  (let [store (keys/in-memory)
        k1 (keys/get-or-create-key store "ada@example.com")
        k2 (keys/get-or-create-key store "ada@example.com")]
    (is (= (seq k1) (seq k2)) "same subject -> same key bytes")))

(deftest different-subjects-get-different-keys
  (let [store (keys/in-memory)]
    (is (not= (seq (keys/get-or-create-key store "a@a.io"))
              (seq (keys/get-or-create-key store "b@b.io"))))))

(deftest encrypt-then-decrypt-round-trips
  (let [store (keys/in-memory)
        k (keys/get-or-create-key store "ada@example.com")
        ct (keys/encrypt k "Ada Lovelace")]
    (is (string? ct))
    (is (= "Ada Lovelace" (keys/decrypt k ct)))))

(deftest erasure-makes-future-decryption-return-the-erased-marker
  (let [store (keys/in-memory)
        k (keys/get-or-create-key store "ada@example.com")
        ct (keys/encrypt k "Ada Lovelace")]
    (is (false? (keys/erased? store "ada@example.com")))
    (keys/erase-subject! store "ada@example.com")
    (is (true? (keys/erased? store "ada@example.com")))
    (is (nil? (keys/get-or-create-key store "ada@example.com"))
        "no key reconstitution after erasure")
    (is (= ::crypto/erased (keys/decrypt nil ct))
        "decrypt with nil key returns the erased marker, doesn't throw")))

(deftest a-tampered-ciphertext-FAILS-decryption
  (let [store (keys/in-memory)
        k (keys/get-or-create-key store "ada@example.com")
        ct (keys/encrypt k "Ada Lovelace")
        tampered (str (subs ct 0 (- (count ct) 4)) "XXXX")]
    (is (thrown? Exception (keys/decrypt k tampered))
        "AES-GCM auth tag failure surfaces, no silent corruption")))
