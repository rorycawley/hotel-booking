(ns hotel.booking.slices.documents.handler
  "Impure shell for document upload + retrieval. Envelope encryption:
   one random per-document AES-256 key encrypts the BYTES; the per-doc
   key is wrapped with the subject's key and stored alongside the
   ciphertext as MinIO user metadata. Erasing the subject's key makes
   every wrapped key for them unrecoverable - the blob in storage stays
   intact but reads as :erased forever after.

   This file is on the same allow-list as decider.clj and effects.clj:
   it touches ports (subject-keys, document-store, event-store) but
   the decision logic is in decide.clj."
  (:require [hotel.booking.decider :as decider]
            [hotel.booking.pii :as pii]
            [hotel.booking.slices.documents.decide :as core]
            [hotel.document-store.interface :as docs]
            [hotel.ids.interface :as ids]
            [hotel.subject-keys.interface :as keys])
  (:import [java.security MessageDigest]
           [java.util Base64]))

(defn- sha256-b64 [^bytes b]
  (.encodeToString (Base64/getEncoder)
                   (.digest (doto (MessageDigest/getInstance "SHA-256")
                              (.update b)))))

(defn- bytes->b64 [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn- b64->bytes [^String s]
  (.decode (Base64/getDecoder) s))

(defn- stream-id [document-id] (str "document-" document-id))

(defn- command-id-for [{id-source :ids} command]
  (or (:command/id command) (ids/new-id id-source)))

(defn- correlation-id-for [{id-source :ids} command]
  (or (:correlation-id command) (ids/new-id id-source)))

(defn- document-id-for [{id-source :ids} command-id command]
  (or (:document-id command)
      (str (ids/name-id id-source (str "document/" command-id)))))

(defn- cached-upload-result [{:keys [event-store subject-keys]} command-id]
  (when-let [{:keys [events correlation-id]}
             (decider/cached-command-result event-store subject-keys command-id)]
    (let [event (first events)]
      {:document-id    (:document-id event)
       :content-hash   (:content-hash event)
       :events         events
       :correlation-id correlation-id})))

(defn upload!
  "PUBLIC. Encrypts the blob with a fresh per-document key, wraps that
   key with the subject's key, persists to the document store, and
   commits a `:document-uploaded` event on the document's own stream.
   Returns the document-id + content-hash on success."
  [{:keys [subject-keys document-store] :as system}
   {:keys [^bytes bytes content-type natural-id actor-id] :as command}]
  (let [command-id  (command-id-for system command)
        command     (assoc command
                           :command/id command-id
                           :correlation-id (correlation-id-for system command))]
    (or (cached-upload-result system command-id)
        (let [document-id (document-id-for system command-id command)
              subject-id  (pii/natural-id->subject-id natural-id)
              _           (when (keys/erased? subject-keys subject-id)
                            (throw (ex-info "Cannot upload a document for an ERASED subject"
                                            {:subject-id subject-id})))
              subject-key (keys/get-or-create-key subject-keys subject-id)
              doc-key     (keys/new-key-bytes)
              ciphertext  (keys/encrypt-bytes doc-key bytes)
              wrapped     (keys/encrypt subject-key (bytes->b64 doc-key))
              content-hash (sha256-b64 bytes)]
          ;; 1) bytes go to storage FIRST. If this fails, no event is committed
          ;;    and the document never existed for the rest of the system.
          (docs/put-blob! document-store
                          {:blob-id     document-id
                           :bytes       ciphertext
                           :content-type content-type
                           :metadata    {"wrapped-key" wrapped
                                         "content-hash" content-hash
                                         "subject-id"  subject-id}})
          ;; 2) then the event. transactional-append + processed_commands
          ;;    gives normal idempotency on :command/id.
          (let [result (decider/handle
                        core/decider system (stream-id document-id)
                        {:command/type :upload-document
                         :command/id   command-id
                         :correlation-id (:correlation-id command)
                         :document-id  document-id
                         :content-hash content-hash
                         :content-type content-type
                         :size-bytes   (alength bytes)
                         :subject-id   subject-id
                         :actor-id     actor-id})]
            (if (:error result)
              ;; The blob is orphaned in MinIO; an operator cleanup task can
              ;; sweep blobs whose document stream is empty. Returning the
              ;; error to the caller is the right semantics - the upload
              ;; "didn't happen" from the application's point of view.
              result
              {:document-id    document-id
               :content-hash   content-hash
               :events         (:events result)
               :correlation-id (:correlation-id result)}))))))

(defn download
  "PUBLIC. Reads the blob, unwraps the doc-key with the subject's key,
   decrypts the bytes. Returns:
     {:bytes ^bytes :content-type s :content-hash s}
     :erased - subject was erased
     nil      - no such document"
  [{:keys [subject-keys document-store]} document-id]
  (when-let [{:keys [bytes content-type metadata]}
             (docs/get-blob document-store document-id)]
    (let [subject-id  (get metadata "subject-id")
          wrapped     (get metadata "wrapped-key")
          subject-key (when (and subject-id
                                 (not (keys/erased? subject-keys subject-id)))
                        (keys/get-or-create-key subject-keys subject-id))
          unwrapped   (when subject-key (keys/decrypt subject-key wrapped))]
      (cond
        (or (nil? subject-key)
            (= :hotel.subject-keys.crypto/erased unwrapped))
        :erased

        :else
        (let [doc-key   (b64->bytes unwrapped)
              plaintext (keys/decrypt-bytes doc-key bytes)]
          (when-not (= (sha256-b64 plaintext) (get metadata "content-hash"))
            (throw (ex-info "Document content hash mismatch - storage tampered or corrupted"
                            {:document-id document-id})))
          {:bytes plaintext
           :content-type content-type
           :content-hash (get metadata "content-hash")})))))
