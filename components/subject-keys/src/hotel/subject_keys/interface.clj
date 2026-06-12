(ns hotel.subject-keys.interface
  "Driven port for per-data-subject encryption keys + pure crypto.
   Adapters: in-memory (test); KMS/HSM (prod) is a thin reify away."
  (:require [hotel.subject-keys.protocol :as p]
            [hotel.subject-keys.in-memory :as in-memory]
            [hotel.subject-keys.crypto :as crypto]))

;; --- port operations
(def get-or-create-key p/get-or-create-key)
(def erase-subject!    p/erase-subject!)
(def erased?           p/erased?)

;; --- pure crypto (callers compose with the port)
(def encrypt        crypto/encrypt)
(def decrypt        crypto/decrypt)
(def encrypt-bytes  crypto/encrypt-bytes)
(def decrypt-bytes  crypto/decrypt-bytes)
(def new-key-bytes  crypto/new-key-bytes)

;; --- adapter constructors
(defn in-memory [] (in-memory/create))
