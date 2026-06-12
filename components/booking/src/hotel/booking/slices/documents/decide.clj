(ns hotel.booking.slices.documents.decide
  "PURE Decider for one supporting document. A document has its own
   stream `document-<id>` so we get audit-by-author and idempotency
   on every upload.

   FSM:
     :absent  ──:upload-document──► :stored   (terminal)

   The stream is single-shot - the blob's content is by definition the
   one the uploader committed; re-uploading should be a NEW document.
   `:stored` is terminal so the decider rejects any further commands.")

(def initial-state {:status :absent})

(defn evolve [state event]
  (case (:event/type event)
    :document-uploaded
    (merge state {:status        :stored
                  :document-id   (:document-id event)
                  :content-hash  (:content-hash event)
                  :content-type  (:content-type event)
                  :subject-id    (:subject-id event)})
    state))

(defn terminal? [state] (= :stored (:status state)))

(def command-schema
  [:map
   [:command/type [:= :upload-document]]
   [:document-id   :string]
   [:content-hash  :string]
   [:content-type  :string]
   [:size-bytes    :int]
   [:subject-id    :string]
   [:actor-id      :string]])

(def decider
  {:command-schema command-schema
   :initial-state  initial-state
   :evolve         evolve
   :terminal?      terminal?
   :decide
   (fn [state command]
     (if (= :absent (:status state))
       {:events [{:event/type   :document-uploaded
                  :document-id  (:document-id command)
                  :content-hash (:content-hash command)
                  :content-type (:content-type command)
                  :size-bytes   (:size-bytes command)
                  ;; subject-id is the opaque hash (already non-PII).
                  :subject-id   (:subject-id command)}]}
       {:error :document-already-uploaded}))})
