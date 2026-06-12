(ns hotel.booking.pii
  "PII path registry + encrypt-at-write / decrypt-at-read seam.

   Subject identity is computed by HASHING a stable identifier (here:
   the guest's email, lowercased). That hash is stored on the event as
   `:subject-id` - a non-PII opaque token. The PII fields themselves
   become ciphertext. Decryption looks up the key by the stored hash,
   not by the encrypted email - otherwise we couldn't find the key.

   Erasure is exposed at the boundary as `erase-by-natural-id`, which
   does the same hashing and destroys the key. After that, every
   historical event for that subject decrypts to :erased markers."
  (:require [clojure.string :as str]
            [hotel.subject-keys.interface :as keys])
  (:import [java.security MessageDigest]
           [java.util Base64]))

;; ---- subject identity ---------------------------------------------------

(defn- sha256 [^String s]
  (.encodeToString (Base64/getEncoder)
                   (.digest (doto (MessageDigest/getInstance "SHA-256")
                              (.update (.getBytes s "UTF-8"))))))

(defn natural-id->subject-id
  "Stable opaque subject-id derived from a natural identifier (email).
   Same shape regardless of input casing/whitespace - normalise once."
  [natural-id]
  (when natural-id
    (sha256 (-> natural-id str str/trim str/lower-case))))

;; ---- registry ----------------------------------------------------------

(def pii-paths-by-event
  "{event-type [[paths] ...]}. Walked by the encrypt/decrypt passes;
   anything outside this map is left alone."
  {:room-booked    [[:guest :name] [:guest :email]]
   :move-requested [[:guest :name] [:guest :email]]})

(def natural-id-by-event
  "{event-type (fn [event] -> natural-id)}. Used at WRITE time to compute
   the subject-id. Reads use the stamped :subject-id instead."
  {:room-booked    #(get-in % [:guest :email])
   :move-requested #(get-in % [:guest :email])})

;; ---- walkers -----------------------------------------------------------

(defn- update-in-when
  "Like update-in but only when the path exists with a non-nil value."
  [m path f]
  (let [v (get-in m path)]
    (if (some? v) (assoc-in m path (f v)) m)))

(defn encrypt-event
  "Write-time: stamp :subject-id (the hash of the natural id) and
   encrypt every leaf in the PII registry with the subject's key.
   Refuses to encrypt for an erased subject - inserting fresh PII for
   someone who has been erased is either a bug or a re-identification
   attempt; either way we want it loud."
  [subject-keys event]
  (let [paths (pii-paths-by-event (:event/type event))]
    (if (empty? paths)
      event
      (let [natural-id (when-let [f (natural-id-by-event (:event/type event))]
                         (f event))
            subject-id (natural-id->subject-id natural-id)]
        (when (keys/erased? subject-keys subject-id)
          (throw (ex-info "Cannot record PII about an ERASED subject"
                          {:subject-id subject-id
                           :event-type (:event/type event)})))
        (let [k (keys/get-or-create-key subject-keys subject-id)
              with-id (assoc event :subject-id subject-id)]
          (reduce (fn [e path] (update-in-when e path #(keys/encrypt k %)))
                  with-id paths))))))

(defn decrypt-event
  "Read-time: look up the key by the event's stamped :subject-id and
   decrypt every leaf in the PII registry. If the subject's key has
   been erased, every leaf becomes :erased."
  [subject-keys event]
  (let [paths      (pii-paths-by-event (:event/type event))
        subject-id (:subject-id event)]
    (if (or (empty? paths) (nil? subject-id))
      event
      (let [k (when-not (keys/erased? subject-keys subject-id)
                (keys/get-or-create-key subject-keys subject-id))]
        (reduce (fn [e path]
                  (update-in-when e path
                                  #(let [out (keys/decrypt k %)]
                                     (if (= :hotel.subject-keys.crypto/erased out)
                                       :erased
                                       out))))
                event paths)))))

(defn encrypt-events [subject-keys events] (mapv #(encrypt-event subject-keys %) events))
(defn decrypt-events [subject-keys events] (mapv #(decrypt-event subject-keys %) events))

(defn erase-by-natural-id!
  "GDPR Article 17. Returns the opaque subject-id used internally."
  [subject-keys natural-id]
  (let [subject-id (natural-id->subject-id natural-id)]
    (keys/erase-subject! subject-keys subject-id)
    subject-id))
