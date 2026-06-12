(ns hotel.subject-keys.crypto
  "PURE crypto helpers. AES-256-GCM (authenticated): tampering produces
   a decryption error rather than silent corruption. Nonce is random
   per-encryption and stored in front of the ciphertext as a single
   base64 blob.

   Output format: base64(nonce || ciphertext || authtag), so a payload
   field that LOOKS encrypted is a single string the JSONB layer can
   round-trip without surprises."
  (:import [javax.crypto Cipher]
           [javax.crypto.spec SecretKeySpec GCMParameterSpec]
           [java.util Base64]
           [java.security SecureRandom]))

(def ^:private nonce-size  12)              ; GCM standard
(def ^:private tag-bits    128)
(def ^:private ^SecureRandom rng (SecureRandom.))

(defn new-key-bytes
  "32 random bytes = a 256-bit AES key. Each subject gets one."
  []
  (let [b (byte-array 32)] (.nextBytes rng b) b))

(defn- random-nonce []
  (let [n (byte-array nonce-size)] (.nextBytes rng n) n))

(defn- cipher ^Cipher [mode ^bytes key ^bytes nonce]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init (int mode)
           (SecretKeySpec. key "AES")
           (GCMParameterSpec. tag-bits nonce))))

(defn encrypt
  "PURE. plaintext-str + key-bytes -> base64 string. Nil/empty key
   throws - encryption must NEVER succeed silently with no key."
  [^bytes key ^String plaintext]
  (when-not key
    (throw (ex-info "Cannot encrypt without a key" {})))
  (let [nonce (random-nonce)
        ct    (.doFinal (cipher Cipher/ENCRYPT_MODE key nonce)
                        (.getBytes plaintext "UTF-8"))
        out   (byte-array (+ nonce-size (alength ct)))]
    (System/arraycopy nonce 0 out 0 nonce-size)
    (System/arraycopy ct    0 out nonce-size (alength ct))
    (.encodeToString (Base64/getEncoder) out)))

(defn decrypt
  "PURE. base64 string + key-bytes -> plaintext string OR ::erased if
   the key has been destroyed. Tampering throws (GCM auth tag fails)."
  [key ^String b64]
  (if (nil? key)
    ::erased
    (let [blob  (.decode (Base64/getDecoder) b64)
          nonce (byte-array nonce-size)
          ct    (byte-array (- (alength blob) nonce-size))]
      (System/arraycopy blob 0 nonce 0 nonce-size)
      (System/arraycopy blob nonce-size ct 0 (alength ct))
      (String. (.doFinal (cipher Cipher/DECRYPT_MODE key nonce) ct)
               "UTF-8"))))

;; ---- BYTES variants (for blobs - documents, scanned ids) -----------------

(defn encrypt-bytes
  "PURE. plaintext bytes + key -> [nonce || ciphertext] bytes. Used for
   files: don't base64 (storage layer handles binary)."
  [^bytes key ^bytes plaintext]
  (when-not key
    (throw (ex-info "Cannot encrypt without a key" {})))
  (let [nonce (random-nonce)
        ct    (.doFinal (cipher Cipher/ENCRYPT_MODE key nonce) plaintext)
        out   (byte-array (+ nonce-size (alength ct)))]
    (System/arraycopy nonce 0 out 0 nonce-size)
    (System/arraycopy ct    0 out nonce-size (alength ct))
    out))

(defn decrypt-bytes
  "PURE. [nonce || ciphertext] bytes + key -> plaintext bytes OR ::erased
   when the key has been destroyed. Tampering throws (GCM auth tag fails)."
  [key ^bytes blob]
  (if (nil? key)
    ::erased
    (let [nonce (byte-array nonce-size)
          ct    (byte-array (- (alength blob) nonce-size))]
      (System/arraycopy blob 0 nonce 0 nonce-size)
      (System/arraycopy blob nonce-size ct 0 (alength ct))
      (.doFinal (cipher Cipher/DECRYPT_MODE key nonce) ct))))
